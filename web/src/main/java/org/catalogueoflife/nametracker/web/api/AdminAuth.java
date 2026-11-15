package org.catalogueoflife.nametracker.web.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The shared-secret check of the admin controllers: the {@code X-Admin-Token} header, compared in constant time. */
final class AdminAuth {

  private final byte[] token;

  AdminAuth(String token) {
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  /** Throws 401 unless the given header value equals the configured token. */
  void check(@Nullable String given) {
    if (given == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
  }
}
