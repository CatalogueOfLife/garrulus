package org.catalogueoflife.garrulus.store.route;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface SourceRouteMapper {

  /** Inserts a route (id and created are ignored); unique per source, layer and config. Returns the id. */
  long insert(@Param("o") SourceRoute route);

  /** As {@link #insert}, but returns null and changes nothing when the source has that layer and config already. */
  @Nullable Long insertIfAbsent(@Param("o") SourceRoute route);

  SourceRoute get(@Param("id") long id);

  /** Oldest first. */
  List<SourceRoute> forSource(@Param("sourceId") long sourceId);

  /** Oldest first; a null layer means every layer. */
  List<SourceRoute> listByStatus(@Param("layer") @Nullable String layer, @Param("status") String status,
      @Param("limit") int limit);

  /**
   * VERIFIED routes of active CORE/BROAD sources with no QUEUED or RUNNING {@code HARVEST_<layer>} task of their own
   * (an onboarding pass already under way) and no onboarding pass that went DEAD since the route was last verified,
   * oldest first: the routes the nightly rollout starts.
   */
  List<SourceRoute> listRollout(@Param("limit") int limit);

  /** Sets the status and replaces the stored error (null clears it). */
  int setStatus(@Param("id") long id, @Param("status") String status, @Param("error") @Nullable String error);

  /** Records why the route's last job failed, keeping its status. */
  int setError(@Param("id") long id, @Param("error") String error);

  int setVerified(@Param("id") long id, @Param("at") Instant at);

  /** A job of the route ran to its end: sets {@code last_ok} and clears {@code last_error}. */
  int markOk(@Param("id") long id, @Param("at") Instant at);

  int setOnboarded(@Param("id") long id, @Param("at") Instant at);

  int setBackfilled(@Param("id") long id, @Param("at") Instant at);

  /** Counts one more lost long pass of the route and returns the new count. */
  int incrementLongRestarts(@Param("id") long id);

  int resetLongRestarts(@Param("id") long id);

  /** Route counts per layer and status: {@code layer}, {@code status}, {@code n}. */
  List<Map<String, Object>> countByLayerAndStatus();

  /**
   * A source ceased: its ACTIVE, VERIFIED and PENDING routes become PAUSED with {@code reason} as last_error. Returns
   * the count.
   */
  int pauseAll(@Param("sourceId") long sourceId, @Param("reason") String reason);

  /**
   * A source is active again: the routes {@link #pauseAll} paused (PAUSED with that last_error) become PENDING, error
   * cleared, so VERIFY_ROUTE decides what each is. Returns their ids.
   */
  List<Long> resumePaused(@Param("sourceId") long sourceId, @Param("reason") String reason);
}
