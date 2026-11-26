package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * authors holds at most 3 names, mainNames at most 5; titleMentions are code-point offsets into title. {@code titleEn}
 * is the English title of a work whose title is not English, {@code titleEnKind} says PARALLEL or MACHINE.
 */
public record WorkSummary(long id, @Nullable String doi, @Nullable String title, @Nullable Integer year,
    @Nullable LocalDate publicationDate, @Nullable SourceRef source, List<String> authors, int authorCount,
    @Nullable String textLevel, boolean hasActs, List<NameRef> mainNames,
    @Nullable String volume, @Nullable String issue, @Nullable String pages,
    List<MentionSpan> titleMentions, @Nullable String language, @Nullable String titleLang, @Nullable String titleEn,
    @Nullable String titleEnKind) {

  /** Without language data. */
  public WorkSummary(long id, @Nullable String doi, @Nullable String title, @Nullable Integer year,
      @Nullable LocalDate publicationDate, @Nullable SourceRef source, List<String> authors, int authorCount,
      @Nullable String textLevel, boolean hasActs, List<NameRef> mainNames, @Nullable String volume,
      @Nullable String issue, @Nullable String pages, List<MentionSpan> titleMentions) {
    this(id, doi, title, year, publicationDate, source, authors, authorCount, textLevel, hasActs, mainNames, volume,
        issue, pages, titleMentions, null, null, null, null);
  }
}
