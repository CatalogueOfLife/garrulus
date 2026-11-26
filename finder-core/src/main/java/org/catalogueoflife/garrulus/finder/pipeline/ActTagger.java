package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.ActPatterns;

import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.CodePoints;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.api.ActPatterns.ActMatch;
import org.gbif.nameparser.api.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches nomenclatural act markers to the nearest preceding name of a fitting kind in the same block. A marker that
 * is a whole HEADING block ("sp. nov.") attaches to the last fitting name of the immediately preceding HEADING block.
 * Each name carries at most one act, the first marker attached; later markers that find only taken names are unattached.
 * Markers in REFERENCE blocks are skipped (neither attached nor counted as unattached): a cited title that says "sp. nov."
 * reports an act of another paper.
 */
public class ActTagger {
  public static final int DEFAULT_MAX_DISTANCE = 40;

  /** Acts per candidate plus the number of markers that found no name (the actsUnattached diagnostic). */
  public record Result(Map<ParsedCandidate, ActMatch> acts, int actsUnattached) {}

  private final int maxDistance;

  public ActTagger() {
    this(DEFAULT_MAX_DISTANCE);
  }

  /** @param maxDistance maximum code points between the end of the name and the start of the marker */
  public ActTagger(int maxDistance) {
    this.maxDistance = maxDistance;
  }

  public Map<ParsedCandidate, ActMatch> tag(List<ParsedCandidate> doc, Document document) {
    return tagWithDiagnostics(doc, document).acts();
  }

  public Result tagWithDiagnostics(List<ParsedCandidate> doc, Document document) {
    Map<ParsedCandidate, ActMatch> out = new LinkedHashMap<>();
    Map<String, List<ParsedCandidate>> byBlock = new HashMap<>();
    for (ParsedCandidate p : doc) byBlock.computeIfAbsent(p.c().blockId(), k -> new ArrayList<>()).add(p);
    byBlock.values().forEach(l -> l.sort(Comparator.comparingInt((ParsedCandidate p) -> p.c().start()).thenComparingInt(p -> p.c().end())));

    int unattached = 0;
    List<Block> blocks = document.blocks();
    for (int bi = 0; bi < blocks.size(); bi++) {
      Block block = blocks.get(bi);
      if (block.type() == BlockType.REFERENCE) continue;
      for (ActMatch m : ActPatterns.find(block.text())) {
        ParsedCandidate target = null;
        if (block.type() == BlockType.HEADING && isWholeMarker(block.text(), m)) {
          if (bi > 0 && blocks.get(bi - 1).type() == BlockType.HEADING) {
            List<ParsedCandidate> prev = byBlock.getOrDefault(blocks.get(bi - 1).id(), List.of());
            for (int i = prev.size() - 1; i >= 0; i--) {
              ParsedCandidate p = prev.get(i);
              if (!fits(m.type(), p)) continue;
              if (!out.containsKey(p)) target = p; // the nearest fitting name, if free; no fallback to an earlier one
              break;
            }
          }
        } else {
          List<ParsedCandidate> same = byBlock.getOrDefault(block.id(), List.of());
          ParsedCandidate best = null;
          for (ParsedCandidate p : same) {
            if (p.c().end() > m.start() || m.start() - p.c().end() > maxDistance) continue;
            if (!fits(m.type(), p)) continue;
            if (best == null || p.c().end() > best.c().end()) best = p;
          }
          if (best != null && !out.containsKey(best)) target = best; // nearest fitting name already taken: unattached
        }
        if (target == null) unattached++;
        else out.put(target, m);
      }
    }
    return new Result(out, unattached);
  }

  /** The block text consists of the marker plus punctuation and whitespace only. */
  private static boolean isWholeMarker(String text, ActMatch m) {
    String rest = CodePoints.slice(text, 0, m.start()) + CodePoints.slice(text, m.end(), CodePoints.length(text));
    return rest.codePoints().noneMatch(Character::isLetterOrDigit);
  }

  static boolean fits(ActType type, ParsedCandidate p) {
    NameKind k = p.kind();
    Rank r = p.rank();
    return switch (type) {
      case SP_NOV -> k == NameKind.BINOMIAL || k == NameKind.ABBREVIATED;
      case SSP_NOV, VAR_NOV -> k == NameKind.TRINOMIAL;
      case GEN_NOV, SUBGEN_NOV ->
          (k == NameKind.UNINOMIAL || k == NameKind.INFRAGENERIC) && r != null && (r == Rank.GENUS || r.isInfragenericStrictly());
      case FAM_NOV -> k == NameKind.UNINOMIAL && r != null && r.isFamilyGroup();
      // above the family group; the parser often has no rank for such names ("Hemimastigophora")
      case HIGHER_NOV -> k == NameKind.UNINOMIAL && (r == null || r == Rank.UNRANKED || (r.isSuprageneric() && !r.isFamilyGroup()));
      default -> true;
    };
  }
}
