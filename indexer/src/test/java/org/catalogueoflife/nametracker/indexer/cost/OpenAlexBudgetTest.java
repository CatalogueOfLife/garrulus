package org.catalogueoflife.nametracker.indexer.cost;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget.Call;
import org.junit.jupiter.api.Test;

class OpenAlexBudgetTest {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

  private static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  @Test
  void budgetExhaustedDefersToNextUtcDay() {
    FakeApiUsage usage = new FakeApiUsage();
    OpenAlexBudget b = new OpenAlexBudget(usage, d("0.0002"), d("0.30"), NOON);
    b.check(Call.LIST);
    b.record(Call.LIST, null, null);
    b.check(Call.LIST);
    b.record(Call.LIST, null, null);

    BudgetExhaustedException e = assertThrows(BudgetExhaustedException.class, () -> b.check(Call.LIST));
    assertEquals(Instant.parse("2026-11-16T00:05:00Z"), e.retryAt());
    assertEquals(0, d("0.0002").compareTo((BigDecimal) usage.get("openalex", TODAY).get("usd")));
    assertEquals(2, usage.get("openalex", TODAY).get("calls"));
    b.check(Call.SINGLETON); // free calls still fit under the cap
  }

  @Test
  void exhaustedSoonDefersFifteenMinutes() {
    OpenAlexBudget b = new OpenAlexBudget(new FakeApiUsage(), d("1.00"), d("0.30"), NOON);
    assertEquals(Instant.parse("2026-11-15T12:15:00Z"), b.exhaustedSoon().retryAt());
  }

  @Test
  void tomorrowStartsFresh() {
    FakeApiUsage usage = new FakeApiUsage();
    usage.add("openalex", TODAY, 5, d("1.00"), d("0"));
    Clock next = Clock.fixed(Instant.parse("2026-11-16T00:00:01Z"), ZoneOffset.UTC);
    new OpenAlexBudget(usage, d("1.00"), d("0.30"), next).check(Call.LIST);
  }

  @Test
  void contentLeavesTheReserve() {
    FakeApiUsage usage = new FakeApiUsage();
    OpenAlexBudget b = new OpenAlexBudget(usage, d("1.00"), d("0.30"), NOON);
    b.record(Call.LIST, d("0.0001"), d("0.305"));
    assertThrows(BudgetExhaustedException.class, () -> b.check(Call.CONTENT));
    b.check(Call.LIST);
  }

  @Test
  void contentAllowedAboveTheReserve() {
    FakeApiUsage usage = new FakeApiUsage();
    OpenAlexBudget b = new OpenAlexBudget(usage, d("1.00"), d("0.30"), NOON);
    b.record(Call.LIST, d("0.0001"), d("0.50"));
    b.check(Call.CONTENT);
  }

  @Test
  void reportedRemainingBelowThePriceBlocks() {
    FakeApiUsage usage = new FakeApiUsage();
    OpenAlexBudget b = new OpenAlexBudget(usage, d("1.00"), d("0.30"), NOON);
    b.record(Call.LIST, d("0.0001"), d("0.00005"));
    assertThrows(BudgetExhaustedException.class, () -> b.check(Call.LIST));
  }

  @Test
  void zeroRemainingBlocksEvenFreeCalls() {
    FakeApiUsage usage = new FakeApiUsage();
    OpenAlexBudget b = new OpenAlexBudget(usage, d("1.00"), d("0.30"), NOON);
    b.record(Call.LIST, d("0.0001"), d("0"));
    assertThrows(BudgetExhaustedException.class, () -> b.check(Call.SINGLETON));
  }

  @Test
  void headerCostsWinOverPriceTable() {
    FakeApiUsage usage = new FakeApiUsage();
    OpenAlexBudget b = new OpenAlexBudget(usage, d("1.00"), d("0.30"), NOON);
    b.record(Call.LIST, d("0.0005"), d("0.9995"));
    b.record(Call.SINGLETON, null, null);
    b.record(Call.CONTENT, null, null);
    assertEquals(0, d("0.0105").compareTo((BigDecimal) usage.get("openalex", TODAY).get("usd")));
    assertEquals(0, d("0.9995").compareTo((BigDecimal) usage.get("openalex", TODAY).get("remaining_usd")));
    assertEquals(3, usage.get("openalex", TODAY).get("calls"));
  }
}
