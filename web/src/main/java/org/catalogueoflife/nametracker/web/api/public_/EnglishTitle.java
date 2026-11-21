package org.catalogueoflife.nametracker.web.api.public_;

import org.jspecify.annotations.Nullable;

/** The line a digest or feed shows after a title that is not English: the English one, labelled by where it came from. */
public final class EnglishTitle {

  private EnglishTitle() {}

  /** "English title: ..." for a parallel title, "Machine translation: ..." for a machine one, null without one. */
  public static @Nullable String line(@Nullable String titleEn, @Nullable String kind) {
    if (titleEn == null || titleEn.isBlank()) {
      return null;
    }
    return ("MACHINE".equals(kind) ? "Machine translation: " : "English title: ") + titleEn.strip();
  }
}
