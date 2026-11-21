package org.catalogueoflife.nametracker.web.api.public_;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.lang.WorkText;
import org.catalogueoflife.nametracker.web.api.public_.dto.TextVersion;

/** The versions of a work's title or abstract from {@code work_text}: original first, then parallel, then machine. */
final class TextVersions {

  private static final Map<String, Integer> ORDER = Map.of("ORIGINAL", 0, "PARALLEL", 1, "MACHINE", 2);

  private TextVersions() {}

  static List<TextVersion> of(List<WorkText> rows, String field) {
    return rows.stream().filter(t -> t.field().equals(field))
        .sorted(Comparator.comparing((WorkText t) -> ORDER.getOrDefault(t.kind(), 9)).thenComparing(WorkText::lang))
        .map(t -> new TextVersion(t.lang(), t.kind(), t.text(), t.model())).toList();
  }
}
