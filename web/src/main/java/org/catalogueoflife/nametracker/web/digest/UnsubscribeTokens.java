package org.catalogueoflife.nametracker.web.digest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signed unsubscribe tokens {@code <userId>.<base64url(HMAC-SHA256(key, "unsub:" + userId))>}, so a link from a
 * digest turns digests off without signing in. The key is {@code nametracker.signing-key}; when it is blank a random
 * key is made per start (one WARN), and links of earlier starts stop working.
 */
@Component
public class UnsubscribeTokens {

  private static final Logger LOG = LoggerFactory.getLogger(UnsubscribeTokens.class);
  private static final String ALG = "HmacSHA256";
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private final SecretKeySpec key;

  public UnsubscribeTokens(@Value("${nametracker.signing-key:}") String signingKey) {
    byte[] bytes;
    if (signingKey == null || signingKey.isBlank()) {
      LOG.warn("nametracker.signing-key (NAMETRACKER_SIGNING_KEY) is blank: using a random key, so unsubscribe "
          + "links stop working at the next start");
      bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
    } else {
      bytes = signingKey.getBytes(StandardCharsets.UTF_8);
    }
    this.key = new SecretKeySpec(bytes, ALG);
  }

  public String create(long userId) {
    return userId + "." + B64.encodeToString(mac(userId));
  }

  /** The user id of a valid token, else null. */
  public @Nullable Long verify(@Nullable String token) {
    if (token == null) {
      return null;
    }
    int dot = token.indexOf('.');
    if (dot <= 0 || dot == token.length() - 1) {
      return null;
    }
    long userId;
    byte[] given;
    try {
      userId = Long.parseLong(token.substring(0, dot));
      given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return null;
    }
    return MessageDigest.isEqual(mac(userId), given) ? userId : null;
  }

  private byte[] mac(long userId) {
    try {
      Mac mac = Mac.getInstance(ALG);
      mac.init(key);
      return mac.doFinal(("unsub:" + userId).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
