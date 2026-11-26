package org.catalogueoflife.garrulus.indexer.hits;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * Whether a paper is inside a follow's alert window. Both ends are inclusive. A paper without a publication date is
 * placed by its first-seen date, but in the rolling window a known year before the year of the window start puts it
 * outside (an old paper harvested today is not news).
 */
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
    LocalDate start = todayUtc.minusDays(p.windowDays());
    if (publicationDate == null && year != null && year < start.getYear()) return false;
    return !date.isBefore(start);
  }
}
