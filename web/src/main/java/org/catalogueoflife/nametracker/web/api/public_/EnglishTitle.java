package org.catalogueoflife.nametracker.web.api.public_;

import org.jspecify.annotations.Nullable;

/** The line a digest or feed shows after a title that is not English: the English one, labelled by where it came from. */
public final class EnglishTitle {

  private EnglishTitle() {}

  /** No line for an English title, whatever {@code titleEn} holds. "English title: ..." for a parallel title, "Machine translation: ..." for a machine one, null without one. */
  public static @Nullable String line(@Nullable String titleLang, @Nullable String language, @Nullable String titleEn,
      @Nullable String kind) {
    String own = titleLang != null ? titleLang : language;
    if ("en".equals(own) || titleEn == null || titleEn.isBlank()) {
      return null;
    }
    return ("MACHINE".equals(kind) ? "Machine translation: " : "English title: ") + titleEn.strip();
  }
}
