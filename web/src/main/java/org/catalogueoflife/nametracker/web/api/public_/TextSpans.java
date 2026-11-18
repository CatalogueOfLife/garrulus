package org.catalogueoflife.nametracker.web.api.public_;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.jspecify.annotations.Nullable;

/**
 * Places stored mentions in a text shown to the reader that may differ from the text the finder saw: the title in
 * {@code work.title}, the abstract as served, or a snippet.
 */
final class TextSpans {

  private TextSpans() {}

  /**
   * Rows must be ordered by block, then stored startCp, then longest first. Stored offsets (per block) only order the
   * rows and detect overlaps within their block; the spans are found by searching the verbatim as a whole word in the
   * text, left to right across the blocks, and are code-point offsets into it. A row whose verbatim is not found is
   * dropped.
   */
  static List<MentionSpan> locate(@Nullable String text, List<TitleMentionRow> rows) {
    if (text == null || rows.isEmpty()) {
      return List.of();
    }
    List<MentionSpan> out = new ArrayList<>();
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
        continue;
      }
      int end = at + r.verbatim().length();
      out.add(new MentionSpan(r.id(), r.nameStringId(), text.codePointCount(0, at), text.codePointCount(0, end),
          r.verbatim(), r.actType()));
      cursor = end;
      lastEnd = r.endCp();
    }
    return out;
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
