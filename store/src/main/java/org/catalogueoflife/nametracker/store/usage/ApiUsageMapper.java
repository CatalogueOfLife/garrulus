package org.catalogueoflife.nametracker.store.usage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface ApiUsageMapper {

  /** Upsert that increments calls and usd; remaining_usd keeps the last non-null value seen. */
  void add(@Param("service") String service, @Param("day") LocalDate day, @Param("calls") int calls,
      @Param("usd") BigDecimal usd, @Param("remainingUsd") @Nullable BigDecimal remainingUsd);

  /** Columns service, day, calls, usd, remaining_usd; null when there is no row. */
  Map<String, Object> get(@Param("service") String service, @Param("day") LocalDate day);
}
