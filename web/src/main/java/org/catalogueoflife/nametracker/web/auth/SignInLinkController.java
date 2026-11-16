package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;
import org.catalogueoflife.nametracker.web.mail.MailService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Email sign-in links and what the SPA needs to know to offer sign-in. */
@RestController
public class SignInLinkController {

  static final Duration TOKEN_VALIDITY = Duration.ofMinutes(15);
  private static final Logger LOG = LoggerFactory.getLogger(SignInLinkController.class);

  public record LinkRequest(@Nullable String email) {}

  private final AccountService accounts;
  private final SignInRateLimiter limiter;
  private final OneTimeTokenService tokens;
  private final MailService mail;
  private final String publicUrl;
  private final boolean orcid;

  public SignInLinkController(AccountService accounts, SignInRateLimiter limiter, OneTimeTokenService tokens,
      MailService mail, @Value("${nametracker.public-url}") String publicUrl,
      @Value("${nametracker.orcid.client-id:}") String orcidClientId) {
    this.accounts = accounts;
    this.limiter = limiter;
    this.tokens = tokens;
    this.mail = mail;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
    this.orcid = !orcidClientId.isBlank();
  }

  /**
   * Mails a sign-in link (one-time token, 15 minutes) to the address. Always {@code 202 {}}: whether the address has
   * an account, and whether the rate limit refused the request, is never told. An invalid address is a 400.
   */
  @PostMapping("/api/auth/link")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> link(@RequestBody LinkRequest body, HttpServletRequest request) {
    String email = AccountService.normaliseEmail(body.email());
    Long userId = accounts.findIdByEmail(email);
    String who = userId == null ? "unknown address" : "user " + userId;
    if (!limiter.tryAcquire(email, request.getRemoteAddr())) {
      LOG.info("sign-in link for {} refused by the rate limit", who);
      return Map.of();
    }
    OneTimeToken token = tokens.generate(new GenerateOneTimeTokenRequest(email, TOKEN_VALIDITY));
    mail.sendSignInLink(email, userId, publicUrl + "/signin/verify?token=" + token.getTokenValue());
    LOG.info("sign-in link requested for {}", who);
    return Map.of();
  }

  /** {@code {"orcid": bool}}: whether "Continue with ORCID" is offered (an ORCID client id is configured). */
  @GetMapping("/api/auth/config")
  public Map<String, Boolean> config() {
    return Map.of("orcid", orcid);
  }
}
