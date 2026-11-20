package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.catalogueoflife.nametracker.store.account.EmailTokenMapper;
import org.catalogueoflife.nametracker.web.mail.MailService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Confirms a new address before it is stored ({@code VERIFY_EMAIL}): a link with a random token (32 bytes, URL-safe
 * base64) goes to the new address, never to the old one; only its SHA-256 is stored, for 24 hours. Using the link
 * from the session that asked for it applies the address ({@link AccountMerger}).
 */
@Service
public class EmailVerificationService {

  static final Duration TOKEN_VALIDITY = Duration.ofHours(24);
  private static final Logger LOG = LoggerFactory.getLogger(EmailVerificationService.class);

  private final EmailTokenMapper tokens;
  private final AccountMerger merger;
  private final CurrentUser currentUser;
  private final SignInRateLimiter limiter;
  private final MailService mail;
  private final Clock clock;
  private final String publicUrl;
  private final SecureRandom random = new SecureRandom();

  public EmailVerificationService(EmailTokenMapper tokens, AccountMerger merger, CurrentUser currentUser,
      SignInRateLimiter limiter, MailService mail, Clock clock, @Value("${nametracker.public-url}") String publicUrl) {
    this.tokens = tokens;
    this.merger = merger;
    this.currentUser = currentUser;
    this.limiter = limiter;
    this.mail = mail;
    this.clock = clock;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
  }

  /**
   * Mails a verification link for the address to the address. Whether the address is in use is never looked at
   * here; a request over the sign-in rate limits (per address and client IP) is dropped silently. An invalid address
   * is a 400.
   */
  public void request(long userId, @Nullable String rawEmail, String clientIp) {
    String email = AccountService.normaliseEmail(rawEmail);
    if (!limiter.tryAcquire(email, clientIp)) {
      LOG.info("verification link for user {} refused by the rate limit", userId);
      return;
    }
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokens.insert(userId, email, sha256(token), clock.instant().plus(TOKEN_VALIDITY));
    mail.sendVerifyEmail(email, userId, publicUrl + "/verify-email?token=" + token);
    LOG.info("address verification requested by user {}", userId);
  }

  /**
   * Applies the token of the signed-in account. After a merge the current session belongs to the survivor, under a
   * new session id.
   */
  public AccountMerger.Outcome verify(long userId, @Nullable String token, HttpServletRequest request,
      HttpServletResponse response) {
    if (token == null || token.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or expired link");
    }
    HttpSession session = request.getSession(false);
    AccountMerger.Result result = merger.apply(userId, sha256(token.strip()), session == null ? null : session.getId());
    if (result.outcome() == AccountMerger.Outcome.MERGED) {
      if (session != null) {
        request.changeSessionId();
      }
      // an email path never grants admin: an admin signs in through ORCID again after a merge
      currentUser.signIn(result.userId(), false, request, response);
    }
    return result.outcome();
  }

  static String sha256(String token) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
