package org.catalogueoflife.nametracker.finder.dict;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.gbif.nameparser.api.Rank;

import java.util.Locale;
import java.util.Optional;

/**
 * Word lookups built from the Catalogue of Life XR. Words are stored as 64-bit FNV-1a hashes only.
 * <p>
 * Case: genus and uninomial lookups are case-sensitive and expect the capitalised form ("Carabus"); epithet
 * lookups are lower-cased ("nigrus").
 * <p>
 * Pairs: genus plus specific epithet of every species and infraspecific name ("Carabus nigrus"), so a binomial can be
 * told from a known genus followed by any known epithet ("Carabus ater" when only "Procarabus ater" exists).
 * <p>
 * Hash collisions: with n stored hashes a word that is not in the dictionary is reported as present with a
 * probability of about n / 2^64. For the full XR (a few million hashes per category at most) this is ~1e-13 per
 * lookup, so a collision is not expected to ever occur in practice and is accepted.
 */
public final class ColDictionary {
  private final String releaseKey;
  private final LongOpenHashSet genera;
  private final LongOpenHashSet epithets;
  private final Long2ByteOpenHashMap uninomialRanks;
  private final Rank[] rankTable;
  private final LongOpenHashSet ambiguous;
  private final LongOpenHashSet pairs;

  /**
   * @param rankTable maps the byte values stored in {@code uninomialRanks} to ranks
   * @param pairs hashes of {@link #pairKey(String, String)}
   */
  public ColDictionary(String releaseKey, LongOpenHashSet genera, LongOpenHashSet epithets, Long2ByteOpenHashMap uninomialRanks,
                       Rank[] rankTable, LongOpenHashSet ambiguous, LongOpenHashSet pairs) {
    this.releaseKey = releaseKey;
    this.genera = genera;
    this.epithets = epithets;
    this.uninomialRanks = uninomialRanks;
    this.rankTable = rankTable;
    this.ambiguous = ambiguous;
    this.pairs = pairs;
  }

  /** The string hashed for a genus-epithet pair: the genus as given, a space, the epithet lower-cased. */
  static String pairKey(String genus, String epithet) {
    return genus + ' ' + epithet.toLowerCase(Locale.ROOT);
  }

  /** 64-bit FNV-1a over the UTF-16 code units, two bytes each. */
  public static long hash(CharSequence s) {
    long h = 0xcbf29ce484222325L;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      h = (h ^ (c & 0xff)) * 0x100000001b3L;
      h = (h ^ (c >>> 8)) * 0x100000001b3L;
    }
    return h;
  }

  public boolean isGenus(String w) {
    return w != null && genera.contains(hash(w));
  }

  public boolean isEpithet(String w) {
    return w != null && epithets.contains(hash(w.toLowerCase(Locale.ROOT)));
  }

  public boolean isUninomial(String w) {
    return w != null && uninomialRanks.containsKey(hash(w));
  }

  public Optional<Rank> uninomialRank(String w) {
    if (w == null) return Optional.empty();
    long h = hash(w);
    if (!uninomialRanks.containsKey(h)) return Optional.empty();
    return Optional.of(rankTable[uninomialRanks.get(h) & 0xff]);
  }

  /** "Carabus nigrus" is a species (or the species part of an infraspecific name) in COL. The genus is case-sensitive. */
  public boolean isBinomial(String genus, String epithet) {
    return genus != null && epithet != null && pairs.contains(hash(pairKey(genus, epithet)));
  }

  /** A genus or uninomial that is also an ordinary word, e.g. "Major". Expects the capitalised form. */
  public boolean isAmbiguousWord(String w) {
    return w != null && ambiguous.contains(hash(w));
  }

  /** @param lower any word, tried as epithet (as given, lower-cased) and as capitalised genus or uninomial */
  public boolean isKnownWord(String lower) {
    if (lower == null || lower.isEmpty()) return false;
    String lc = lower.toLowerCase(Locale.ROOT);
    if (epithets.contains(hash(lc))) return true;
    int cp = lc.codePointAt(0);
    String cap = new String(Character.toChars(Character.toUpperCase(cp))) + lc.substring(Character.charCount(cp));
    long h = hash(cap);
    return genera.contains(h) || uninomialRanks.containsKey(h);
  }

  public String releaseKey() {
    return releaseKey;
  }

  public int genusCount() {
    return genera.size();
  }

  public int epithetCount() {
    return epithets.size();
  }

  public int uninomialCount() {
    return uninomialRanks.size();
  }

  public int ambiguousCount() {
    return ambiguous.size();
  }

  public int pairCount() {
    return pairs.size();
  }

  public String stats() {
    return "release " + releaseKey + ": genera " + genusCount() + ", epithets " + epithetCount() + ", uninomials "
        + uninomialCount() + ", ambiguous " + ambiguousCount() + ", genus-epithet pairs " + pairCount();
  }

  LongOpenHashSet genera() { return genera; }
  LongOpenHashSet epithets() { return epithets; }
  Long2ByteOpenHashMap uninomialRanks() { return uninomialRanks; }
  Rank[] rankTable() { return rankTable; }
  LongOpenHashSet ambiguous() { return ambiguous; }
  LongOpenHashSet pairs() { return pairs; }
}
