package org.catalogueoflife.garrulus.finder.dict;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.gbif.nameparser.api.Rank;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds a {@link ColDictionary} from a ColDP {@code NameUsage.tsv}. The file is streamed line by line and every word
 * is hashed as it is read, so no strings are retained (apart from the small common word list).
 */
public final class ColDictionaryBuilder {
  private static final Rank[] RANKS = Rank.values();

  private final CommonWords common;
  private final LongOpenHashSet genera = new LongOpenHashSet();
  private final LongOpenHashSet epithets = new LongOpenHashSet();
  private final LongOpenHashSet ambiguous = new LongOpenHashSet();
  private final LongOpenHashSet pairs = new LongOpenHashSet();
  /** hash -> flat [rankOrdinal, count, rankOrdinal, count, ...] while building */
  private final Long2ObjectOpenHashMap<int[]> uninomialCounts = new Long2ObjectOpenHashMap<>();
  private long rows;

  public ColDictionaryBuilder(CommonWords common) {
    this.common = common;
  }

  public long rows() {
    return rows;
  }

  public static ColDictionary fromNameUsageTsv(Reader tsv, String releaseKey, CommonWords common) throws IOException {
    ColDictionaryBuilder b = new ColDictionaryBuilder(common);
    b.read(tsv);
    return b.build(releaseKey);
  }

  /**
   * Streams the NameUsage.tsv entry of an XR zip. The release key is read from metadata.yaml ({@code key:}, falling back
   * to {@code version:}), wherever in the zip it is.
   */
  public static ColDictionary fromXrZip(Path zip, CommonWords common) throws IOException {
    try (InputStream in = Files.newInputStream(zip)) {
      return fromXrZip(in, common);
    }
  }

  public static ColDictionary fromXrZip(Path zip) throws IOException {
    return fromXrZip(zip, CommonWords.load(CommonWords.DEFAULT_LANGS));
  }

  public static ColDictionary fromXrZip(InputStream raw, CommonWords common) throws IOException {
    ColDictionaryBuilder b = new ColDictionaryBuilder(common);
    String key = null;
    boolean read = false;
    try (ZipInputStream zin = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
      ZipEntry e;
      while ((e = zin.getNextEntry()) != null) {
        String n = e.getName();
        if (n.endsWith("metadata.yaml")) {
          key = releaseKeyFromYaml(new BufferedReader(new InputStreamReader(zin, StandardCharsets.UTF_8)));
        } else if (n.endsWith("NameUsage.tsv") && !read) {
          // do not close zin: the reader wraps it and the zip is closed by try-with-resources
          b.read(new InputStreamReader(zin, StandardCharsets.UTF_8));
          read = true;
        }
        if (read && key != null) break;
      }
    }
    if (!read) throw new IOException("No NameUsage.tsv in the XR zip");
    if (key == null) throw new IOException("No key: or version: in metadata.yaml of the XR zip");
    return b.build(key);
  }

  /** Top level scalar {@code key:}, else {@code version:}, of a metadata.yaml, read line by line. */
  static String releaseKeyFromYaml(String yaml) {
    try {
      return releaseKeyFromYaml(new BufferedReader(new java.io.StringReader(yaml)));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  static String releaseKeyFromYaml(BufferedReader r) throws IOException {
    String key = null, version = null, line;
    while ((line = r.readLine()) != null) {
      if (line.isEmpty() || Character.isWhitespace(line.charAt(0)) || line.charAt(0) == '#') continue;
      int c = line.indexOf(':');
      if (c <= 0) continue;
      String name = line.substring(0, c).strip();
      boolean isKey = name.equals("key");
      if (!isKey && !name.equals("version")) continue;
      String v = line.substring(c + 1).strip();
      if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'') && v.charAt(v.length() - 1) == v.charAt(0)) {
        v = v.substring(1, v.length() - 1);
      }
      if (v.isEmpty()) continue;
      if (isKey && key == null) key = v;
      if (!isKey && version == null) version = v;
    }
    return key != null ? key : version;
  }

  public void read(Reader tsv) throws IOException {
    BufferedReader r = new BufferedReader(tsv, 1 << 16);
    String header = r.readLine();
    if (header == null) throw new IOException("Empty NameUsage.tsv");
    String[] cols = header.split("\t", -1);
    int uni = idx(cols, "col:uninomial", true), gen = idx(cols, "col:genericName", true), rank = idx(cols, "col:rank", true);
    int sp = idx(cols, "col:specificEpithet", true), inf = idx(cols, "col:infraspecificEpithet", true);
    int max = Math.max(Math.max(uni, gen), Math.max(rank, Math.max(sp, inf)));
    String line;
    while ((line = r.readLine()) != null) {
      rows++;
      int[] cut = new int[max + 2];
      // find the field boundaries up to the last column of interest
      int f = 0, pos = 0;
      cut[0] = 0;
      while (f <= max) {
        int t = line.indexOf('\t', pos);
        cut[f + 1] = t < 0 ? line.length() : t;
        f++;
        if (t < 0) break;
        pos = t + 1;
      }
      if (f <= max) {
        // short line: missing trailing columns are empty
        for (int i = f; i <= max; i++) cut[i + 1] = line.length();
      }
      String rk = field(line, cut, rank, f);
      String uninomial = field(line, cut, uni, f);
      String genericName = field(line, cut, gen, f);
      Rank rnk = parseRank(rk);
      if (!genericName.isEmpty() && (rnk == null || !rnk.isSuprageneric())) addGenus(genericName);
      if (!uninomial.isEmpty() && rnk != null) {
        if (rnk.isSuprageneric()) addUninomial(uninomial, rnk);
        else if (rnk == Rank.GENUS) addGenus(uninomial);
      }
      String specific = field(line, cut, sp, f);
      addEpithet(specific);
      addEpithet(field(line, cut, inf, f));
      if (!genericName.isEmpty() && !specific.isEmpty() && (rnk == null || !rnk.isSuprageneric())) addPair(genericName, specific);
    }
  }

  private static String field(String line, int[] cut, int i, int found) {
    if (i >= found) return "";
    int from = i == 0 ? 0 : cut[i] + 1;
    return line.substring(from, cut[i + 1]);
  }

  private static int idx(String[] cols, String name, boolean required) {
    for (int i = 0; i < cols.length; i++) if (cols[i].equals(name)) return i;
    if (required) throw new IllegalArgumentException("NameUsage.tsv has no column " + name);
    return -1;
  }

  static Rank parseRank(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Rank.valueOf(s.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** a single latin word, optionally hyphenated; strips hybrid markers */
  private static String clean(String w) {
    int s = 0;
    while (s < w.length() && (w.charAt(s) == '×' || w.charAt(s) == '+' || w.charAt(s) == 'x' && s + 1 < w.length() && w.charAt(s + 1) == ' ')) s++;
    w = w.substring(s).strip();
    if (w.isEmpty()) return null;
    for (int i = 0; i < w.length(); i++) {
      char c = w.charAt(i);
      if (!Character.isLetter(c) && c != '-') return null;
    }
    return w;
  }

  private void addGenus(String w) {
    w = clean(w);
    if (w == null || !Character.isUpperCase(w.charAt(0))) return;
    long h = ColDictionary.hash(w);
    genera.add(h);
    if (common.contains(w)) ambiguous.add(h);
  }

  private void addUninomial(String w, Rank rank) {
    w = clean(w);
    if (w == null || !Character.isUpperCase(w.charAt(0))) return;
    long h = ColDictionary.hash(w);
    if (common.contains(w)) ambiguous.add(h);
    int[] c = uninomialCounts.get(h);
    int o = rank.ordinal();
    if (c == null) {
      uninomialCounts.put(h, new int[]{o, 1});
      return;
    }
    for (int i = 0; i < c.length; i += 2) {
      if (c[i] == o) {
        c[i + 1]++;
        return;
      }
    }
    int[] n = java.util.Arrays.copyOf(c, c.length + 2);
    n[c.length] = o;
    n[c.length + 1] = 1;
    uninomialCounts.put(h, n);
  }

  private void addEpithet(String w) {
    if (w.isEmpty()) return;
    w = clean(w);
    if (w == null) return;
    epithets.add(ColDictionary.hash(w.toLowerCase(Locale.ROOT)));
  }

  /** Species and infraspecific rows: the genus and the specific epithet. */
  private void addPair(String genus, String epithet) {
    genus = clean(genus);
    epithet = clean(epithet);
    if (genus == null || epithet == null || !Character.isUpperCase(genus.charAt(0))) return;
    pairs.add(ColDictionary.hash(ColDictionary.pairKey(genus, epithet)));
  }

  public ColDictionary build(String releaseKey) {
    if (RANKS.length > 255) throw new IllegalStateException("too many ranks for a byte");
    Long2ByteOpenHashMap ranks = new Long2ByteOpenHashMap(uninomialCounts.size());
    for (var e : uninomialCounts.long2ObjectEntrySet()) {
      int[] c = e.getValue();
      int best = c[0], bestN = c[1];
      for (int i = 2; i < c.length; i += 2) {
        // most frequent rank; on a tie the higher rank (smaller ordinal) wins
        if (c[i + 1] > bestN || c[i + 1] == bestN && c[i] < best) {
          best = c[i];
          bestN = c[i + 1];
        }
      }
      ranks.put(e.getLongKey(), (byte) best);
    }
    genera.trim();
    epithets.trim();
    ambiguous.trim();
    pairs.trim();
    ranks.trim();
    return new ColDictionary(releaseKey, genera, epithets, ranks, RANKS, ambiguous, pairs);
  }
}
