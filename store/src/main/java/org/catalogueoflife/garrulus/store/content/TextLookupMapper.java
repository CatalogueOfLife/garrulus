package org.catalogueoflife.garrulus.store.content;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Which text fallback service was asked for a work, when, and whether it had a text. An outage is not recorded. */
@Mapper
public interface TextLookupMapper {

  /** True when the service was asked for the work at or after {@code since}. */
  boolean triedSince(@Param("workId") long workId, @Param("service") String service, @Param("since") Instant since);

  /** Records a try now (replaces an earlier one). */
  int record(@Param("workId") long workId, @Param("service") String service, @Param("found") boolean found);
}
