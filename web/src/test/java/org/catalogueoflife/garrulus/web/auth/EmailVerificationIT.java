package org.catalogueoflife.garrulus.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EmailVerificationIT extends AbstractAuthIT {

  static String sha256(String token) throws Exception {
    return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
  }

  HttpResponse<String> requestAddress(Browser b, String email) throws Exception {
    return b.json("POST", "/api/me/email", "{\"email\":\"" + email + "\"}", true);
  }

  HttpResponse<String> verify(Browser b, String token) throws Exception {
    return b.json("POST", "/api/auth/verify-email", "{\"token\":\"" + token + "\"}", true);
  }

  @Test
  void verifySetsUnusedAddress() throws Exception {
    long id = orcidUser(orcid());
    Browser b = signedInAs(id);
    String email = SignInLinkIT.address("new");

    HttpResponse<String> requested = requestAddress(b, email);
    assertThat(requested.statusCode()).isEqualTo(202);
    assertThat(requested.body()).isEqualTo("{}");
    // nothing is stored on the account before the link is used
    assertThat(jdbc.queryForObject("select email from app_user where id = ?", String.class, id)).isNull();

    List<MimeMessage> mails = mail.to(email);
    assertThat(mails).hasSize(1);
    assertThat(mails.getFirst().getSubject()).contains("Garrulus");
    String text = CapturingMailSender.plainText(mails.getFirst());
    assertThat(text).contains("http://localhost:5173/verify-email?token=");
    assertThat(CapturingMailSender.html(mails.getFirst())).contains("/verify-email?token=");
    String token = mail.lastTokenFor(email);
    // 32 random bytes, URL-safe base64 without padding
    assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    // only the SHA-256 of the token is stored
    Map<String, Object> row = jdbc.queryForMap("select user_id, email, token_hash from email_token where user_id = ?",
        id);
    assertThat(row.get("token_hash")).isEqualTo(sha256(token));
    assertThat(row.get("email")).isEqualTo(email);

    HttpResponse<String> verified = verify(b, token);
    assertThat(verified.statusCode()).isEqualTo(200);
    assertThat(verified.body()).isEqualTo("{\"outcome\":\"SET\"}");
    Map<String, Object> user = jdbc.queryForMap(
        "select email, email_verified, digest_since from app_user where id = ?", id);
    assertThat(user.get("email")).isEqualTo(email);
    assertThat(user.get("email_verified")).isNotNull();
    assertThat(user.get("digest_since")).isNotNull();
    assertThat(b.get("/api/me").body()).contains("\"email\":\"" + email + "\"");

    // single use
    assertThat(verify(b, token).statusCode()).isEqualTo(400);
  }

  @Test
  void verifyNormalisesAddress() throws Exception {
    long id = orcidUser(orcid());
    Browser b = signedInAs(id);
    String email = SignInLinkIT.address("norm");

    assertThat(requestAddress(b, "  " + email.toUpperCase() + " ").statusCode()).isEqualTo(202);
    assertThat(mail.to(email)).hasSize(1);
    assertThat(verify(b, mail.lastTokenFor(email)).body()).isEqualTo("{\"outcome\":\"SET\"}");
    assertThat(jdbc.queryForObject("select email from app_user where id = ?", String.class, id)).isEqualTo(email);

    // invalid addresses are a 400 and send nothing; signed out is a 401
    int before = mail.sent().size();
    for (String bad : List.of("no-at-sign", "a@b@example.org", "@example.org", "")) {
      assertThat(requestAddress(b, bad).statusCode()).as(bad).isEqualTo(400);
    }
    assertThat(requestAddress(browser(), SignInLinkIT.address("anon")).statusCode()).isEqualTo(401);
    assertThat(mail.sent()).hasSize(before);
  }

  @Test
  void tokenExpiresAfter24Hours() throws Exception {
    long id = orcidUser(orcid());
    Browser b = signedInAs(id);
    String email = SignInLinkIT.address("late");
    requestAddress(b, email);
    String token = mail.lastTokenFor(email);

    Instant expires = jdbc.queryForObject("select expires from email_token where token_hash = ?", Instant.class,
        sha256(token));
    assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofHours(24).minusMinutes(1),
        Duration.ofHours(24));

    // 24 hours and a second later
    jdbc.update("update email_token set expires = expires - interval '24 hours 1 second' where token_hash = ?",
        sha256(token));
    HttpResponse<String> late = verify(b, token);
    assertThat(late.statusCode()).isEqualTo(400);
    assertThat(late.body()).doesNotContain(email);
    assertThat(jdbc.queryForObject("select email from app_user where id = ?", String.class, id)).isNull();
  }

  @Test
  void requestAnswerIdenticalForUsedAndUnusedAddress() throws Exception {
    String used = SignInLinkIT.address("used");
    emailUser(used, orcid());
    String unused = SignInLinkIT.address("unused");
    Browser b = signedInAs(orcidUser(orcid()));

    HttpResponse<String> a = requestAddress(b, used);
    HttpResponse<String> c = requestAddress(b, unused);
    assertThat(a.statusCode()).isEqualTo(c.statusCode()).isEqualTo(202);
    assertThat(a.body()).isEqualTo(c.body()).isEqualTo("{}");
    // both get their link; the old address of the account is never written to
    assertThat(mail.to(used)).hasSize(1);
    assertThat(mail.to(unused)).hasSize(1);
    assertThat(mail.sent()).hasSize(2);
  }

  @Test
  void verifyNeedsTheRequestersSession() throws Exception {
    long id = orcidUser(orcid());
    Browser b = signedInAs(id);
    String email = SignInLinkIT.address("mine");
    requestAddress(b, email);
    String token = mail.lastTokenFor(email);

    // signed out, or another account: 401, and the token stays usable
    assertThat(verify(browser(), token).statusCode()).isEqualTo(401);
    Browser other = signedInAs(orcidUser(orcid()));
    assertThat(verify(other, token).statusCode()).isEqualTo(401);
    // CSRF is required
    assertThat(b.json("POST", "/api/auth/verify-email", "{\"token\":\"" + token + "\"}", false).statusCode())
        .isEqualTo(403);
    assertThat(jdbc.queryForObject("select count(*) from email_token where token_hash = ?", Long.class,
        sha256(token))).isEqualTo(1);

    assertThat(verify(b, token).body()).isEqualTo("{\"outcome\":\"SET\"}");
    // an unknown token is a 400
    assertThat(verify(b, "nope").statusCode()).isEqualTo(400);
  }
}
