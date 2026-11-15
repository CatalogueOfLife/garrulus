package org.catalogueoflife.nametracker.indexer.cost;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.jspecify.annotations.Nullable;

/**
 * The per UTC day OpenAlex spend, kept in {@code api_usage}. {@link #check} runs before each call and {@link #record}
 * after it, with the cost and remaining amount the response headers report (both in USD). All arithmetic is BigDecimal.
 */
public class OpenAlexBudget {

  public static final String SERVICE = "openalex";

  public enum Call {
    SINGLETON("0"), LIST("0.0001"), GROUP_BY("0.0001"), CONTENT("0.01");

    private final BigDecimal price;

    Call(String price) {
      this.price = new BigDecimal(price);
    }

    public BigDecimal price() {
      return price;
    }
  }

  private final ApiUsageMapper usage;
  private final BigDecimal maxDailyUsd;
  private final BigDecimal contentReserveUsd;
  private final Clock clock;
  private final String fingerprint;

  /** Without an API key. */
  public OpenAlexBudget(ApiUsageMapper usage, BigDecimal maxDailyUsd, BigDecimal contentReserveUsd, Clock clock) {
    this(usage, maxDailyUsd, contentReserveUsd, clock, null);
  }

  /**
   * The remaining amount OpenAlex reports belongs to one key (a keyless caller has its own small allowance), so it is
   * stored with the key's fingerprint and only counts for a budget with the same fingerprint.
   */
  public OpenAlexBudget(ApiUsageMapper usage, BigDecimal maxDailyUsd, BigDecimal contentReserveUsd, Clock clock,
      @Nullable String apiKey) {
    this.fingerprint = fingerprint(apiKey);
    this.usage = usage;
    this.maxDailyUsd = maxDailyUsd;
    this.contentReserveUsd = contentReserveUsd;
    this.clock = clock;
  }

  /** The first 12 hex digits of SHA-256 of the key, or {@code none} without a key. The key itself is never kept. */
  public static String fingerprint(@Nullable String apiKey) {
    if (apiKey == null || apiKey.isBlank()) return "none";
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneOffset.UTC));
  }

  /** Throws when the call would overspend the daily cap, the remaining amount, or (content) the reserve. */
  public void check(Call kind) {
    BigDecimal price = kind.price();
    Map<String, Object> row = usage.get(SERVICE, today());
    BigDecimal spent = row == null ? BigDecimal.ZERO : (BigDecimal) row.get("usd");
    BigDecimal remaining = row == null || !fingerprint.equals(row.get("key_fingerprint")) ? null
        : (BigDecimal) row.get("remaining_usd");

    if (spent.add(price).compareTo(maxDailyUsd) > 0) throw exhausted();
    if (remaining != null && (remaining.signum() <= 0 || remaining.compareTo(price) < 0)) throw exhausted();
    if (kind == Call.CONTENT) {
      BigDecimal left = maxDailyUsd.subtract(spent);
      if (remaining != null && remaining.compareTo(left) < 0) left = remaining;
      if (left.subtract(price).compareTo(contentReserveUsd) < 0) throw exhausted();
    }
  }

  /** How long a 429 of unknown kind (burst or daily) defers the work. */
  public static final java.time.Duration SOON = java.time.Duration.ofMinutes(15);

  /** For a final 429: retryAt is now + {@link #SOON}. The next-day deferral comes only from {@link #check}. */
  public BudgetExhaustedException exhaustedSoon() {
    return new BudgetExhaustedException(clock.instant().plus(SOON));
  }

  /** The exception for a spent budget, with retryAt the next 00:05 UTC. */
  public BudgetExhaustedException exhausted() {
    Instant now = clock.instant();
    LocalDate day = now.atOffset(ZoneOffset.UTC).toLocalDate();
    Instant retry = day.atTime(LocalTime.of(0, 5)).toInstant(ZoneOffset.UTC);
    if (!retry.isAfter(now)) retry = day.plusDays(1).atTime(LocalTime.of(0, 5)).toInstant(ZoneOffset.UTC);
    return new BudgetExhaustedException(retry);
  }

  /** Header values win; without them the price table counts. Remaining keeps the last known value. */
  public void record(Call kind, @Nullable BigDecimal creditsUsed, @Nullable BigDecimal remaining) {
    usage.add(SERVICE, today(), 1, creditsUsed != null ? creditsUsed : kind.price(), remaining,
        fingerprint);
  }
}
