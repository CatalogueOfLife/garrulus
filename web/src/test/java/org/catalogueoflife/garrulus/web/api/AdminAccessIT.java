package org.catalogueoflife.garrulus.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.catalogueoflife.garrulus.web.WebApplication;
import org.catalogueoflife.garrulus.web.auth.AbstractAuthIT;
import org.catalogueoflife.garrulus.web.auth.Browser;
import org.catalogueoflife.garrulus.web.auth.TestSignIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

/**
 * Who may call {@code /api/admin/**} (ADR-0006, amendment M6b): the {@code X-Admin-Token} header (scripts, no XSRF)
 * or a session with ROLE_ADMIN of an account whose ORCID iD is listed in {@code garrulus.admin.orcids} (browser,
 * XSRF like every session write). The list is read per request, so a delisted iD loses admin after a restart even
 * with a live session.
 */
@TestPropertySource(properties = "garrulus.admin.orcids=https://orcid.org/" + AdminAccessIT.LISTED + ",not-an-orcid")
class AdminAccessIT extends AbstractAuthIT {

  static final String LISTED = "0000-0003-0000-000X";
  static final String TOKEN = "test-token";
  static final String TASK = "{\"type\":\"PING\",\"payload\":{}}";

  long source;

  @BeforeEach
  void fixtures() {
    jdbc.update("DELETE FROM app_user WHERE orcid = ?", LISTED);
    source = jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin, active) "
        + "VALUES (?, 'Admin access journal', 'CANDIDATE', 'MANUAL', true) RETURNING id", Long.class,
        "S9" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
  }

  /** The account of the listed iD (fresh for every test), optionally with a verified address. */
  long listedUser(String email) {
    return email == null ? orcidUser(LISTED) : emailUser(email, LISTED);
  }

  String patchPath() {
    return "/api/admin/sources/" + source;
  }

  static Map<String, String> token(String value) {
    return Map.of("X-Admin-Token", value);
  }

  static Map<String, String> xsrf(Browser b) throws Exception {
    return Map.of("X-XSRF-TOKEN", b.xsrf());
  }

  @Test
  void tokenStillWorksWithoutXsrf() throws Exception {
    // no cookies at all, no XSRF token: the header alone
    Browser script = browser();
    assertThat(script.json("GET", "/api/admin/status", null, token(TOKEN)).statusCode()).isEqualTo(200);
    assertThat(script.json("PATCH", patchPath(), "{\"tier\":\"CANDIDATE\"}", token(TOKEN)).statusCode())
        .isEqualTo(200);
    assertThat(script.json("POST", "/api/admin/tasks", TASK, token(TOKEN)).statusCode()).isEqualTo(202);
    // a wrong token is a 401 for writes too, never a CSRF 403
    assertThat(script.json("POST", "/api/admin/tasks", TASK, token("nope")).statusCode()).isEqualTo(401);
    assertThat(script.json("POST", "/api/admin/tasks", TASK, token("")).statusCode()).isEqualTo(401);
  }

  @Test
  void wrongTokenIs401EvenWithAdminSession() throws Exception {
    Browser admin = signedInAsAdmin(listedUser(null));
    assertThat(admin.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(200);
    // a header that is sent is checked alone: no fall-back to the session
    assertThat(admin.json("GET", "/api/admin/status", null, token("nope")).statusCode()).isEqualTo(401);
    assertThat(admin.json("PATCH", patchPath(), "{\"tier\":\"CANDIDATE\"}",
        Map.of("X-Admin-Token", "nope", "X-XSRF-TOKEN", admin.xsrf())).statusCode()).isEqualTo(401);
  }

  @Test
  void adminOrcidSessionReadsStatus() throws Exception {
    Browser admin = signedInAsAdmin(listedUser(null));
    HttpResponse<String> status = admin.json("GET", "/api/admin/status", null, Map.of());
    assertThat(status.statusCode()).isEqualTo(200);
    assertThat(status.body()).contains("\"tasks\"");
    assertThat(admin.json("GET", "/api/admin/sources", null, Map.of()).statusCode()).isEqualTo(200);
    assertThat(admin.json("GET", patchPath() + "/routes", null, Map.of()).statusCode()).isEqualTo(200);
    // the admin paths issue the XSRF cookie the SPA echoes on its writes
    assertThat(admin.cookie("XSRF-TOKEN")).isPresent();
  }

  @Test
  void emailSignInOfListedOrcidAccountIsNotAdmin() throws Exception {
    String email = "admin-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    long id = listedUser(email);
    Browser b = browser();
    HttpResponse<String> login = b.signIn(email, mail);
    assertThat(userId(login.body())).isEqualTo(id);
    assertThat(b.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(403);
    assertThat(b.get("/api/me").body()).contains("\"admin\":false");
  }

  @Test
  void otherAccountGets403OnEveryAdminPath() throws Exception {
    // a session claiming ROLE_ADMIN for an unlisted iD, and the listed account signed in without the role
    for (Browser b : new Browser[] {signedInAsAdmin(orcidUser(orcid())), signedInAs(listedUser(null))}) {
      Map<String, String> xsrf = xsrf(b);
      assertThat(b.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(403);
      assertThat(b.json("GET", "/api/admin/sources", null, Map.of()).statusCode()).isEqualTo(403);
      assertThat(b.json("GET", patchPath() + "/routes", null, Map.of()).statusCode()).isEqualTo(403);
      assertThat(b.json("PATCH", patchPath(), "{\"tier\":\"CORE\"}", xsrf).statusCode()).isEqualTo(403);
      assertThat(b.json("POST", "/api/admin/tasks", TASK, xsrf).statusCode()).isEqualTo(403);
      assertThat(b.json("POST", "/api/admin/sources", "{\"openalexId\":\"S1\",\"tier\":\"CORE\"}", xsrf)
          .statusCode()).isEqualTo(403);
      assertThat(b.json("POST", "/api/admin/digests/run", "{\"userId\":1}", xsrf).statusCode()).isEqualTo(403);
    }
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, source))
        .isEqualTo("CANDIDATE");
  }

  @Test
  void signedOutIs401() throws Exception {
    Browser b = browser();
    assertThat(b.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(401);
    assertThat(b.json("GET", "/api/admin/sources", null, Map.of()).statusCode()).isEqualTo(401);
    // a write with a valid XSRF token but no session
    assertThat(b.json("POST", "/api/admin/tasks", TASK, xsrf(b)).statusCode()).isEqualTo(401);
  }

  @Test
  void adminSessionWriteNeedsXsrf() throws Exception {
    Browser admin = signedInAsAdmin(listedUser(null));
    assertThat(admin.json("PATCH", patchPath(), "{\"tier\":\"BROAD\",\"active\":false}", Map.of()).statusCode())
        .isEqualTo(403);
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, source))
        .isEqualTo("CANDIDATE");
    assertThat(admin.json("PATCH", patchPath(), "{\"tier\":\"BROAD\",\"active\":false}", xsrf(admin)).statusCode())
        .isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, source))
        .isEqualTo("BROAD");
  }

  @Test
  void meReportsAdminOnlyForListedAdminSession() throws Exception {
    assertThat(signedInAsAdmin(listedUser(null)).get("/api/me").body()).contains("\"admin\":true");
    assertThat(signedInAsAdmin(orcidUser(orcid())).get("/api/me").body()).contains("\"admin\":false");
  }

  /**
   * Review Focus 1: the same web app restarted with the iD taken off the list (the token stays set, so the endpoints
   * stay mapped). The session, kept in Postgres, still carries ROLE_ADMIN, but the list is checked per request.
   */
  @Nested
  @TestPropertySource(properties = "garrulus.admin.orcids=")
  class Delisted {

    @LocalServerPort int restartedPort;

    @Test
    void removedOrcidLosesAdminAfterRestart() throws Exception {
      // the app with the iD listed: the outer class's context, from the test context cache (a nested test's outer
      // instance is injected from the nested context, so its port is not the listed app's)
      int listedPort = new TestContextManager(AdminAccessIT.class).getTestContext().getApplicationContext()
          .getEnvironment().getRequiredProperty("local.server.port", Integer.class);
      assertThat(restartedPort).isNotEqualTo(listedPort);
      Browser admin = signedInAs(listedPort, listedUser(null), true);
      assertThat(admin.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(200);
      assertThat(admin.get("/api/me").body()).contains("\"admin\":true");

      // the same session (kept in Postgres) on the app restarted without the iD
      Browser sameSession = new Browser(restartedPort);
      sameSession.copyCookiesFrom(admin);
      HttpResponse<String> me = sameSession.get("/api/me");
      assertThat(me.statusCode()).isEqualTo(200);
      assertThat(me.body()).contains("\"admin\":false");
      assertThat(sameSession.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(403);
      assertThat(sameSession.json("PATCH", patchPath(), "{\"tier\":\"CORE\"}", xsrf(sameSession)).statusCode())
          .isEqualTo(403);
      assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, source))
          .isEqualTo("CANDIDATE");
      // the token still works there
      assertThat(sameSession.json("GET", "/api/admin/status", null, token(TOKEN)).statusCode()).isEqualTo(200);
    }
  }

  /** Admin ORCID iDs alone map the admin endpoints: the session works, any token header is a 401. */
  @Nested
  @SpringBootTest(classes = WebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {"garrulus.admin.token=", "garrulus.digest.cron=-",
          "garrulus.admin.orcids=" + AdminAccessIT.LISTED})
  @Import(TestSignIn.class)
  class WithoutToken {

    @LocalServerPort int noTokenPort;

    @Test
    void adminEndpointsMappedWithOrcidsButNoToken() throws Exception {
      Browser signedOut = new Browser(noTokenPort);
      assertThat(signedOut.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(401);
      assertThat(signedOut.json("GET", "/api/admin/status", null, token(TOKEN)).statusCode()).isEqualTo(401);
      assertThat(signedOut.json("GET", "/api/admin/status", null, token("")).statusCode()).isEqualTo(401);
      assertThat(signedOut.json("POST", "/api/admin/tasks", TASK, token(TOKEN)).statusCode()).isEqualTo(401);

      Browser admin = signedInAs(noTokenPort, listedUser(null), true);
      assertThat(admin.json("GET", "/api/admin/status", null, Map.of()).statusCode()).isEqualTo(200);
      assertThat(admin.json("POST", "/api/admin/tasks", TASK, xsrf(admin)).statusCode()).isEqualTo(202);
    }
  }
}
