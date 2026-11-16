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

  /** Sets all of these; the caller passes the current value for what stays and the new digest_since (or null). */
  int updateSettings(@Param("id") long id, @Param("displayName") @Nullable String displayName,
      @Param("timeZone") String timeZone, @Param("digestFrequency") String digestFrequency,
      @Param("digestWeekday") int digestWeekday, @Param("digestHour") int digestHour,
      @Param("digestSince") @Nullable Instant digestSince);

  /** Stores a verified address: email_verified = now(), and digest_since = now() if it was never set. */
  int setEmail(@Param("id") long id, @Param("email") String email);

  int setOrcid(@Param("id") long id, @Param("orcid") String orcid);

  int touchLogin(@Param("id") long id);

  /** Hard delete; subscriptions, hits, digests and email tokens go with it (cascade). */
  int delete(@Param("id") long id);
}
