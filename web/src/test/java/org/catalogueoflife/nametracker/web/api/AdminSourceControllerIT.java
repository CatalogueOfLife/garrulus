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
    jdbc.update("DELETE FROM subscription WHERE label IN ('Old Journal', 'Other')");
    jdbc.update("DELETE FROM app_user WHERE email = 'ceased@example.org'");
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
  void promotionVerifiesPendingRoutesOnly() throws Exception {
    long id = source("S77", "Registered", "CANDIDATE", true, false);
    long pending = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', '{\"base\":\"https://j.example.org/oai\"}'::jsonb, 'PENDING', 'REGISTERED') RETURNING id",
        Long.class, id);
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'MAGTECH', '{\"site\":\"https://j.example.org\"}'::jsonb, 'BROKEN', 'DISCOVERED')", id);
    call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"EXCLUDED\"}", "test-token");
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE'", String.class)).isEmpty();
    call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"BROAD\"}", "test-token");
    call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"CORE\"}", "test-token");
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE'", String.class))
        .containsExactly("verify-route:" + pending);
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

  long route(long sourceId, String layer, String status, String config) {
    return jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, ?, ?::jsonb, ?, 'SEED') RETURNING id", Long.class, sourceId, layer, config, status);
  }

  String routeStatus(long id) {
    return jdbc.queryForObject("SELECT status || '/' || coalesce(last_error, '') FROM source_route WHERE id = ?",
        String.class, id);
  }

  @Test
  void ceasingPausesRoutesAndNotesFollows() throws Exception {
    long successor = source("S10", "New Journal", "CORE", true, true);
    long id = source("S11", "Old Journal", "CORE", true, true);
    long active = route(id, "OAI", "ACTIVE", "{\"base\":\"https://a.example.org/oai\"}");
    long verified = route(id, "OAI", "VERIFIED", "{\"base\":\"https://b.example.org/oai\"}");
    long pending = route(id, "MAGTECH", "PENDING", "{\"site\":\"https://c.example.org\"}");
    long broken = route(id, "EUROPEPMC", "BROKEN", "{\"issns\":[\"1234-5679\"]}");
    long user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('ceased@example.org') RETURNING id", Long.class);
    jdbc.update("INSERT INTO subscription (user_id, type, target, label) VALUES (?, 'SOURCE', ?, 'Old Journal')", user,
        String.valueOf(id));
    jdbc.update("INSERT INTO subscription (user_id, type, target, label) VALUES (?, 'SOURCE', ?, 'Other')", user,
        String.valueOf(successor));

    HttpResponse<String> r = call("PATCH", "/api/admin/sources/" + id, "{\"publicationStatus\":\"CEASED\","
        + "\"ceasedReason\":\"TITLE_CHANGE\",\"lastIssueYear\":2019,\"continuedBy\":" + successor + "}", "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode v = JSON.readTree(r.body());
    assertThat(v.path("publicationStatus").asString()).isEqualTo("CEASED");
    assertThat(v.path("lastIssueYear").asInt()).isEqualTo(2019);
    assertThat(jdbc.queryForMap("SELECT publication_status, ceased_reason, status_set_by, continued_by, "
        + "status_changed IS NOT NULL AS changed FROM source WHERE id = ?", id))
        .containsEntry("ceased_reason", "TITLE_CHANGE").containsEntry("status_set_by", "ADMIN")
        .containsEntry("continued_by", successor).containsEntry("changed", true);
    assertThat(routeStatus(active)).isEqualTo("PAUSED/journal ceased");
    assertThat(routeStatus(verified)).isEqualTo("PAUSED/journal ceased");
    assertThat(routeStatus(pending)).isEqualTo("PAUSED/journal ceased");
    assertThat(routeStatus(broken)).isEqualTo("BROKEN/");
    assertThat(jdbc.queryForObject("SELECT xr_note FROM subscription WHERE target = ?", String.class, String.valueOf(id)))
        .isEqualTo("Ceased publication · last issue 2019. Continued as New Journal.");
    assertThat(jdbc.queryForObject("SELECT xr_note FROM subscription WHERE target = ?", String.class,
        String.valueOf(successor))).isNull();
    // no onboarding or verification is queued for a ceased journal
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }

  @Test
  void ceasedNeedsReason() throws Exception {
    long id = source("S12", "J", "CORE", true, true);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"publicationStatus\":\"CEASED\"}", "test-token")
        .statusCode()).isEqualTo(400);
    assertThat(call("PATCH", "/api/admin/sources/" + id,
        "{\"publicationStatus\":\"CEASED\",\"ceasedReason\":\"BORED\"}", "test-token").statusCode()).isEqualTo(400);
    assertThat(call("PATCH", "/api/admin/sources/" + id,
        "{\"publicationStatus\":\"CEASED\",\"ceasedReason\":\"STOPPED\",\"lastIssueYear\":1700}", "test-token")
        .statusCode()).isEqualTo(400);
    assertThat(call("PATCH", "/api/admin/sources/" + id,
        "{\"publicationStatus\":\"CEASED\",\"ceasedReason\":\"STOPPED\",\"lastIssueYear\":2999}", "test-token")
        .statusCode()).isEqualTo(400);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"publicationStatus\":\"GONE\"}", "test-token")
        .statusCode()).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT publication_status FROM source WHERE id = ?", String.class, id))
        .isEqualTo("ACTIVE");
  }

  @Test
  void continuedByMustExistAndDiffer() throws Exception {
    long id = source("S13", "J", "CORE", true, true);
    String ceased = "{\"publicationStatus\":\"CEASED\",\"ceasedReason\":\"MERGED\",\"continuedBy\":";
    assertThat(call("PATCH", "/api/admin/sources/" + id, ceased + id + "}", "test-token").statusCode()).isEqualTo(400);
    assertThat(call("PATCH", "/api/admin/sources/" + id, ceased + "999999}", "test-token").statusCode()).isEqualTo(400);
    assertThat(jdbc.queryForObject("SELECT publication_status FROM source WHERE id = ?", String.class, id))
        .isEqualTo("ACTIVE");
  }

  @Test
  void reactivationPutsRoutesBackToPendingAndVerifies() throws Exception {
    long id = source("S14", "J", "CORE", true, true);
    long active = route(id, "OAI", "ACTIVE", "{\"base\":\"https://a.example.org/oai\"}");
    long manual = route(id, "MAGTECH", "PAUSED", "{\"site\":\"https://c.example.org\"}"); // paused by an admin
    call("PATCH", "/api/admin/sources/" + id, "{\"publicationStatus\":\"CEASED\",\"ceasedReason\":\"STOPPED\"}",
        "test-token");
    assertThat(routeStatus(active)).isEqualTo("PAUSED/journal ceased");
    jdbc.update("DELETE FROM task");

    HttpResponse<String> r = call("PATCH", "/api/admin/sources/" + id, "{\"publicationStatus\":\"ACTIVE\"}",
        "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(r.body()).path("publicationStatus").asString()).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForMap("SELECT ceased_reason, last_issue_year, continued_by FROM source WHERE id = ?", id))
        .containsEntry("ceased_reason", null).containsEntry("last_issue_year", null);
    assertThat(routeStatus(active)).isEqualTo("PENDING/");
    assertThat(routeStatus(manual)).isEqualTo("PAUSED/");
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE'", String.class))
        .containsExactly("verify-route:" + active);
    // no follow note on reactivation beyond the one of the ceasing
  }

  @Test
  void statusOnlyAndDismissOnlyPatchesAreValid() throws Exception {
    long id = source("S15", "J", "CORE", true, true);
    jdbc.update("UPDATE source SET dormant_since = current_date, dormant_evidence = '{}'::jsonb WHERE id = ?", id);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"dismissDormant\":true}", "test-token").statusCode())
        .isEqualTo(200);
    assertThat(jdbc.queryForMap("SELECT dormant_since, dormant_dismissed_at IS NOT NULL AS dismissed FROM source "
        + "WHERE id = ?", id)).containsEntry("dormant_since", null).containsEntry("dismissed", true);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"dismissDormant\":false}", "test-token").statusCode())
        .isEqualTo(400);
  }

  @Test
  void patchToTheCurrentStatusChangesNothing() throws Exception {
    long id = source("S16", "J", "CORE", true, true);
    call("PATCH", "/api/admin/sources/" + id, "{\"publicationStatus\":\"ACTIVE\"}", "test-token");
    assertThat(jdbc.queryForMap("SELECT status_changed, status_set_by FROM source WHERE id = ?", id))
        .containsEntry("status_changed", null).containsEntry("status_set_by", null);
    String ceased = "{\"publicationStatus\":\"CEASED\",\"ceasedReason\":\"STOPPED\",\"lastIssueYear\":2019}";
    call("PATCH", "/api/admin/sources/" + id, ceased, "test-token");
    Object first = jdbc.queryForObject("SELECT status_changed FROM source WHERE id = ?", Object.class, id);
    assertThat(first).isNotNull();
    call("PATCH", "/api/admin/sources/" + id, ceased.replace("2019", "2020"), "test-token");
    assertThat(jdbc.queryForObject("SELECT status_changed FROM source WHERE id = ?", Object.class, id)).isEqualTo(first);
    assertThat(jdbc.queryForObject("SELECT last_issue_year FROM source WHERE id = ?", Integer.class, id)).isEqualTo(2020);
  }
}
