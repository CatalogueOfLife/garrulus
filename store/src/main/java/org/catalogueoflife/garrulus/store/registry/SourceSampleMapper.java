package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface SourceSampleMapper {

  /** Inserts the sample (id and sampled_at of the argument are ignored) and returns the generated id. */
  long insert(@Param("o") SourceSample sample);

  /** The newest sample of the source, or null. */
  @Nullable SourceSample latest(@Param("sourceId") long sourceId);

  /** The newest sample of each of these sources (one query); a source without one has no entry. */
  List<SourceSample> latestFor(@Param("ids") List<Long> ids);

  /** The newest samples of the source, newest first. */
  List<SourceSample> recent(@Param("sourceId") long sourceId, @Param("limit") int limit);

  /** True when a user registered the source (origin REGISTERED or a registration that is not withdrawn). */
  boolean registered(@Param("sourceId") long sourceId);

  /** Every active, not ceased CANDIDATE with whether a user registered it, ordered by id. */
  List<CandidateRow> candidateRows();

  /**
   * Up to {@code limit} ids of active, not ceased CANDIDATEs without a sample newer than {@code sampledBefore} and without a QUEUED or RUNNING SAMPLE_SOURCE task: the
   * registered ones first, then those never sampled, then by candidate score descending (unscored last), then by id.
   */
  List<Long> dueForSampling(@Param("sampledBefore") Instant sampledBefore, @Param("limit") int limit);
}
