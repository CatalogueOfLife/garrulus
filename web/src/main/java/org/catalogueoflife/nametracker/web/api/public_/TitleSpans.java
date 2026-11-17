package org.catalogueoflife.nametracker.web.api.public_;

import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.jspecify.annotations.Nullable;

/** Places stored title mentions in {@code work.title}, which may differ from the text the finder saw. */
final class TitleSpans {

  private TitleSpans() {}

  /**
   * Rows must be ordered by stored startCp, then longest first. Stored offsets only order the rows and detect overlaps;
   * the spans are found by searching the verbatim in the title, left to right, and are code-point offsets into it.
   */
  static List<MentionSpan> locate(@Nullable String title, List<TitleMentionRow> rows) {
    if (title == null || rows.isEmpty()) {
      return List.of();
    }
    List<MentionSpan> out = new ArrayList<>();
    int cursor = 0;
    int lastEnd = Integer.MIN_VALUE;
    for (TitleMentionRow r : rows) {
      if (r.startCp() < lastEnd) {
        continue;
      }
      int at = title.indexOf(r.verbatim(), cursor);
      if (at < 0) {
        continue;
      }
      int end = at + r.verbatim().length();
      out.add(new MentionSpan(r.id(), r.nameStringId(), title.codePointCount(0, at), title.codePointCount(0, end),
          r.verbatim(), r.actType()));
      cursor = end;
      lastEnd = r.endCp();
    }
    return out;
  }
}
