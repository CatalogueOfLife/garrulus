package org.catalogueoflife.nametracker.indexer.hits;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** Whether a paper is inside a follow's alert window. Both ends are inclusive. */
public final class HitWindow {

  private HitWindow() {}

  public static boolean contains(@Nullable LocalDate publicationDate, @Nullable Integer year, Instant firstSeenAt,
      @Nullable Integer sinceYear, LocalDate todayUtc, HitProperties p) {
    LocalDate seen = firstSeenAt.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate date = publicationDate != null ? publicationDate : seen;
    if (date.isAfter(todayUtc.plusDays(p.futureDays()))) return false;
    if (sinceYear != null) {
      return (year != null ? year : seen.getYear()) >= sinceYear;
    }
    return !date.isBefore(todayUtc.minusDays(p.windowDays()));
  }
}
