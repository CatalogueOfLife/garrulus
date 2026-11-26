package org.catalogueoflife.garrulus.store.account;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface HitMapper {

  /** ON CONFLICT DO NOTHING on (subscription, work, kind); returns 1 if inserted, 0 for a duplicate. */
  int insertIfAbsent(@Param("o") Hit o);

  @Nullable Hit find(@Param("subscriptionId") long subscriptionId, @Param("workId") long workId,
      @Param("kind") String kind);

  /** The user's hits, newest first, optionally of one subscription. */
  List<Hit> listByUser(@Param("userId") long userId, @Param("subscriptionId") @Nullable Long subscriptionId,
      @Param("limit") int limit, @Param("offset") int offset);

  /**
   * The user's hits of unpaused subscriptions with no digest, created after {@code since} (digest_since) and not
   * before {@code minCreated}; newest first.
   */
  List<Hit> undeliveredForDigest(@Param("userId") long userId, @Param("since") Instant since,
      @Param("minCreated") Instant minCreated);

  /**
   * Sets digest_id of the listed hits that are in no digest yet; returns the number marked. An empty list marks
   * nothing; the ids are bound as one array, so the list may be of any length.
   */
  int markDelivered(@Param("hitIds") List<Long> hitIds, @Param("digestId") long digestId);

  /** Clears digest_id of every hit of the digest (rollback of a failed send). */
  int unmark(@Param("digestId") long digestId);

  /**
   * Re-points the hits of one work to another (a merge) unless the target has a hit of the same subscription and kind;
   * those stay on the old work and go with it. Returns the rows moved.
   */
  int moveHits(@Param("fromWork") long fromWork, @Param("toWork") long toWork);

  /** The subscription's hits, newest first. */
  List<Hit> byFeed(@Param("subscriptionId") long subscriptionId, @Param("limit") int limit);
}
