package org.catalogueoflife.garrulus.store.account;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

@Mapper
public interface DigestMapper {

  /** Inserts the row (id and sent_at ignored; sent_at is now()) and returns the generated id. */
  long insert(@Param("o") Digest o);

  int setStatus(@Param("id") long id, @Param("status") String status, @Param("error") @Nullable String error);

  /** The latest sent_at among the user's SENT digests, or null. */
  @Nullable Instant lastSent(@Param("userId") long userId);

  /** The user's digests, newest first. */
  java.util.List<Digest> listByUser(@Param("userId") long userId);

  /** Moves every digest of one account to another (account merge); returns the number moved. */
  int reassign(@Param("fromUserId") long fromUserId, @Param("toUserId") long toUserId);
}
