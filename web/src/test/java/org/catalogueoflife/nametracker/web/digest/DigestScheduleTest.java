package org.catalogueoflife.nametracker.web.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class DigestScheduleTest {

  static AppUser user(String zone, String frequency, int weekday, int hour, boolean verified) {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    return new AppUser(1L, "a@example.org", verified ? t : null, null, null, zone, frequency, weekday, hour, t, t, null);
  }

  static boolean due(AppUser u, @Nullable String lastSent, String now) {
    return DigestSchedule.isDue(u, lastSent == null ? null : Instant.parse(lastSent), Instant.parse(now));
  }

  @Test
  void dailyDueOnceAfterHour() {
    AppUser u = user("UTC", "DAILY", 1, 7, true);
    assertThat(due(u, null, "2026-11-16T06:05:00Z")).isFalse();
    assertThat(due(u, null, "2026-11-16T07:05:00Z")).isTrue();
    // sent at 07:05: not again that day, also not late in the evening
    assertThat(due(u, "2026-11-16T07:05:01Z", "2026-11-16T08:05:00Z")).isFalse();
    assertThat(due(u, "2026-11-16T07:05:01Z", "2026-11-16T23:05:00Z")).isFalse();
    // next day: not before the hour, then once
    assertThat(due(u, "2026-11-16T07:05:01Z", "2026-11-17T06:05:00Z")).isFalse();
    assertThat(due(u, "2026-11-16T07:05:01Z", "2026-11-17T07:05:00Z")).isTrue();
    // missed hours catch up later the same day
    assertThat(due(u, "2026-11-15T07:05:01Z", "2026-11-16T15:05:00Z")).isTrue();
  }

  @Test
  void berlinDstChangeDayOnce() {
    // 2026-10-25: 03:00 CEST becomes 02:00 CET, so local 02:05 happens twice (00:05 and 01:05 UTC)
    AppUser u = user("Europe/Berlin", "DAILY", 1, 2, true);
    assertThat(due(u, "2026-10-24T00:05:01Z", "2026-10-24T23:05:00Z")).isFalse(); // 01:05 CEST
    assertThat(due(u, "2026-10-24T00:05:01Z", "2026-10-25T00:05:00Z")).isTrue(); // 02:05 CEST
    assertThat(due(u, "2026-10-25T00:05:01Z", "2026-10-25T01:05:00Z")).isFalse(); // 02:05 CET, the second time
    assertThat(due(u, "2026-10-25T00:05:01Z", "2026-10-25T22:05:00Z")).isFalse(); // 23:05 CET
    assertThat(due(u, "2026-10-25T00:05:01Z", "2026-10-26T01:05:00Z")).isTrue(); // 02:05 CET next day
    // 2026-03-29: local 02:xx does not exist; 01:05 UTC is 03:05 CEST, past the hour
    assertThat(due(u, "2026-03-28T01:05:01Z", "2026-03-29T00:05:00Z")).isFalse(); // 01:05 CET
    assertThat(due(u, "2026-03-28T01:05:01Z", "2026-03-29T01:05:00Z")).isTrue();
  }

  @Test
  void kolkataHalfHourOffset() {
    AppUser u = user("Asia/Kolkata", "DAILY", 1, 7, true);
    assertThat(due(u, null, "2026-11-16T01:05:00Z")).isFalse(); // 06:35 IST
    assertThat(due(u, null, "2026-11-16T02:05:00Z")).isTrue(); // 07:35 IST
    // sent at 07:35 IST on the 16th; 18:35 UTC is already 00:05 IST on the 17th, before the hour
    assertThat(due(u, "2026-11-16T02:05:01Z", "2026-11-16T18:35:00Z")).isFalse();
    assertThat(due(u, "2026-11-16T02:05:01Z", "2026-11-17T01:05:00Z")).isFalse();
    assertThat(due(u, "2026-11-16T02:05:01Z", "2026-11-17T02:05:00Z")).isTrue();
    // sent late on the 16th UTC = early on the 17th IST: not due again on the 17th
    assertThat(due(u, "2026-11-16T20:05:01Z", "2026-11-17T02:05:00Z")).isFalse();
  }

  @Test
  void weeklyDueFromWeekdayOncePerWeek() {
    AppUser u = user("UTC", "WEEKLY", 3, 7, true); // Wednesday; 2026-11-16 is a Monday
    assertThat(due(u, null, "2026-11-17T09:05:00Z")).isFalse(); // Tuesday
    assertThat(due(u, null, "2026-11-18T06:05:00Z")).isFalse(); // Wednesday before the hour
    assertThat(due(u, null, "2026-11-18T07:05:00Z")).isTrue();
    assertThat(due(u, "2026-11-18T07:05:01Z", "2026-11-19T07:05:00Z")).isFalse(); // same week
    assertThat(due(u, "2026-11-18T07:05:01Z", "2026-11-22T23:05:00Z")).isFalse(); // Sunday, same week
    assertThat(due(u, "2026-11-18T07:05:01Z", "2026-11-23T07:05:00Z")).isFalse(); // Monday, before Wednesday
    assertThat(due(u, "2026-11-18T07:05:01Z", "2026-11-25T07:05:00Z")).isTrue();
    // a missed Wednesday is caught up later in the week
    assertThat(due(u, "2026-11-11T07:05:01Z", "2026-11-20T10:05:00Z")).isTrue();
    // a digest sent earlier in the week (forced on Monday) counts
    assertThat(due(u, "2026-11-16T10:00:00Z", "2026-11-18T07:05:00Z")).isFalse();
  }

  @Test
  void offAndUnverifiedNeverDue() {
    assertThat(due(user("UTC", "OFF", 1, 0, true), null, "2026-11-16T12:05:00Z")).isFalse();
    assertThat(due(user("UTC", "DAILY", 1, 0, false), null, "2026-11-16T12:05:00Z")).isFalse();
    AppUser noAddress = new AppUser(1L, null, Instant.EPOCH, "0000-0001", null, "UTC", "DAILY", 1, 0, null, null, null);
    assertThat(DigestSchedule.isDue(noAddress, null, Instant.parse("2026-11-16T12:05:00Z"))).isFalse();
  }
}
