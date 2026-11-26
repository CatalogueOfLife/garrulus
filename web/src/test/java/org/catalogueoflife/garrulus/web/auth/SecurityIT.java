package org.catalogueoflife.garrulus.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecurityIT extends AbstractAuthIT {

  HttpResponse<String> plainGet(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void publicAndAdminUnchanged() throws Exception {
    for (String path : new String[] {"/api/vocab/taxgroup", "/api/sources", "/api/works", "/api/ping",
        "/api/openapi.yaml", "/actuator/health", "/actuator/info"}) {
      HttpResponse<String> r = plainGet(path);
      assertThat(r.statusCode()).as(path).isEqualTo(200);
      // public reads set no cookie at all (no session, no XSRF-TOKEN), so caches can keep them
      assertThat(r.headers().allValues("Set-Cookie")).as(path).isEmpty();
    }
    // nor do the portal's taxon calls, found or not
    assertThat(plainGet("/api/taxa/NOPE/stats").headers().allValues("Set-Cookie")).isEmpty();
    // public reads carry no cache-busting headers, so Varnish and browsers may keep them
    for (String path : new String[] {"/api/works", "/api/taxa/NOPE/works", "/api/sources", "/api/openapi.yaml"}) {
      HttpResponse<String> r = plainGet(path);
      assertThat(r.statusCode()).as(path).isEqualTo(200);
      assertThat(r.headers().firstValue("Cache-Control")).as(path).isEmpty();
      assertThat(r.headers().firstValue("Pragma")).as(path).isEmpty();
      assertThat(r.headers().firstValue("Expires")).as(path).isEmpty();
    }
    // a missing public resource is still a 404, not a 401
    assertThat(plainGet("/api/works/999999999").statusCode()).isEqualTo(404);

    // admin: header token, no cookie, no CSRF token
    HttpResponse<String> admin = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/tasks"))
            .header("Content-Type", "application/json")
            .header("X-Admin-Token", "test-token")
            .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"PING\",\"payload\":{}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(admin.statusCode()).isEqualTo(202);
  }

  @Test
  void meWithoutSessionIs401() throws Exception {
    HttpResponse<String> r = plainGet("/api/me");
    assertThat(r.statusCode()).isEqualTo(401);
    assertThat(r.headers().firstValue("Location")).isEmpty();
    assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("json"));
    assertThat(r.body()).contains("\"status\":401");
    // a signed-out SPA load must not create a session (no saved request)
    assertThat(r.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));

    Browser b = browser();
    assertThat(b.json("PATCH", "/api/me", "{\"digestHour\":5}", true).statusCode()).isEqualTo(401);
  }

  @Test
  void patchMeNeedsCsrf() throws Exception {
    Browser b = browser();
    long userId = userId(b.signIn("frank-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org", mail).body());

    assertThat(b.json("PATCH", "/api/me", "{\"digestHour\":5}", false).statusCode()).isEqualTo(403);
    assertThat(jdbc.queryForObject("select digest_hour from app_user where id = ?", Integer.class, userId))
        .isEqualTo(7);

    HttpResponse<String> ok = b.json("PATCH", "/api/me",
        "{\"digestHour\":5,\"digestWeekday\":3,\"timeZone\":\"Europe/Berlin\",\"digestFrequency\":\"WEEKLY\","
            + "\"displayName\":\"Frank\"}", true);
    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body()).contains("\"digestHour\":5").contains("\"timeZone\":\"Europe/Berlin\"")
        .contains("\"digestFrequency\":\"WEEKLY\"").contains("\"displayName\":\"Frank\"");
    HttpResponse<String> me = b.get("/api/me");
    assertThat(me.body()).contains("\"digestWeekday\":3");
    // created (ISO instant): the SPA proposes the browser's time zone to new accounts
    Instant created = jdbc.queryForObject("select created from app_user where id = ?", Timestamp.class, userId)
        .toInstant();
    assertThat(me.body()).contains("\"created\":\"" + created + "\"");
  }

  @Test
  void patchMeValidatesAndRestartsDigest() throws Exception {
    Browser b = browser();
    long userId = userId(b.signIn("gina-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org", mail).body());

    for (String bad : new String[] {"{\"timeZone\":\"Mars/Olympus\"}", "{\"digestHour\":24}",
        "{\"digestHour\":-1}", "{\"digestWeekday\":0}", "{\"digestWeekday\":8}", "{\"digestFrequency\":\"HOURLY\"}"}) {
      assertThat(b.json("PATCH", "/api/me", bad, true).statusCode()).as(bad).isEqualTo(400);
    }

    // OFF keeps digest_since; turning digests on again restarts it at now
    assertThat(b.json("PATCH", "/api/me", "{\"digestFrequency\":\"OFF\"}", true).statusCode()).isEqualTo(200);
    Instant old = Instant.parse("2020-01-01T00:00:00Z");
    jdbc.update("update app_user set digest_since = ? where id = ?", Timestamp.from(old), userId);
    assertThat(b.json("PATCH", "/api/me", "{\"digestHour\":9}", true).statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select digest_since from app_user where id = ?", Timestamp.class, userId)
        .toInstant()).isEqualTo(old);
    assertThat(b.json("PATCH", "/api/me", "{\"digestFrequency\":\"DAILY\"}", true).statusCode()).isEqualTo(200);
    Instant since = jdbc.queryForObject("select digest_since from app_user where id = ?", Timestamp.class, userId)
        .toInstant();
    assertThat(Duration.between(since, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
  }

  @Test
  void xsrfCookieOnlyOnSpaPaths() throws Exception {
    for (String path : new String[] {"/api/auth/config", "/api/me"}) {
      assertThat(plainGet(path).headers().allValues("Set-Cookie")).as(path)
          .anyMatch(c -> c.startsWith("XSRF-TOKEN=") && !c.startsWith("XSRF-TOKEN=;"))
          .noneMatch(c -> c.startsWith("SESSION="));
    }
  }

  @Test
  void accountAndAuthResponsesAreNoStore() throws Exception {
    assertThat(plainGet("/api/auth/config").headers().allValues("Cache-Control")).containsExactly("no-store");
    // signed out (401) and signed in
    assertThat(plainGet("/api/me").headers().allValues("Cache-Control")).containsExactly("no-store");
    Browser b = signedInAs(emailUser("nostore-" + UUID.randomUUID().toString().substring(0, 8) + "@example.org", null));
    HttpResponse<String> me = b.get("/api/me");
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.headers().allValues("Cache-Control")).containsExactly("no-store");
    assertThat(b.get("/api/me/subscriptions").headers().allValues("Cache-Control")).containsExactly("no-store");
  }

  @Test
  void authConfigReportsOrcidOff() throws Exception {
    HttpResponse<String> r = plainGet("/api/auth/config");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"orcid\":false}");
  }
}
