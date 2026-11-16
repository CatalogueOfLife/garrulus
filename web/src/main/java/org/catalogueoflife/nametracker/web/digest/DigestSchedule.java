package org.catalogueoflife.nametracker.web.digest;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.jspecify.annotations.Nullable;

/** When a user's digest is due, in the user's own time zone. Pure. */
public final class DigestSchedule {

  /** A hit is mailed only while it is at most this old: 14 days for daily digests, 21 for weekly ones. */
  static final Duration DAILY_MAX_AGE = Duration.ofDays(14);
  static final Duration WEEKLY_MAX_AGE = Duration.ofDays(21);

  private DigestSchedule() {}

  /**
   * True when the address is verified, the frequency is not OFF, the local hour has reached {@code digest_hour} and
   * no digest was sent in the current period: the local date (DAILY), or the local ISO week once the local weekday is
   * {@code digest_weekday} or later (WEEKLY). {@code lastSent} is the last SENT digest. Hours missed (an outage, a
   * DST gap) are caught up later in the same period; a repeated hour (DST end) does not send twice.
   */
  public static boolean isDue(AppUser u, @Nullable Instant lastSent, Instant now) {
    if (u.email() == null || u.emailVerified() == null || u.digestFrequency() == null) {
      return false;
    }
    ZoneId zone = zone(u);
    ZonedDateTime local = now.atZone(zone);
    int hour = u.digestHour() == null ? 7 : u.digestHour();
    if (local.getHour() < hour) {
      return false;
    }
    LocalDate today = local.toLocalDate();
    LocalDate last = lastSent == null ? null : lastSent.atZone(zone).toLocalDate();
    return switch (u.digestFrequency()) {
      case "DAILY" -> last == null || last.isBefore(today);
      case "WEEKLY" -> {
        int weekday = u.digestWeekday() == null ? 1 : u.digestWeekday();
        if (local.getDayOfWeek().getValue() < weekday) {
          yield false;
        }
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        yield last == null || last.isBefore(weekStart);
      }
      default -> false;
    };
  }

  /** The oldest hit a digest of this user may carry, as an age: 21 days for WEEKLY, 14 otherwise. */
  public static Duration maxAge(AppUser u) {
    return "WEEKLY".equals(u.digestFrequency()) ? WEEKLY_MAX_AGE : DAILY_MAX_AGE;
  }

  /** The user's zone; UTC when it is missing or unknown to this JVM. */
  static ZoneId zone(AppUser u) {
    try {
      return u.timeZone() == null ? ZoneOffset.UTC : ZoneId.of(u.timeZone());
    } catch (DateTimeException e) {
      return ZoneOffset.UTC;
    }
  }
}
