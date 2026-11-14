package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.catalogueoflife.nametracker.finder.dict.DictHit;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes the {@link CandidateFeatures} of parsed candidates. An instance holds what is shared by all candidates of one
 * document (block lookup, name density); it is immutable, so create one per document and call.
 *
 * <p>Feature definitions:
 * <ul>
 *   <li>{@code italic}: the ITALIC generator saw the span, or its first code point lies inside an ITALIC style span of the
 *       block. The second rule keeps the feature when the markup generator is disabled or the span is not name shaped.</li>
 *   <li>{@code taxonMarkup}: the TAXON_MARKUP generator saw the span (publisher tagged).</li>
 *   <li>{@code rankWordBefore}: the word right before the span, separated from it by whitespace only, is a rank word of
 *       {@link RankWords} ("genus Carabus", "Familie Carabidae"; "families Rosaceae" is not).</li>
 *   <li>{@code gnfinderOdds}: the {@code oddsLog10} candidate feature (the maximum when several gnfinder names merged).</li>
 *   <li>{@code dictHit}: the {@code dictHit} candidate feature of the dictionary generator, NONE when it did not
 *       contribute. For a merged span it is the hit of the longest dictionary candidate inside it.</li>
 *   <li>{@code suffixRule}: the SUFFIX generator saw the span.</li>
 *   <li>{@code ambiguousWord}: the candidate is a single word (kind UNINOMIAL) and the dictionary flagged it as a genus or
 *       uninomial that is also an ordinary word ({@code ambiguousWord} candidate feature). A multi-word name is never an
 *       ordinary word, even when its genus is ("Ophelia yangi": "Ophelia" is in the common word lists); the epithet
 *       disambiguates it, and the genus ambiguity still shows in {@code dictHit} AMBIGUOUS_WORD.</li>
 *   <li>{@code docNameDensity}: names per 100 word tokens of the whole document. "Names" is the {@code namesInDoc} the
 *       caller passes; the pipeline passes the number of parsed candidates, because the accepted names are not known
 *       before judging. Tokens are those of {@link Tokenizer} over all blocks (letter runs; numbers and punctuation do not
 *       count). 0 for a document without tokens.</li>
 *   <li>{@code blockType}: the type of the candidate's block.</li>
 *   <li>{@code sentenceInitial}: before the span there is only whitespace and opening brackets or quotes back to either
 *       the block start or a '.', '!' or '?' that is followed by at least one whitespace. So "x. Carabus" and
 *       "x. (Carabus" are sentence initial, "i.e.Carabus" is not. Abbreviation dots ("sp. nov. Carabus") count as a sentence
 *       end too; telling them apart is left to the weights.</li>
 *   <li>{@code authorshipFollows}: the authorship attacher found an authorship after the span.</li>
 *   <li>{@code generatorCount}: the number of distinct generators that saw the span or part of it; the markup generator
 *       counts once per kind (ITALIC, TAXON_MARKUP).</li>
 * </ul>
 */
public final class FeatureExtractor {
  private static final Set<Integer> OPENERS = Set.of((int) '(', (int) '[', (int) '{', (int) '"', (int) '\'', (int) '“',
      (int) '‘', (int) '«', (int) '„');

  private final Map<String, Block> blocks;
  private final double density;

  /** @param namesInDoc the number of names in the document, used for {@code docNameDensity} */
  public FeatureExtractor(Document doc, int namesInDoc) {
    Map<String, Block> byId = new HashMap<>();
    long tokens = 0;
    for (Block b : doc.blocks()) {
      byId.putIfAbsent(b.id(), b);
      tokens += Tokenizer.tokens(b.text()).size();
    }
    this.blocks = byId;
    this.density = tokens == 0 ? 0 : 100.0 * namesInDoc / tokens;
  }

  /** The features of one candidate; convenient for single calls, the pipeline uses one instance per document. */
  public static CandidateFeatures features(ParsedCandidate c, Document doc, int namesInDoc) {
    return new FeatureExtractor(doc, namesInDoc).features(c);
  }

  /** @throws IllegalArgumentException if the candidate's block is not part of the document */
  public CandidateFeatures features(ParsedCandidate p) {
    MergedCandidate c = p.c();
    Block b = blocks.get(c.blockId());
    if (b == null) throw new IllegalArgumentException("Candidate refers to unknown block " + c.blockId());
    int[] cps = b.text().codePoints().toArray();
    Map<String, Object> f = c.features();
    Set<Generator> gens = c.generators();
    Double odds = f.get("oddsLog10") instanceof Number n ? n.doubleValue() : null;
    DictHit hit = f.get("dictHit") instanceof DictHit h ? h : DictHit.NONE;
    return new CandidateFeatures(
        gens.contains(Generator.ITALIC) || inItalic(b, c.start()),
        gens.contains(Generator.TAXON_MARKUP),
        rankWordBefore(cps, c.start()),
        odds,
        hit,
        gens.contains(Generator.SUFFIX),
        p.kind() == NameKind.UNINOMIAL && Boolean.TRUE.equals(f.get("ambiguousWord")),
        density,
        b.type(),
        sentenceInitial(cps, c.start()),
        c.authorship() != null,
        gens.size());
  }

  private static boolean inItalic(Block b, int cp) {
    for (StyleSpan s : b.styles()) {
      if (s.style() == Style.ITALIC && s.start() <= cp && cp < s.end()) return true;
    }
    return false;
  }

  static boolean rankWordBefore(int[] cps, int start) {
    int i = Math.min(start, cps.length) - 1;
    int ws = 0;
    while (i >= 0 && isSpace(cps[i])) {
      i--;
      ws++;
    }
    if (ws == 0 || i < 0 || !Character.isLetter(cps[i])) return false;
    int end = i + 1;
    while (i >= 0 && (Character.isLetter(cps[i]) || cps[i] == '-')) i--;
    return RankWords.rank(new String(cps, i + 1, end - i - 1)).isPresent();
  }

  static boolean sentenceInitial(int[] cps, int start) {
    int i = Math.min(start, cps.length) - 1;
    boolean space = false;
    while (i >= 0) {
      int c = cps[i];
      if (isSpace(c)) space = true;
      else if (!OPENERS.contains(c)) break;
      i--;
    }
    if (i < 0) return true;
    int c = cps[i];
    return space && (c == '.' || c == '!' || c == '?');
  }

  private static boolean isSpace(int c) {
    return Character.isWhitespace(c) || Character.isSpaceChar(c);
  }
}
