package org.catalogueoflife.nametracker.store.account;

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
}
