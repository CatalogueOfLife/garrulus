package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.ContextScope;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.gbif.nameparser.api.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the article's own classification ("verbatim classification") and says which names it applies to.
 *
 * <p>Three patterns:
 * <ol>
 *   <li>Chains of higher taxa such as "(Coleoptera: Carabidae)" in the parentheses of TITLE, ABSTRACT and HEADING
 *       blocks, or at the very start of such a block ("Coleoptera: Carabidae. A new ..."). A chain is accepted only if
 *       every element is a known uninomial or genus or has a rank-typical suffix, and not every element is also an
 *       ordinary word. TITLE and ABSTRACT chains are DOCUMENT contexts, HEADING chains SECTION contexts.</li>
 *   <li>A HEADING starting with a rank word and a name ("Family Opheliidae Malmgren, 1867", "Familie Carabidae") is a
 *       SECTION context with that rank; the authorship is ignored.</li>
 *   <li>Treatment headings: the converter turns TaxPub {@code tp:nomenclature} into a plain HEADING and leaves no
 *       marker on the {@link Block}, so they are recognised by heuristic: a HEADING whose first name candidate has
 *       rank genus or lower opens a TREATMENT context with that name. Ordinary section headings that start with such a
 *       name behave the same way, which is harmless: the context ends with the next heading of the same or higher
 *       level anyway.</li>
 * </ol>
 *
 * <p>SECTION and TREATMENT contexts run from their heading to the block before the next HEADING of level less than or
 * equal to theirs ({@code untilBlockId} is that next heading, null if the scope runs to the end). Each name gets the
 * innermost context covering its block, that is the one whose scope starts latest, else the first DOCUMENT context.
 * Context ids are local ("ctx:blockId:charOffset"); the caller renumbers them.
 */
public class ContextExtractor {
  public record Result(List<ClassificationContext> contexts, Map<ParsedCandidate, String> contextOf) {}

  private static final Pattern PAREN_CHAIN = Pattern.compile("\\(([A-Z][a-z]+(?:\\s*[:,;]\\s*[A-Z][a-z]+)+)\\)");
  private static final Pattern INITIAL_CHAIN = Pattern.compile("^([A-Z][a-z]+(?:\\s*:\\s*[A-Z][a-z]+)+)");
  private static final Pattern ELEMENT_SPLIT = Pattern.compile("\\s*[:,;]\\s*");
  private static final Pattern RANK_HEADING = Pattern.compile("^\\s*(\\p{L}[\\p{L}-]*)\\s*[:.]?\\s+(\\p{Lu}\\p{Ll}+)");

  private final ColDictionary dict;

  public ContextExtractor(ColDictionary dict) {
    this.dict = dict;
  }

  /** A context plus the block index range [from, to) it covers; DOCUMENT contexts cover everything. */
  private record Scoped(ClassificationContext ctx, int from, int to) {}

  public Result extract(Document doc, List<ParsedCandidate> names) {
    List<Block> blocks = doc.blocks();
    Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < blocks.size(); i++) index.put(blocks.get(i).id(), i);
    Map<String, List<ParsedCandidate>> byBlock = new HashMap<>();
    for (ParsedCandidate p : names) byBlock.computeIfAbsent(p.c().blockId(), k -> new ArrayList<>()).add(p);

    List<Scoped> scoped = new ArrayList<>();
    for (int bi = 0; bi < blocks.size(); bi++) {
      Block b = blocks.get(bi);
      BlockType t = b.type();
      if (t == BlockType.TITLE || t == BlockType.ABSTRACT || t == BlockType.HEADING) {
        ContextScope scope = t == BlockType.HEADING ? ContextScope.SECTION : ContextScope.DOCUMENT;
        for (Pattern p : List.of(PAREN_CHAIN, INITIAL_CHAIN)) {
          Matcher m = p.matcher(b.text());
          while (m.find()) {
            List<RankedName> ranks = acceptChain(m.group(1));
            if (ranks != null) scoped.add(make(blocks, bi, b, scope, m.start(1), m.group(1), ranks));
          }
        }
      }
      if (t == BlockType.HEADING) {
        Scoped rank = rankHeading(blocks, bi, b);
        if (rank != null) scoped.add(rank);
        else {
          Scoped treatment = treatment(blocks, bi, b, byBlock.getOrDefault(b.id(), List.of()));
          if (treatment != null) scoped.add(treatment);
        }
      }
    }

    Scoped document = scoped.stream().filter(s -> s.ctx().scope() == ContextScope.DOCUMENT).findFirst().orElse(null);
    Map<ParsedCandidate, String> contextOf = new LinkedHashMap<>();
    for (ParsedCandidate p : names) {
      Integer bi = index.get(p.c().blockId());
      if (bi == null) continue;
      Scoped best = null;
      for (Scoped s : scoped) {
        if (s.ctx().scope() == ContextScope.DOCUMENT || bi < s.from() || bi >= s.to()) continue;
        if (best == null || s.from() >= best.from()) best = s; // latest start wins, a later one on a tie
      }
      if (best == null) best = document;
      if (best != null) contextOf.put(p, best.ctx().id());
    }
    return new Result(scoped.stream().map(Scoped::ctx).toList(), contextOf);
  }

  private Scoped make(List<Block> blocks, int bi, Block b, ContextScope scope, int charOffset, String verbatim,
                      List<RankedName> ranks) {
    int to = blocks.size();
    if (scope != ContextScope.DOCUMENT) {
      for (int i = bi + 1; i < blocks.size(); i++) {
        Block n = blocks.get(i);
        if (n.type() == BlockType.HEADING && n.level() <= b.level()) {
          to = i;
          break;
        }
      }
    }
    String until = scope != ContextScope.DOCUMENT && to < blocks.size() ? blocks.get(to).id() : null;
    String id = "ctx:" + b.id() + ":" + charOffset;
    return new Scoped(new ClassificationContext(id, verbatim, ranks, scope, b.id(), until), bi, scope == ContextScope.DOCUMENT ? blocks.size() : to);
  }

  /** The ranked elements of an accepted chain, or null if the chain is rejected. */
  private List<RankedName> acceptChain(String chain) {
    String[] parts = ELEMENT_SPLIT.split(chain);
    List<RankedName> out = new ArrayList<>();
    boolean allAmbiguous = true;
    for (String w : parts) {
      Optional<Rank> suffix = SuffixRules.rankFor(w, null);
      if (!(dict.isUninomial(w) || dict.isGenus(w) || suffix.isPresent())) return null;
      Rank r = dict.uninomialRank(w).orElseGet(() -> suffix.orElse(dict.isGenus(w) ? Rank.GENUS : Rank.UNRANKED));
      out.add(new RankedName(r, w));
      if (!dict.isAmbiguousWord(w)) allAmbiguous = false;
    }
    return allAmbiguous ? null : out;
  }

  private Scoped rankHeading(List<Block> blocks, int bi, Block b) {
    Matcher m = RANK_HEADING.matcher(b.text());
    if (!m.find()) return null;
    Optional<Rank> rank = RankWords.rank(m.group(1));
    String name = m.group(2);
    if (rank.isEmpty()) return null;
    if (!(dict.isUninomial(name) || dict.isGenus(name) || SuffixRules.rankFor(name, null).isPresent())) return null;
    return make(blocks, bi, b, ContextScope.SECTION, 0, b.text().strip(), List.of(new RankedName(rank.get(), name)));
  }

  private Scoped treatment(List<Block> blocks, int bi, Block b, List<ParsedCandidate> inBlock) {
    ParsedCandidate first = inBlock.stream()
        .min(Comparator.comparingInt((ParsedCandidate p) -> p.c().start()).thenComparing(p -> -p.c().end())).orElse(null);
    if (first == null || first.rank() == null) return null;
    Rank r = first.rank();
    if (r == Rank.UNRANKED || r == Rank.OTHER || r.isSuprageneric()) return null;
    String verbatim = CodePoints.slice(b.text(), first.c().start(), first.c().end());
    Scoped s = make(blocks, bi, b, ContextScope.TREATMENT, b.text().offsetByCodePoints(0, first.c().start()), verbatim,
        List.of(new RankedName(r, first.canonical())));
    return s;
  }
}
