package org.catalogueoflife.nametracker.web.auth;

import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Shared context of the sign-in ITs: a capturing mail sender, ORCID pinned off whatever the local .env says, and a
 * fresh rate limiter per test (all requests come from 127.0.0.1, so the 20-per-IP limit would otherwise carry over).
 */
@Import({CapturingMailSender.Config.class, TestSignIn.class})
@TestPropertySource(properties = "nametracker.orcid.client-id=")
abstract class AbstractAuthIT extends AbstractPostgresIT {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired CapturingMailSender mail;
  @Autowired SignInRateLimiter limiter;

  @BeforeEach
  void resetMailAndLimits() {
    mail.clear();
    limiter.clear();
  }

  Browser browser() {
    return new Browser(port);
  }

  /** An account with this ORCID iD and no address. */
  long orcidUser(String orcid) {
    return jdbc.queryForObject("insert into app_user (orcid, display_name) values (?, 'Ada') returning id",
        Long.class, orcid);
  }

  /** An account with this verified address (and ORCID iD, if not null). */
  long emailUser(String email, String orcid) {
    return jdbc.queryForObject("insert into app_user (email, email_verified, digest_since, orcid) "
        + "values (?, now(), now(), ?) returning id", Long.class, email, orcid);
  }

  /** A browser with a real session of the account (TestSignIn). */
  Browser signedInAs(long userId) throws Exception {
    Browser b = browser();
    if (b.get("/login/test-as?userId=" + userId).statusCode() != 204) {
      throw new AssertionError("test sign-in failed");
    }
    return b;
  }

  static String orcid() {
    long n = java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000_000_000_000L, 99_999_999_999_999_999L);
    String d = Long.toString(n).substring(0, 16);
    return d.substring(0, 4) + "-" + d.substring(4, 8) + "-" + d.substring(8, 12) + "-" + d.substring(12);
  }

  static long userId(String loginBody) {
    return Long.parseLong(loginBody.replaceAll("\\D", ""));
  }
}
