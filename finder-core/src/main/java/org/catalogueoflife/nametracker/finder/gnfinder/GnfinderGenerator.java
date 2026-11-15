package org.catalogueoflife.nametracker.finder.gnfinder;

import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.catalogueoflife.nametracker.finder.pipeline.Candidate;
import org.catalogueoflife.nametracker.finder.pipeline.CandidateGenerator;
import org.catalogueoflife.nametracker.finder.pipeline.GenerationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Candidates from gnfinder. One call per chunk of {@link FlatText#chunks(int)}. gnfinder spans include surrounding
 * punctuation ("(Coleoptera:"), so they are trimmed to the letters of the name, keeping the dot of an abbreviated genus.
 * Spans that cross a block boundary are dropped, and so are garbled names (see {@link #garbled}).
 */
public class GnfinderGenerator implements CandidateGenerator {
  private static final char BOM = '\uFEFF';

  private final GnfinderClient client;
  private final int maxChunkCodePoints;
  private final AtomicInteger misaligned = new AtomicInteger();

  public GnfinderGenerator(GnfinderClient client, int maxChunkCodePoints) {
    this.client = client;
    this.maxChunkCodePoints = maxChunkCodePoints;
  }

  /** How many gnfinder names were dropped because their offsets did not point at their verbatim text. Should stay 0. */
  public int misaligned() {
    return misaligned.get();
  }

  @Override
  public Generator id() {
    return Generator.GNFINDER;
  }

  @Override
  public List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx) {
    List<Candidate> out = new ArrayList<>();
    for (FlatText chunk : flat.chunks(maxChunkCodePoints)) {
      String text = chunk.text();
      if (text.isEmpty()) continue;
      int[] cps = text.codePoints().toArray(); // once per chunk: slicing the string would scan it again per name
      int len = cps.length;
      // gnfinder drops a leading byte order mark and then counts offsets from the next character. The converters remove
      // U+FEFF (TextNormalizer), so this only guards documents that reach the finder without them, e.g. from API clients.
      int shift = text.charAt(0) == BOM ? 1 : 0;
      for (GnName n : client.find(shift == 0 ? text : text.substring(1)).names()) {
        int ns = n.start() + shift;
        int ne = n.end() + shift;
        if (n.start() < 0 || ne > len || n.start() >= n.end()) continue;
        String raw = new String(cps, ns, ne - ns);
        // gnfinder prints line breaks inside a verbatim as the symbol \u2424 (not a real character of the text)
        if (!raw.equals(n.verbatim().replace('\u2424', '\n'))) {
          misaligned.incrementAndGet(); // never store a name that is not an exact span of the source text
          continue;
        }
        int[] t = trimOffsets(raw);
        if (t[0] >= t[1]) continue;
        int start = ns + t[0];
        int end = ns + t[1];
        FlatText.Position s;
        FlatText.Position e;
        try {
          s = chunk.toBlock(start);
          e = chunk.toBlock(end);
        } catch (IllegalArgumentException ex) {
          continue; // starts or ends inside a block separator
        }
        if (!s.blockId().equals(e.blockId())) continue;
        String verbatim = new String(cps, start, end - start);
        if (garbled(n.name(), verbatim)) continue;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("oddsLog10", n.oddsLog10());
        f.put("cardinality", n.cardinality());
        f.put("annotationNomenType", n.annotationNomenType());
        if (n.annotationNomen() != null) f.put("annotationNomen", n.annotationNomen());
        out.add(new Candidate(s.blockId(), s.offset(), e.offset(), verbatim, n.name(), Generator.GNFINDER, f));
      }
    }
    return out;
  }

  /**
   * gnfinder replaces characters it cannot fit into a name by U+FFFD ("Diagnosis\uFFFDscutellaria holguinensis" from
   * "Diagnosis:Scutellaria holguinensis"); a name with one, or a trimmed span with ":" or ";" inside (always between
   * letters, the span starts and ends with them), is not a name. Dropping it lets the italic or dictionary candidate win.
   */
  static boolean garbled(String name, String trimmedVerbatim) {
    return name.indexOf('\uFFFD') >= 0 || trimmedVerbatim.indexOf(':') >= 0 || trimmedVerbatim.indexOf(';') >= 0;
  }

  /** The tight name span of a gnfinder verbatim. */
  public static String trim(String verbatim) {
    int[] r = trimOffsets(verbatim);
    return CodePoints.slice(verbatim, r[0], r[1]);
  }

  /**
   * Code-point range [start, end) of the name inside the verbatim: leading and trailing non-letters are cut, except a
   * trailing dot that follows a single upper case letter (an abbreviated genus such as "C.") and a closing parenthesis
   * whose opening one is inside the span ("Crematogaster (Oxygyne)", but "Carabidae)" loses it).
   */
  public static int[] trimOffsets(String verbatim) {
    int[] cps = verbatim.codePoints().toArray();
    int s = 0;
    int e = cps.length;
    while (s < e && !Character.isLetter(cps[s])) s++;
    int lastLetter = e;
    while (lastLetter > s && !Character.isLetter(cps[lastLetter - 1])) lastLetter--;
    e = lastLetter;
    if (e > s && e < cps.length && cps[e] == '.' && Character.isUpperCase(cps[e - 1])
        && (e - 1 == s || !Character.isLetter(cps[e - 2]))) {
      e++;
    }
    if (e > s && e < cps.length && cps[e] == ')' && openParentheses(cps, s, e) > 0) e++;
    if (e < s) e = s;
    return new int[] {s, e};
  }

  /** Opening minus closing parentheses in cps[from, to). */
  private static int openParentheses(int[] cps, int from, int to) {
    int open = 0;
    for (int i = from; i < to; i++) {
      if (cps[i] == '(') open++;
      else if (cps[i] == ')') open--;
    }
    return open;
  }
}
