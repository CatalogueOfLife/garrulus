package org.catalogueoflife.nametracker.store.content;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** Audit of every institutional (publisher API) fetch; the row outlives its work (work_id is set null). */
@Mapper
public interface InstitutionalFetchMapper {

  /** Inserts an audit row and returns its id. */
  long insert(@Param("workId") @Nullable Long workId, @Param("doi") String doi, @Param("publisher") String publisher,
      @Param("api") String api, @Param("status") int status, @Param("bytes") int bytes,
      @Param("outcome") String outcome);

  /** Fetches of the publisher at or after {@code since}. */
  int countSince(@Param("publisher") String publisher, @Param("since") Instant since);
}
