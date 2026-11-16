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
   * Unpaused subscriptions of any user whose (type, target) is one of the keys: SOURCE by {@code sourceId}, TAXON by
   * {@code taxonIds}, NAME by {@code nameIds}, TAXGROUP by {@code taxgroups}. Null or empty key lists match nothing.
   */
  List<Subscription> candidates(@Param("sourceId") @Nullable String sourceId,
      @Param("taxonIds") @Nullable List<String> taxonIds, @Param("nameIds") @Nullable List<String> nameIds,
      @Param("taxgroups") @Nullable List<String> taxgroups);
}
