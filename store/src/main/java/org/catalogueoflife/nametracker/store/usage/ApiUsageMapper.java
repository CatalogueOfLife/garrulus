package org.catalogueoflife.nametracker.store.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface ApiUsageMapper {

  /** Upsert that increments calls and usd; remaining_usd keeps the last non-null value seen under the same key fingerprint,
   * and starts over when the fingerprint changes. */
  void add(@Param("service") String service, @Param("day") LocalDate day, @Param("calls") int calls,
      @Param("usd") BigDecimal usd, @Param("remainingUsd") @Nullable BigDecimal remainingUsd,
      @Param("keyFingerprint") String keyFingerprint);

  /** As above, for a call made without an API key (fingerprint {@code none}). */
  default void add(String service, LocalDate day, int calls, BigDecimal usd, @Nullable BigDecimal remainingUsd) {
    add(service, day, calls, usd, remainingUsd, "none");
  }

  /** Columns service, day, calls, usd, remaining_usd, key_fingerprint; null when there is no row. */
  Map<String, Object> get(@Param("service") String service, @Param("day") LocalDate day);

  /** All rows of a day, ordered by service; columns as in {@link #get}. */
  List<Map<String, Object>> listDay(@Param("day") LocalDate day);
}
