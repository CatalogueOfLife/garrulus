package org.catalogueoflife.nametracker.store.notice;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface AdminNoticeMapper {

  /** Returns 0 and changes nothing when a notice with that key exists (a redelivered task adds nothing). */
  int insert(@Param("kind") String kind, @Param("dedupKey") String dedupKey, @Param("subject") String subject,
      @Param("body") String body);

  /**
   * Marks up to {@code limit} notices as mailed (oldest first, skipping locked rows) and returns them: the unmailed ones
   * and those claimed before {@code staleBefore} that never got an outcome (the claiming run died before it sent them).
   */
  List<AdminNotice> claimUnmailed(@Param("limit") int limit, @Param("staleBefore") java.time.Instant staleBefore);

  int setOutcome(@Param("id") long id, @Param("outcome") String outcome);

  /** The newest notice whose kind starts with the prefix and whose key starts with the prefix. */
  @Nullable AdminNotice latest(@Param("kindPrefix") String kindPrefix, @Param("keyPrefix") String keyPrefix);

  /** The newest {@code layer-*} notice of the layer (keys {@code layer-failing:<LAYER>:<day>} and alike). */
  @Nullable AdminNotice latestForLayer(@Param("layer") String layer);
}
