package org.catalogueoflife.nametracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SignInRateLimiterTest {

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-11-16T10:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  final MutableClock clock = new MutableClock();
  final SignInRateLimiter limiter = new SignInRateLimiter(clock);

  @Test
  void threePerAddressPer15Minutes() {
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.2")).isTrue();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.3")).isTrue();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.4")).isFalse();
    // other addresses are not affected
    assertThat(limiter.tryAcquire("b@example.org", "10.0.0.4")).isTrue();
  }

  @Test
  void addressWindowSlides() {
    limiter.tryAcquire("a@example.org", "10.0.0.1");
    clock.advance(Duration.ofMinutes(5));
    limiter.tryAcquire("a@example.org", "10.0.0.1");
    limiter.tryAcquire("a@example.org", "10.0.0.1");
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isFalse();
    // 15 minutes after the first: one slot free again, not three
    clock.advance(Duration.ofMinutes(10));
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isFalse();
    clock.advance(Duration.ofMinutes(5));
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isTrue();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isFalse();
  }

  @Test
  void refusedRequestsDoNotExtendTheBlock() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("a@example.org", "10.0.0.1");
    }
    for (int i = 0; i < 10; i++) {
      clock.advance(Duration.ofMinutes(1));
      assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isFalse();
    }
    clock.advance(Duration.ofMinutes(5));
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isTrue();
  }

  @Test
  void twentyPerIpPerHour() {
    for (int i = 0; i < 20; i++) {
      assertThat(limiter.tryAcquire("u" + i + "@example.org", "10.0.0.9")).as("request " + i).isTrue();
    }
    assertThat(limiter.tryAcquire("u20@example.org", "10.0.0.9")).isFalse();
    // another IP is not affected
    assertThat(limiter.tryAcquire("u20@example.org", "10.0.0.10")).isTrue();
    // a request refused for its IP does not use up the address
    assertThat(limiter.tryAcquire("u21@example.org", "10.0.0.9")).isFalse();
    assertThat(limiter.tryAcquire("u21@example.org", "10.0.0.11")).isTrue();
    assertThat(limiter.tryAcquire("u21@example.org", "10.0.0.11")).isTrue();
    assertThat(limiter.tryAcquire("u21@example.org", "10.0.0.11")).isTrue();
  }

  @Test
  void ipWindowSlides() {
    for (int i = 0; i < 10; i++) {
      limiter.tryAcquire("u" + i + "@example.org", "10.0.0.9");
    }
    clock.advance(Duration.ofMinutes(30));
    for (int i = 10; i < 20; i++) {
      limiter.tryAcquire("u" + i + "@example.org", "10.0.0.9");
    }
    assertThat(limiter.tryAcquire("x@example.org", "10.0.0.9")).isFalse();
    // an hour after the first ten, ten slots are free again
    clock.advance(Duration.ofMinutes(30));
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.tryAcquire("v" + i + "@example.org", "10.0.0.9")).as("request " + i).isTrue();
    }
    assertThat(limiter.tryAcquire("y@example.org", "10.0.0.9")).isFalse();
  }

  @Test
  void addressRefusalDoesNotUseTheIpSlot() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("a@example.org", "10.0.0.1");
    }
    for (int i = 0; i < 30; i++) {
      assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isFalse();
    }
    // 3 IP slots used, 17 left
    for (int i = 0; i < 17; i++) {
      assertThat(limiter.tryAcquire("o" + i + "@example.org", "10.0.0.1")).as("request " + i).isTrue();
    }
    assertThat(limiter.tryAcquire("z@example.org", "10.0.0.1")).isFalse();
  }

  @Test
  void refusedRequestsLeaveNoEntries() {
    for (int i = 0; i < 20; i++) {
      limiter.tryAcquire("u" + i + "@example.org", "10.0.0.9");
    }
    int tracked = limiter.size();
    for (int i = 0; i < 1000; i++) {
      assertThat(limiter.tryAcquire("fresh" + i + "@example.org", "10.0.0.9")).isFalse();
    }
    assertThat(limiter.size()).isEqualTo(tracked);
    // nor does an address refused for itself add its IP
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("a@example.org", "10.0.0.1");
    }
    tracked = limiter.size();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.77")).isFalse();
    assertThat(limiter.size()).isEqualTo(tracked);
  }

  @Test
  void clearForgetsEverything() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("a@example.org", "10.0.0.1");
    }
    limiter.clear();
    assertThat(limiter.tryAcquire("a@example.org", "10.0.0.1")).isTrue();
  }
}
