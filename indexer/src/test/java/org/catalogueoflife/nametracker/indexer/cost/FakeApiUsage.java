package org.catalogueoflife.nametracker.indexer.cost;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;

/** In-memory ApiUsageMapper with the same upsert semantics as the SQL. */
public class FakeApiUsage implements ApiUsageMapper {
  public final Map<String, Map<String, Object>> rows = new HashMap<>();

  @Override
  public void add(String service, LocalDate day, int calls, BigDecimal usd, BigDecimal remainingUsd) {
    Map<String, Object> r = rows.computeIfAbsent(service + "|" + day, k -> {
      Map<String, Object> m = new HashMap<>();
      m.put("calls", 0);
      m.put("usd", BigDecimal.ZERO);
      m.put("remaining_usd", null);
      return m;
    });
    r.put("calls", (Integer) r.get("calls") + calls);
    r.put("usd", ((BigDecimal) r.get("usd")).add(usd));
    if (remainingUsd != null) r.put("remaining_usd", remainingUsd);
  }

  @Override
  public Map<String, Object> get(String service, LocalDate day) {
    return rows.get(service + "|" + day);
  }
}
