package org.catalogueoflife.nametracker.store.account;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface SubscriptionMapper {

  /** Oldest first. */
  List<Subscription> listByUser(@Param("userId") long userId);

  int countByUser(@Param("userId") long userId);

  /** Inserts the row (id and created ignored) and returns the generated id; a duplicate (user, type, target) violates the unique key. */
  long insert(@Param("o") Subscription o);

  /** Updates label, options, filters, since_year and paused of the row {@code o.id} of user {@code o.userId}. */
  int update(@Param("o") Subscription o);

  int delete(@Param("id") long id, @Param("userId") long userId);

  @Nullable Subscription byFeedToken(@Param("token") String token);

  int setFeedToken(@Param("id") long id, @Param("token") String token);

  /**
   * Account merge: moves every subscription of {@code fromUserId} to {@code toUserId}. Of two with the same (type,
   * target) the older one (created, then id) stays with its options, whichever account it was on; the hits of the
   * other move to it unless it already has that (work, kind), and the other is deleted with its remaining hits. Not
   * limited by the per-user cap. Call it inside a transaction.
   */
  default void merge(long fromUserId, long toUserId) {
    moveHitsOfDuplicates(fromUserId, toUserId);
    deleteNewerDuplicates(fromUserId, toUserId);
    reassign(fromUserId, toUserId);
  }

  /** Step 1 of {@link #merge}: the newer duplicate's hits move to the older one where the unique key allows. */
  int moveHitsOfDuplicates(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

  /** Step 2 of {@link #merge}: deletes the newer of each duplicate pair (cascading its remaining hits). */
  int deleteNewerDuplicates(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

  /** Step 3 of {@link #merge}: the remaining subscriptions change owner. */
  int reassign(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);

  /**
   * Unpaused subscriptions of any user whose (type, target) is one of the keys: SOURCE by {@code sourceId}, TAXON by
   * {@code taxonIds}, NAME by {@code nameIds}, TAXGROUP by {@code taxgroups}. Null or empty key lists match nothing.
   */
  default List<Subscription> candidates(@Nullable String sourceId, @Nullable List<String> taxonIds,
      @Nullable List<String> nameIds, @Nullable List<String> taxgroups) {
    return candidates(sourceId, taxonIds, nameIds, taxgroups, false);
  }

  /** As {@link #candidates(String, List, List, List)}, with paused subscriptions too when {@code includePaused}. */
  List<Subscription> candidates(@Param("sourceId") @Nullable String sourceId,
      @Param("taxonIds") @Nullable List<String> taxonIds, @Param("nameIds") @Nullable List<String> nameIds,
      @Param("taxgroups") @Nullable List<String> taxgroups, @Param("includePaused") boolean includePaused);
}
