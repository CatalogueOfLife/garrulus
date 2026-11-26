package org.catalogueoflife.garrulus.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SignInLinkIT extends AbstractAuthIT {

  static String address(String who) {
    return who + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
  }

  @Test
  void linkSentAndTokenSignsIn() throws Exception {
    String email = address("alice");
    Browser b = browser();

    HttpResponse<String> link = b.json("POST", "/api/auth/link", "{\"email\":\"" + email + "\"}", false);
    assertThat(link.statusCode()).isEqualTo(202);
    assertThat(link.body()).isEqualTo("{}");

    List<MimeMessage> mails = mail.to(email);
    assertThat(mails).hasSize(1);
    assertThat(mails.getFirst().getSubject()).contains("Garrulus");
    assertThat(mails.getFirst().getFrom()[0].toString()).contains("Garrulus");
    String text = CapturingMailSender.plainText(mails.getFirst());
    assertThat(text).contains("http://localhost:5173/signin/verify?token=");
    assertThat(CapturingMailSender.html(mails.getFirst())).contains("/signin/verify?token=");
    String token = mail.lastTokenFor(email);

    HttpResponse<String> login = b.form("/login/ott", "token", token, true);
    assertThat(login.statusCode()).isEqualTo(200);
    assertThat(login.headers().firstValue("Location")).isEmpty();
    assertThat(login.body()).matches("\\{\\s*\"userId\"\\s*:\\s*\\d+\\s*}");
    long userId = userId(login.body());

    HttpResponse<String> me = b.get("/api/me");
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.body()).contains("\"id\":" + userId).contains("\"email\":\"" + email + "\"");

    Map<String, Object> row = jdbc.queryForMap(
        "select email, email_verified, digest_since, last_login from app_user where id = ?", userId);
    assertThat(row.get("email")).isEqualTo(email);
    assertThat(row.get("email_verified")).isNotNull();
    assertThat(row.get("digest_since")).isNotNull();
    assertThat(row.get("last_login")).isNotNull();
  }

  @Test
  void sameAnswerForUnknownAndRateLimited() throws Exception {
    String unknown = address("nobody");
    String known = address("known");
    long id = jdbc.queryForObject("insert into app_user (email, email_verified) values (?, now()) returning id",
        Long.class, known);
    Browser b = browser();

    HttpResponse<String> first = b.json("POST", "/api/auth/link", "{\"email\":\"" + unknown + "\"}", false);
    List<HttpResponse<String>> knownAnswers = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      knownAnswers.add(b.json("POST", "/api/auth/link", "{\"email\":\"" + known + "\"}", false));
    }

    for (HttpResponse<String> r : List.of(first, knownAnswers.get(0), knownAnswers.get(3))) {
      assertThat(r.statusCode()).isEqualTo(202);
      assertThat(r.body()).isEqualTo("{}");
    }
    assertThat(mail.to(unknown)).hasSize(1);
    // 3 per address per 15 minutes: the fourth request is refused, answered alike and sends nothing
    assertThat(mail.to(known)).hasSize(3);
    assertThat(id).isPositive();
    // no session is opened for a link request
    assertThat(b.cookie("SESSION")).isEmpty();
  }

  @Test
  void tokenSingleUse() throws Exception {
    String email = address("once");
    Browser b = browser();
    b.json("POST", "/api/auth/link", "{\"email\":\"" + email + "\"}", false);
    String token = mail.lastTokenFor(email);

    assertThat(b.form("/login/ott", "token", token, true).statusCode()).isEqualTo(200);

    Browser other = browser();
    HttpResponse<String> again = other.form("/login/ott", "token", token, true);
    assertThat(again.statusCode()).isEqualTo(401);
    assertThat(again.headers().firstValue("Location")).isEmpty();
    assertThat(other.get("/api/me").statusCode()).isEqualTo(401);
  }

  @Test
  void tokenExpiresAfter15Minutes() throws Exception {
    String email = address("late");
    Browser b = browser();
    b.json("POST", "/api/auth/link", "{\"email\":\"" + email + "\"}", false);
    String token = mail.lastTokenFor(email);

    // JdbcOneTimeTokenService writes expires_at as a local timestamp of this JVM
    LocalDateTime expires = jdbc.queryForObject(
        "select expires_at from one_time_tokens where token_value = ?", LocalDateTime.class, token);
    Duration left = Duration.between(LocalDateTime.now(), expires);
    assertThat(left).isBetween(Duration.ofMinutes(14), Duration.ofMinutes(15));

    // 15 minutes and a second later
    jdbc.update("update one_time_tokens set expires_at = expires_at - interval '15 minutes 1 second' "
        + "where token_value = ?", token);
    HttpResponse<String> login = b.form("/login/ott", "token", token, true);
    assertThat(login.statusCode()).isEqualTo(401);
    assertThat(jdbc.queryForObject("select count(*) from app_user where lower(email) = ?", Long.class, email))
        .isZero();
  }

  @Test
  void emailIsNormalised() throws Exception {
    String email = address("carol");
    Browser b = browser();

    HttpResponse<String> link = b.json("POST", "/api/auth/link",
        "{\"email\":\"  " + email.toUpperCase() + " \"}", false);
    assertThat(link.statusCode()).isEqualTo(202);
    assertThat(mail.to(email)).hasSize(1);
    HttpResponse<String> login = b.form("/login/ott", "token", mail.lastTokenFor(email), true);
    long userId = userId(login.body());
    assertThat(jdbc.queryForObject("select email from app_user where id = ?", String.class, userId)).isEqualTo(email);

    // the same address in other case signs into the same account
    Browser again = browser();
    HttpResponse<String> second = again.signIn(email.replace("carol", "CAROL"), mail);
    assertThat(userId(second.body())).isEqualTo(userId);

    // invalid addresses are a 400 and send nothing
    int before = mail.sent().size();
    for (String bad : List.of("no-at-sign", "two@@example.org", "a@b@example.org", "@example.org", "x@",
        "a".repeat(250) + "@example.org", "")) {
      assertThat(b.json("POST", "/api/auth/link", "{\"email\":\"" + bad + "\"}", false).statusCode())
          .as(bad).isEqualTo(400);
    }
    assertThat(b.json("POST", "/api/auth/link", "{}", false).statusCode()).isEqualTo(400);
    assertThat(mail.sent()).hasSize(before);
  }

  @Test
  void sessionStoredInDatabase() throws Exception {
    Browser b = browser();
    HttpResponse<String> login = b.signIn(address("dora"), mail);
    long userId = userId(login.body());

    assertThat(b.cookie("SESSION")).isPresent();
    assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
        String.valueOf(userId))).isEqualTo(1);
    // only account ids, never addresses, name a session
    assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name like '%@%'",
        Long.class)).isZero();
    // about 30 days
    assertThat(jdbc.queryForObject("select max_inactive_interval from spring_session where principal_name = ?",
        Integer.class, String.valueOf(userId))).isEqualTo(30 * 24 * 3600);
    String setCookie = login.headers().allValues("Set-Cookie").stream()
        .filter(c -> c.startsWith("SESSION=")).findFirst().orElseThrow();
    assertThat(setCookie).contains("HttpOnly").containsIgnoringCase("SameSite=Lax");

    // a fresh browser with only the session cookie is signed in: the session lives in Postgres
    String sessionCookie = "SESSION=" + b.cookie("SESSION").orElseThrow();
    assertThat(getWithCookie("/api/me", sessionCookie).statusCode()).isEqualTo(200);

    // sign out removes it
    assertThat(b.json("POST", "/api/auth/logout", "", true).statusCode()).isEqualTo(204);
    assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Long.class,
        String.valueOf(userId))).isZero();
    assertThat(getWithCookie("/api/me", sessionCookie).statusCode()).isEqualTo(401);
  }

  @Test
  void loginNeedsCsrf() throws Exception {
    String email = address("henry");
    Browser b = browser();
    b.json("POST", "/api/auth/link", "{\"email\":\"" + email + "\"}", false);
    String token = mail.lastTokenFor(email);

    b.xsrf();
    HttpResponse<String> noHeader = b.form("/login/ott", "token", token, false);
    assertThat(noHeader.statusCode()).isEqualTo(403);
    assertThat(b.cookie("SESSION")).isEmpty();
    // refused before the token was looked at: it still works
    assertThat(b.form("/login/ott", "token", token, true).statusCode()).isEqualTo(200);
  }

  @Test
  void sessionIdAndCsrfTokenChangeOnLogin() throws Exception {
    String email = address("ines");
    Browser b = browser();
    long userId = userId(b.signIn(email, mail).body());
    String firstSession = b.cookie("SESSION").orElseThrow();
    String firstXsrf = b.xsrf();

    // signing in again from a browser that has a session: a new session id (no fixation), a new CSRF token
    assertThat(userId(b.signIn(email, mail).body())).isEqualTo(userId);
    String secondSession = b.cookie("SESSION").orElseThrow();
    assertThat(secondSession).isNotEqualTo(firstSession);
    assertThat(getWithCookie("/api/me", "SESSION=" + firstSession).statusCode()).isEqualTo(401);
    assertThat(getWithCookie("/api/me", "SESSION=" + secondSession).statusCode()).isEqualTo(200);

    String secondXsrf = b.cookie("XSRF-TOKEN").orElseThrow();
    assertThat(secondXsrf).isNotEqualTo(firstXsrf);
    assertThat(b.send("PATCH", "/api/me", "application/json", "{\"digestHour\":6}", firstXsrf).statusCode())
        .isEqualTo(403);
    assertThat(b.send("PATCH", "/api/me", "application/json", "{\"digestHour\":6}", secondXsrf).statusCode())
        .isEqualTo(200);
  }

  @Test
  void failedAccountStepLeavesNoSession() throws Exception {
    // a token whose username is no address: the provider refuses it before any principal exists
    String token = UUID.randomUUID().toString();
    jdbc.update("insert into one_time_tokens (token_value, username, expires_at) "
        + "values (?, 'not-an-address', localtimestamp + interval '1 hour')", token);
    long sessions = jdbc.queryForObject("select count(*) from spring_session", Long.class);
    Browser b = browser();

    HttpResponse<String> login = b.form("/login/ott", "token", token, true);
    assertThat(login.statusCode()).isEqualTo(401);
    assertThat(b.cookie("SESSION")).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from spring_session", Long.class)).isEqualTo(sessions);
    assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name like '%@%' "
        + "or principal_name = 'not-an-address'", Long.class)).isZero();
  }

  HttpResponse<String> getWithCookie(String path, String cookie) throws Exception {
    return java.net.http.HttpClient.newHttpClient().send(
        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
            .header("Cookie", cookie).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void ottGenerateEndpointNotExposed() throws Exception {
    String email = address("eve");
    Browser b = browser();
    long tokens = jdbc.queryForObject("select count(*) from one_time_tokens", Long.class);

    for (HttpResponse<String> r : List.of(
        b.form("/ott/generate", "username", email, true),
        b.form("/ott/generate", "username", email, false),
        b.get("/ott/generate"),
        b.get("/login/ott"),
        b.get("/login"))) {
      assertThat(r.statusCode()).as(r.request().method() + " " + r.uri()).isIn(401, 403, 404, 405);
      assertThat(r.headers().firstValue("Location")).isEmpty();
      assertThat(r.body()).doesNotContainIgnoringCase("<form");
    }
    assertThat(jdbc.queryForObject("select count(*) from one_time_tokens", Long.class)).isEqualTo(tokens);
    assertThat(mail.to(email)).isEmpty();
  }
}
