package org.catalogueoflife.garrulus.web.digest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.catalogueoflife.garrulus.store.account.AccountMapper;
import org.catalogueoflife.garrulus.store.account.AppUser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One-click unsubscribe (RFC 8058) without signing in: {@code POST /api/unsubscribe?t=<token>} turns digests off
 * (digest_frequency OFF, all other settings kept) and answers 200 {}. Mail clients post here from the
 * List-Unsubscribe header; the SPA page {@code /unsubscribe} posts the token of the footer link. A GET changes nothing:
 * it redirects to that SPA page (mail clients without RFC 8058 open the URL), so link scanners cannot unsubscribe
 * anyone. A bad token is a 400. No CSRF (the signed token is the credential) and no session.
 */
@RestController
public class UnsubscribeController {

  private static final Logger LOG = LoggerFactory.getLogger(UnsubscribeController.class);

  private final UnsubscribeTokens tokens;
  private final AccountMapper accounts;

  private final String publicUrl;

  public UnsubscribeController(UnsubscribeTokens tokens, AccountMapper accounts,
      @Value("${garrulus.public-url}") String publicUrl) {
    this.tokens = tokens;
    this.accounts = accounts;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
  }

  /** 302 to the SPA page, which asks and posts; nothing changes here. */
  @GetMapping("/api/unsubscribe")
  public ResponseEntity<Void> page(@RequestParam(name = "t", required = false) @Nullable String token) {
    String target = publicUrl + "/unsubscribe"
        + (token == null ? "" : "?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
  }

  @PostMapping("/api/unsubscribe")
  public Map<String, Object> unsubscribe(@RequestParam(name = "t", required = false) @Nullable String token) {
    Long userId = tokens.verify(token);
    if (userId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid unsubscribe link");
    }
    AppUser u = accounts.findById(userId);
    // a deleted account has nothing left to turn off
    if (u != null && !"OFF".equals(u.digestFrequency())) {
      accounts.updateSettings(userId, u.displayName(), u.timeZone(), "OFF", u.digestWeekday(), u.digestHour(), null);
      LOG.info("Digests of user {} turned off by unsubscribe link", userId);
    }
    return Map.of();
  }
}
