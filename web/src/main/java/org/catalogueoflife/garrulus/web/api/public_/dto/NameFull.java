package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record NameFull(long id, String name, String kind, @Nullable String rank, @Nullable NameMatchDto match,
    List<YearWorks> timeline, @Nullable FirstAppearance firstAppearance) {

  public record YearWorks(int year, long works) {}

  public record FirstAppearance(long workId, @Nullable String title, @Nullable LocalDate publicationDate) {}
}
