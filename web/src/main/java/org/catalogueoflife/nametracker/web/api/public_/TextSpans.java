package org.catalogueoflife.nametracker.web.api.public_;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.catalogueoflife.nametracker.finder.api.ActPatterns;
import org.catalogueoflife.nametracker.finder.api.ActPatterns.ActMatch;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.jspecify.annotations.Nullable;

/**
 * Places stored mentions in a text shown to the reader that may differ from the text the finder saw: the title in
 * {@code work.title}, the abstract as served, or a snippet.
 */
final class TextSpans {

  private TextSpans() {}

  /** A row placed at char offsets [start, end) of the text. */
  private record Placed(TitleMentionRow row, int start, int end) {}

  /**
   * Rows must be ordered by block, then stored startCp, then longest first. Stored offsets (per block) only order the
   * rows and detect overlaps within their block; the spans are found by searching the verbatim as a whole word in the
   * text, left to right across the blocks. A second pass gives each row the first pass did not find after its cursor
   * the first occurrence that no span covers yet: the text may hold only some of the finder's blocks, in another order
   * (a full-text document's simple summary and abstract against the abstract served). In that pass rows with an act
   * come first and take the free occurrence nearest before a marker of their act, as {@link ActSpans} pairs them, else
   * the first free one. Rows not found are dropped. The spans are code-point offsets into the text, in text order.
   */
  static List<MentionSpan> locate(@Nullable String text, List<TitleMentionRow> rows) {
    if (text == null || rows.isEmpty()) {
      return List.of();
    }
    List<Placed> placed = new ArrayList<>();
    List<TitleMentionRow> missed = new ArrayList<>();
    int cursor = 0;
    int lastEnd = Integer.MIN_VALUE;
    String block = null;
    for (TitleMentionRow r : rows) {
      if (!Objects.equals(r.blockId(), block)) {
        block = r.blockId();
        lastEnd = Integer.MIN_VALUE;
      }
      if (r.startCp() < lastEnd || r.verbatim().isEmpty()) {
        continue;
      }
      int at = find(text, r.verbatim(), cursor);
      if (at < 0) {
        missed.add(r);
        continue;
      }
      placed.add(new Placed(r, at, at + r.verbatim().length()));
      cursor = at + r.verbatim().length();
      lastEnd = r.endCp();
    }
    if (!missed.isEmpty()) {
      List<ActMatch> markers = missed.stream().anyMatch(r -> r.actType() != null) ? ActPatterns.find(text) : List.of();
      missed.sort(Comparator.comparing(r -> r.actType() == null)); // stable: acts first, each in row order
      for (TitleMentionRow r : missed) {
        int at = free(text, r, placed, markers);
        if (at >= 0) {
          placed.add(new Placed(r, at, at + r.verbatim().length()));
        }
      }
      placed.sort(Comparator.comparingInt(Placed::start));
    }
    return placed.stream().map(p -> new MentionSpan(p.row().id(), p.row().nameStringId(),
        text.codePointCount(0, p.start()), text.codePointCount(0, p.end()), p.row().verbatim(), p.row().actType()))
        .toList();
  }

  /**
   * The whole-word occurrence of the row's verbatim that no placed span overlaps: for a row with an act the one nearest
   * before a marker of that act (at most {@link ActSpans#MAX_DISTANCE} code points), else the first; -1 if none.
   */
  private static int free(String text, TitleMentionRow r, List<Placed> placed, List<ActMatch> markers) {
    String word = r.verbatim();
    int first = -1;
    int best = -1;
    int bestDistance = Integer.MAX_VALUE;
    for (int at = find(text, word, 0); at >= 0; at = find(text, word, at + 1)) {
      int start = at;
      int end = at + word.length();
      if (placed.stream().anyMatch(p -> p.start() < end && start < p.end())) {
        continue;
      }
      if (first < 0) {
        first = at;
      }
      if (r.actType() != null) {
        int endCp = text.codePointCount(0, end);
        for (ActMatch m : markers) {
          int d = m.start() - endCp;
          if (m.type().name().equals(r.actType()) && d >= 0 && d <= ActSpans.MAX_DISTANCE && d < bestDistance) {
            best = at;
            bestDistance = d;
          }
        }
      }
    }
    return best >= 0 ? best : first;
  }

  /** The first index from {@code from} where {@code word} stands as a whole word: no letter or digit on either side. */
  private static int find(String text, String word, int from) {
    for (int at = text.indexOf(word, from); at >= 0; at = text.indexOf(word, at + 1)) {
      int end = at + word.length();
      boolean before = at == 0 || !Character.isLetterOrDigit(text.codePointBefore(at));
      boolean after = end == text.length() || !Character.isLetterOrDigit(text.codePointAt(end));
      if (before && after) {
        return at;
      }
    }
    return -1;
  }
}
