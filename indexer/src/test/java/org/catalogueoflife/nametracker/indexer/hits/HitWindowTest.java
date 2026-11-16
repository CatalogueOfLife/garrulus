package org.catalogueoflife.nametracker.indexer.hits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class HitWindowTest {
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);
  private static final HitProperties P = new HitProperties(90, 7);
  private static final Instant SEEN = TODAY.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);

  private static boolean rolling(LocalDate pub) {
    return HitWindow.contains(pub, null, SEEN, null, TODAY, P);
  }

  @Test
  void rollingWindowAccepts89RejectsOver90() {
    assertThat(rolling(TODAY.minusDays(89))).isTrue();
    assertThat(rolling(TODAY.minusDays(90))).isTrue();
    assertThat(rolling(TODAY.minusDays(91))).isFalse();
  }

  @Test
  void missingDateUsesFirstSeen() {
    assertThat(HitWindow.contains(null, null, SEEN.minus(java.time.Duration.ofDays(10)), null, TODAY, P)).isTrue();
    assertThat(HitWindow.contains(null, null, SEEN.minus(java.time.Duration.ofDays(120)), null, TODAY, P)).isFalse();
  }

  @Test
  void year1886FromLiveIsOutside() {
    assertThat(HitWindow.contains(LocalDate.of(1886, 1, 1), 1886, SEEN, null, TODAY, P)).isFalse();
  }

  @Test
  void thirtyDaysInFutureRejected() {
    assertThat(rolling(TODAY.plusDays(30))).isFalse();
    assertThat(rolling(TODAY.plusDays(8))).isFalse();
    assertThat(rolling(TODAY.plusDays(7))).isTrue();
    assertThat(HitWindow.contains(TODAY.plusDays(30), 2026, SEEN, 2000, TODAY, P)).isFalse();
  }

  @Test
  void sixDaysInFutureAccepted() {
    assertThat(rolling(TODAY.plusDays(6))).isTrue();
  }

  @Test
  void sinceYearUsesYearAndAcceptsOldPapers() {
    assertThat(HitWindow.contains(LocalDate.of(2001, 5, 1), 2001, SEEN, 2000, TODAY, P)).isTrue();
    assertThat(HitWindow.contains(null, 1999, SEEN, 2000, TODAY, P)).isFalse();
    // no year: the year of first seen
    assertThat(HitWindow.contains(null, null, SEEN, 2026, TODAY, P)).isTrue();
    assertThat(HitWindow.contains(null, null, SEEN, 2027, TODAY, P)).isFalse();
  }
}
