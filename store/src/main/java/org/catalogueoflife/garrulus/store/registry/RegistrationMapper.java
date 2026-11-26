package org.catalogueoflife.garrulus.store.registry;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** {@code source_registration}: written by {@code web} (insert, withdraw, decisions) and the indexer (identification). */
@Mapper
public interface RegistrationMapper {

  /** A decision that was just claimed for the user's mail. */
  record Decision(long id, long userId, String input, String decision, long sourceId, @Nullable String title) {}

  /** A registration identified as a candidate whose source still waits for an admin. */
  record Waiting(long id, String kind, String input, long sourceId, @Nullable String title) {}

  /** Inserts an IDENTIFYING registration and returns its id. */
  long insert(@Param("userId") long userId, @Param("kind") String kind, @Param("input") String input,
      @Param("note") @Nullable String note);

  /** Locks the account row until the end of the transaction (serialises the daily count of one user). */
  int lockUser(@Param("userId") long userId);

  /** Registrations of the user created after {@code since}, whatever their state. */
  int countSince(@Param("userId") long userId, @Param("since") Instant since);

  /** Registrations of all users created after {@code since}: the global daily cap. */
  int countAllSince(@Param("since") Instant since);

  /**
   * Deletes the {@code REGISTERED} signals of the user's registrations (signal key = registration id), as a withdrawal
   * does for one: called with the account deletion, before the registrations go with the user (cascade).
   */
  int deleteSignalsOfUser(@Param("userId") long userId);

  @Nullable SourceRegistration get(@Param("id") long id);

  /** As {@link #get}, locked until the end of the transaction. */
  @Nullable SourceRegistration lock(@Param("id") long id);

  /** The registration if it belongs to the user, else null. */
  @Nullable SourceRegistration getForUser(@Param("id") long id, @Param("userId") long userId);

  /** The user's registrations, newest first. */
  List<SourceRegistration> listByUser(@Param("userId") long userId, @Param("limit") int limit);

  /** IDENTIFYING → IDENTIFIED with the source, outcome and the source's tier; 0 if it was not IDENTIFYING. */
  int identified(@Param("id") long id, @Param("outcome") String outcome, @Param("sourceId") long sourceId,
      @Param("tier") String tier);

  /** IDENTIFYING → NOT_IDENTIFIED with the reason; 0 if it was not IDENTIFYING. */
  int notIdentified(@Param("id") long id, @Param("reason") String reason);

  /**
   * Any state but WITHDRAWN → WITHDRAWN, returning the source it pointed at in the same statement (so an identification
   * that commits meanwhile is seen); null if nothing changed or it had no source.
   */
  @Nullable Long withdraw(@Param("id") long id, @Param("userId") long userId);

  /** The user's IDENTIFYING or IDENTIFIED registration of this input, if any (the newest). */
  @Nullable Long findActive(@Param("userId") long userId, @Param("kind") String kind, @Param("input") String input);

  /** Moves every registration of one account to another (account merge). */
  int reassign(@Param("from") long from, @Param("to") long to);

  /**
   * Sets {@code decision} (ACCEPTED for a CORE or BROAD source, DECLINED for an EXCLUDED one) and {@code decision_at}
   * on up to {@code limit} identified registrations whose tier at identification was CANDIDATE and that have no
   * decision yet (oldest first, skipping locked rows), and returns them. WITHDRAWN registrations are never claimed.
   */
  List<Decision> claimDecisions(@Param("limit") int limit);

  /**
   * Registrations identified as candidates after {@code since} (all if null) whose source is still a CANDIDATE, oldest
   * first, at most {@code limit}.
   */
  List<Waiting> waitingSince(@Param("since") @Nullable Instant since, @Param("limit") int limit);

  /** The count of {@link #waitingSince}, unlimited. */
  int countWaitingSince(@Param("since") @Nullable Instant since);

  /** Admin view: the registrations in this state (all if null), newest first. */
  List<AdminRegistrationRow> adminList(@Param("state") @Nullable String state, @Param("limit") int limit,
      @Param("offset") int offset);

  /** Admin view: the registrations that point at the source (a folded one too), newest first. */
  List<AdminRegistrationRow> adminForSource(@Param("sourceId") long sourceId);
}
