package org.catalogueoflife.garrulus.store.account;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface EmailTokenMapper {

  void insert(@Param("userId") long userId, @Param("email") String email, @Param("tokenHash") String tokenHash,
      @Param("expires") Instant expires);

  /** Deletes the row of the hash and returns it; null when unknown or expired. */
  @Nullable EmailToken consume(@Param("tokenHash") String tokenHash);

  /** The row of the hash, left in place; null when unknown or expired. */
  @Nullable EmailToken findValid(@Param("tokenHash") String tokenHash);

  /** Deletes the email tokens that expired before {@code now}; returns the number deleted. */
  int purgeExpired(@Param("now") Instant now);

  /**
   * Deletes the expired sign-in tokens of Spring's one-time-token login ({@code one_time_tokens}). Its
   * {@code expires_at} has no time zone; the instant is bound the way JdbcOneTimeTokenService binds it.
   */
  int purgeExpiredOneTimeTokens(@Param("now") Instant now);
}
