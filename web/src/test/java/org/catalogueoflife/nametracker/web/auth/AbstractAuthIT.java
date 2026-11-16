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
@Import(CapturingMailSender.Config.class)
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

  static long userId(String loginBody) {
    return Long.parseLong(loginBody.replaceAll("\\D", ""));
  }
}
