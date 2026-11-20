package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AdminSourceControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
  }

  long source(String openalexId, String title, String tier, boolean active, boolean onboarded) {
    return jdbc.queryForObject(
        "INSERT INTO source (openalex_id, title, tier, origin, active, works_per_year, onboarded_at) "
            + "VALUES (?, ?, ?, 'MANUAL', ?, 120, CASE WHEN ? THEN now() END) RETURNING id",
        Long.class, openalexId, title, tier, active, onboarded);
  }

  HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      b.header("X-Admin-Token", token);
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  long onboardTasks(long id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM task WHERE type = 'ONBOARD_SOURCE' AND dedup_key = ?", Long.class, "onboard:" + id);
  }

  @Test
  void promotionEnqueuesOnboardingOnce() throws Exception {
    long id = source("S1", "Zootaxa", "CANDIDATE", true, false);
    HttpResponse<String> r = call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"CORE\"}", "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(r.body()).path("tier").asString()).isEqualTo("CORE");
    assertThat(jdbc.queryForMap("SELECT tier, tier_set_by FROM source WHERE id = ?", id))
        .containsEntry("tier", "CORE").containsEntry("tier_set_by", "ADMIN");
    assertThat(onboardTasks(id)).isEqualTo(1);
    var task = jdbc.queryForMap("SELECT payload::text AS p, priority FROM task WHERE dedup_key = ?", "onboard:" + id);
    assertThat(JSON.readTree((String) task.get("p")).path("sourceId").asLong()).isEqualTo(id);
    assertThat(((Number) task.get("priority")).intValue()).isEqualTo(2); // BACKFILL

    // promoting again while the task is still live does not add a second one
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"BROAD\"}", "test-token").statusCode())
        .isEqualTo(200);
    assertThat(onboardTasks(id)).isEqualTo(1);
  }

  @Test
  void noOnboardingWhenAlreadyOnboardedInactiveOrNotPromoted() throws Exception {
    long done = source("S2", "A", "CANDIDATE", true, true);
    long inactive = source("S3", "B", "CANDIDATE", false, false);
    long demoted = source("S4", "C", "CANDIDATE", true, false);
    call("PATCH", "/api/admin/sources/" + done, "{\"tier\":\"CORE\"}", "test-token");
    call("PATCH", "/api/admin/sources/" + inactive, "{\"tier\":\"CORE\"}", "test-token");
    call("PATCH", "/api/admin/sources/" + demoted, "{\"tier\":\"EXCLUDED\",\"active\":false}", "test-token");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT active FROM source WHERE id = ?", Boolean.class, demoted)).isFalse();
    // activating a promoted, not onboarded source makes it eligible
    call("PATCH", "/api/admin/sources/" + inactive, "{\"active\":true}", "test-token");
    assertThat(onboardTasks(inactive)).isEqualTo(1);
  }

  @Test
  void unknownSource404() throws Exception {
    assertThat(call("PATCH", "/api/admin/sources/999999", "{\"tier\":\"CORE\"}", "test-token").statusCode())
        .isEqualTo(404);
  }

  @Test
  void badTier400() throws Exception {
    long id = source("S5", "D", "CANDIDATE", true, false);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"GOLD\"}", "test-token").statusCode())
        .isEqualTo(400);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{}", "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", "/api/admin/sources", "{\"openalexId\":\"S1\",\"tier\":\"GOLD\"}", "test-token")
        .statusCode()).isEqualTo(400);
    assertThat(call("POST", "/api/admin/sources", "{\"tier\":\"CORE\"}", "test-token").statusCode()).isEqualTo(400);
    assertThat(call("GET", "/api/admin/sources?tier=GOLD", null, "test-token").statusCode()).isEqualTo(400);
    assertThat(call("GET", "/api/admin/sources?limit=501", null, "test-token").statusCode()).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, id)).isEqualTo("CANDIDATE");
  }

  @Test
  void withoutToken401() throws Exception {
    long id = source("S6", "E", "CANDIDATE", true, false);
    assertThat(call("GET", "/api/admin/sources", null, null).statusCode()).isEqualTo(401);
    assertThat(call("GET", "/api/admin/sources", null, "wrong").statusCode()).isEqualTo(401);
    // without the header a write is a session write, which needs XSRF first (ADR-0006, amendment M6b)
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"CORE\"}", null).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/admin/sources", "{\"openalexId\":\"S7\"}", "wrong").statusCode()).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, id)).isEqualTo("CANDIDATE");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }

  @Test
  void listFiltersAndPages() throws Exception {
    source("S10", "Zootaxa", "CORE", true, true);
    source("S11", "ZooKeys", "BROAD", true, false);
    source("S12", "Other", "BROAD", false, false);
    JsonNode all = JSON.readTree(call("GET", "/api/admin/sources", null, "test-token").body());
    assertThat(all.size()).isEqualTo(3);
    JsonNode first = all.get(0);
    assertThat(first.propertyNames()).contains("id", "openalexId", "title", "tier", "active",
        "onboardedAt", "lastHarvested", "worksPerYear");
    assertThat(first.path("openalexId").asString()).isEqualTo("S10");
    assertThat(first.path("worksPerYear").asInt()).isEqualTo(120);
    assertThat(JSON.readTree(call("GET", "/api/admin/sources?tier=BROAD&active=true&q=zoo", null, "test-token")
        .body()).size()).isEqualTo(1);
    assertThat(JSON.readTree(call("GET", "/api/admin/sources?limit=2&offset=2", null, "test-token").body()).size())
        .isEqualTo(1);
  }

  @Test
  void addSourceEnqueuesAddSourceAndAnswers202() throws Exception {
    HttpResponse<String> r = call("POST", "/api/admin/sources", "{\"openalexId\":\"S4210\",\"tier\":\"CORE\"}",
        "test-token");
    assertThat(r.statusCode()).isEqualTo(202);
    var task = jdbc.queryForMap("SELECT payload::text AS p, dedup_key FROM task WHERE type = 'ADD_SOURCE'");
    JsonNode p = JSON.readTree((String) task.get("p"));
    assertThat(p.path("openalexId").asString()).isEqualTo("S4210");
    assertThat(p.path("tier").asString()).isEqualTo("CORE");
    assertThat(task.get("dedup_key")).isEqualTo("add-source:S4210");
  }
}
