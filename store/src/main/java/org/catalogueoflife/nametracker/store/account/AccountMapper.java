package org.catalogueoflife.nametracker.store.account;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface AccountMapper {

  /** Case-insensitive; pass the normalised address. */
  @Nullable AppUser findByEmail(@Param("email") String normalisedEmail);

  @Nullable AppUser findByOrcid(@Param("orcid") String orcid);

  @Nullable AppUser findById(@Param("id") long id);

  /**
   * Inserts the row and returns the generated id. Null time zone, frequency, weekday and hour take the column
   * defaults (UTC, DAILY, Monday, 7); a duplicate email (ignoring case) or ORCID iD violates a unique key.
   */
  long insert(@Param("o") AppUser o);

  /** Sets all of these; the caller passes the current value for what stays; a null digestSince keeps the stored digest_since. */
  int updateSettings(@Param("id") long id, @Param("displayName") @Nullable String displayName,
      @Param("timeZone") String timeZone, @Param("digestFrequency") String digestFrequency,
      @Param("digestWeekday") int digestWeekday, @Param("digestHour") int digestHour,
      @Param("digestSince") @Nullable Instant digestSince);

  /** Stores a verified address: email_verified = now(), and digest_since = now() if it was never set. */
  int setEmail(@Param("id") long id, @Param("email") String email);

  int setOrcid(@Param("id") long id, @Param("orcid") String orcid);

  int touchLogin(@Param("id") long id);

  /**
   * Deletes the Spring sessions of the account (principal name = the account id), except the session with the id
   * {@code keepSessionId} (none if null). Returns the number deleted.
   */
  int deleteSessions(@Param("userId") long userId, @Param("keepSessionId") @Nullable String keepSessionId);

  /** Accounts that may get digests: a verified address and a frequency other than OFF; by id. */
  java.util.List<AppUser> digestCandidates();

  /** Accounts with an ORCID iD and a verified address, by id: the candidates for admin mail (web filters by iD). */
  java.util.List<AppUser> withOrcidAndVerifiedEmail();

  /** Hard delete; subscriptions, hits, digests and email tokens go with it (cascade), sessions do not. */
  int delete(@Param("id") long id);
}
