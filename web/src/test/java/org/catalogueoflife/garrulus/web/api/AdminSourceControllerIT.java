package org.catalogueoflife.garrulus.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.garrulus.web.AbstractPostgresIT;
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
    jdbc.update("DELETE FROM app_user WHERE email = 'ceased@example.org' OR email LIKE 't12%@example.org'");
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
    JsonNode page = JSON.readTree(call("GET", "/api/admin/sources", null, "test-token").body());
    assertThat(page.path("total").asInt()).isEqualTo(3);
    JsonNode all = page.path("items");
    assertThat(all.size()).isEqualTo(3);
    JsonNode first = all.get(0);
    assertThat(first.propertyNames()).contains("id", "openalexId", "title", "tier", "active",
        "onboardedAt", "lastHarvested", "worksPerYear");
    assertThat(first.path("openalexId").asString()).isEqualTo("S10");
    assertThat(first.path("worksPerYear").asInt()).isEqualTo(120);
    assertThat(JSON.readTree(call("GET", "/api/admin/sources?tier=BROAD&active=true&q=zoo", null, "test-token")
        .body()).path("items").size()).isEqualTo(1);
    JsonNode paged = JSON.readTree(call("GET", "/api/admin/sources?limit=2&offset=2", null, "test-token").body());
    assertThat(paged.path("items").size()).isEqualTo(1);
    assertThat(paged.path("total").asInt()).isEqualTo(3);
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

  // ---- M6b Task 12: candidates, proposals, detail, disciplines, sampling

  JsonNode items(String query) throws Exception {
    HttpResponse<String> r = call("GET", "/api/admin/sources" + query, null, "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    return JSON.readTree(r.body()).path("items");
  }

  long candidate(String title, Float score, String origin, String country) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, country, candidate_score, issn_l, issns) "
        + "VALUES (?, 'CANDIDATE', ?, ?, ?, NULL, '{}') RETURNING id", Long.class, title, origin, country, score);
  }

  @Test
  void candidatesSortByScoreWithSignalsAndSample() throws Exception {
    long low = candidate("Low", 0.2f, "DOAJ", "DE");
    long high = candidate("High", 0.9f, "PKP_BEACON", "BR");
    long none = candidate("Unscored", null, "DOAJ", "DE");
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES (?, 'PKP_BEACON', '', '{}'), "
        + "(?, 'DOAJ', '', '{}')", high, high);
    jdbc.update("INSERT INTO source_sample (source_id, sampled_at, route, articles, with_abstract, names_per_article, "
        + "act_share, taxgroups) VALUES (?, now() - interval '2 days', 'OAI', 5, 5, 1, 0, '{}'), "
        + "(?, now(), 'OAI', 20, 18, 7.5, 0.25, '{\"Insects\":0.8}')", high, high);
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', '{\"base\":\"https://a.example.org/oai\"}'::jsonb, 'PENDING', 'DISCOVERED')", high);

    JsonNode page = JSON.readTree(call("GET", "/api/admin/sources?tier=CANDIDATE", null, "test-token").body());
    assertThat(page.path("total").asInt()).isEqualTo(3);
    JsonNode rows = page.path("items");
    assertThat(rows.get(0).path("id").asLong()).isEqualTo(high);
    assertThat(rows.get(1).path("id").asLong()).isEqualTo(low);
    assertThat(rows.get(2).path("id").asLong()).isEqualTo(none);
    JsonNode h = rows.get(0);
    assertThat(h.path("candidateScore").asDouble()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.001));
    assertThat(h.path("origin").asString()).isEqualTo("PKP_BEACON");
    assertThat(h.path("country").asString()).isEqualTo("BR");
    assertThat(h.path("signalOrigins")).extracting(JsonNode::asString).containsExactlyInAnyOrder("PKP_BEACON", "DOAJ");
    assertThat(h.path("sample").path("articles").asInt()).isEqualTo(20); // the newest sample
    assertThat(h.path("sample").path("namesPerArticle").asDouble()).isEqualTo(7.5);
    assertThat(h.path("sample").path("taxgroups").path("Insects").asDouble()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.001));
    assertThat(h.path("routes").path("PENDING").asLong()).isEqualTo(1);
    assertThat(rows.get(1).path("sample").isNull()).isTrue();
    assertThat(h.path("publicationStatus").asString()).isEqualTo("ACTIVE");
    // by title
    assertThat(items("?tier=CANDIDATE&sort=title").get(0).path("title").asString()).isEqualTo("High");
    assertThat(call("GET", "/api/admin/sources?sort=banana", null, "test-token").statusCode()).isEqualTo(400);
  }

  @Test
  void filtersByOriginCountryAndRegistered() throws Exception {
    long de = candidate("De", 0.5f, "DOAJ", "DE");
    long br = candidate("Br", 0.4f, "PKP_BEACON", "BR");
    long reg = candidate("Reg", 0.1f, "REGISTERED", null);
    long viaRegistration = candidate("Via", 0.1f, "DOAJ", "DE");
    long user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('t12a@example.org') RETURNING id", Long.class);
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, state, source_id) VALUES "
        + "(?, 'ISSN', '1234-5679', 'IDENTIFIED', ?), (?, 'ISSN', '1234-5670', 'WITHDRAWN', ?)", user, viaRegistration,
        user, de);
    assertThat(items("?origin=PKP_BEACON")).extracting(n -> n.path("id").asLong()).containsExactly(br);
    assertThat(items("?country=de")).extracting(n -> n.path("id").asLong()).containsExactlyInAnyOrder(de, viaRegistration);
    assertThat(items("?registered=true")).extracting(n -> n.path("id").asLong())
        .containsExactlyInAnyOrder(reg, viaRegistration);
    assertThat(items("?registered=false")).extracting(n -> n.path("id").asLong()).containsExactlyInAnyOrder(de, br);
    assertThat(items("?registered=true").get(0).path("registrations").asInt()).isGreaterThanOrEqualTo(0);
    assertThat(items("?registered=true&sort=title").size()).isEqualTo(2);
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM app_user WHERE email = 't12a@example.org'");
  }

  @Test
  void densityProposalsShown() throws Exception {
    long promote = harvested("S101", "Promote", "BROAD", 0.12f, 6f);
    long demoteCore = harvested("S102", "DemoteCore", "CORE", 0.01f, 0.5f);
    long demoteBroad = harvested("S103", "DemoteBroad", "BROAD", 0.2f, 0.4f);
    long fine = harvested("S104", "Fine", "BROAD", 0.05f, 3f);
    long coreFine = harvested("S105", "CoreFine", "CORE", 0.01f, 2f);
    long unscored = harvested("S106", "Unscored", "CORE", null, null);
    JsonNode all = items("");
    assertThat(proposal(all, promote)).isEqualTo("PROMOTE");
    assertThat(proposal(all, demoteCore)).isEqualTo("DEMOTE");
    assertThat(proposal(all, demoteBroad)).isEqualTo("DEMOTE");
    assertThat(proposal(all, fine)).isNull();
    assertThat(proposal(all, coreFine)).isNull();
    assertThat(proposal(all, unscored)).isNull();
    assertThat(items("?proposal=true")).extracting(n -> n.path("id").asLong())
        .containsExactlyInAnyOrder(promote, demoteCore, demoteBroad);
    // shown, never applied
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, promote)).isEqualTo("BROAD");
  }

  long harvested(String oa, String title, String tier, Float actRate, Float nameDensity) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin, active, onboarded_at, act_rate, "
        + "name_density) VALUES (?, ?, ?, 'MANUAL', true, now(), ?, ?) RETURNING id", Long.class, oa, title, tier,
        actRate, nameDensity);
  }

  static String proposal(JsonNode rows, long id) {
    for (JsonNode n : rows) {
      if (n.path("id").asLong() == id) return n.path("proposal").isNull() ? null : n.path("proposal").asString();
    }
    throw new AssertionError("source " + id + " not listed");
  }

  @Test
  void dormantAndRevivalProposalsShown() throws Exception {
    long dormant = harvested("S111", "Dormant", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET dormant_since = current_date, dormant_evidence = '{\"lastPublished\":\"2024-01-01\"}'"
        + "::jsonb WHERE id = ?", dormant);
    long ceased = harvested("S112", "Ceased", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', status_set_by = 'ADMIN', "
        + "status_changed = now() - interval '30 days' WHERE id = ?", ceased);
    // a re-offer bumps last_seen but is no evidence; dated evidence newer than the status change is
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, first_seen, last_seen, detail) VALUES "
        + "(?, 'DOAJ', '', now() - interval '400 days', now(), '{}')", ceased);
    long stale = harvested("S113", "StaleCeased", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', status_changed = now() "
        + "- interval '30 days' WHERE id = ?", stale);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES "
        + "(?, 'BLR', '', ('{\"lastSeen\":\"' || (current_date - 90) || '\"}')::jsonb)", stale);
    long revived = harvested("S114", "Revived", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', status_changed = now() "
        + "- interval '30 days' WHERE id = ?", revived);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES "
        + "(?, 'BLR', '', ('{\"lastSeen\":\"' || (current_date - 3) || '\"}')::jsonb)", revived);

    // a PKP signal alone is no dated evidence
    long pkp = harvested("S115", "PkpOnly", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', status_changed = now() "
        + "- interval '30 days' WHERE id = ?", pkp);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES "
        + "(?, 'PKP_BEACON', '', '{\"records2025\":40}'::jsonb)", pkp);
    // malformed dates must not break the list
    long bad = harvested("S116", "BadDate", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', status_changed = now() "
        + "- interval '30 days' WHERE id = ?", bad);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES "
        + "(?, 'BLR', '', '{\"lastSeen\":\"garbage\"}'::jsonb), (?, 'BLR', 'b', '{\"lastSeen\":\"2026-02-31\"}'::jsonb),"
        + " (?, 'BLR', 'c', '{\"lastSeen\":7}'::jsonb)", bad, bad, bad);
    // a leap day is a real day
    long leap = harvested("S120", "LeapDay", "CORE", 0.1f, 5f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', status_changed = "
        + "'2023-06-01' WHERE id = ?", leap);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES "
        + "(?, 'BLR', '', '{\"lastSeen\":\"2024-02-29T10:00:00Z\"}'::jsonb)", leap);
    // an excluded journal: only a recent registration (a user asking again) proposes a revival, a BLR sighting does not
    long exReg = harvested("S117", "ExcludedAsked", "EXCLUDED", null, null);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES (?, 'REGISTERED', '1', '{}'::jsonb)",
        exReg);
    long exBlr = harvested("S118", "ExcludedBlr", "EXCLUDED", null, null);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES "
        + "(?, 'BLR', '', ('{\"lastSeen\":\"' || (current_date - 3) || '\"}')::jsonb)", exBlr);
    long exOld = harvested("S119", "ExcludedOld", "EXCLUDED", null, null);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, first_seen, detail) VALUES "
        + "(?, 'REGISTERED', '2', now() - interval '200 days', '{}'::jsonb)", exOld);

    JsonNode all = items("");
    assertThat(flag(all, pkp, "revival")).isFalse();
    assertThat(flag(all, bad, "revival")).isFalse();
    assertThat(flag(all, leap, "revival")).isTrue();
    assertThat(flag(all, exReg, "revival")).isTrue();
    assertThat(flag(all, exBlr, "revival")).isFalse();
    assertThat(flag(all, exOld, "revival")).isFalse();
    assertThat(flag(all, dormant, "dormant")).isTrue();
    assertThat(flag(all, ceased, "revival")).isFalse();
    assertThat(flag(all, stale, "revival")).isFalse();
    assertThat(flag(all, revived, "revival")).isTrue();
    assertThat(flag(all, revived, "dormant")).isFalse();
    assertThat(items("?dormant=true")).extracting(n -> n.path("id").asLong()).containsExactly(dormant);
    assertThat(items("?revival=true")).extracting(n -> n.path("id").asLong())
        .containsExactlyInAnyOrder(revived, exReg, leap);
    // never applied
    assertThat(jdbc.queryForObject("SELECT publication_status FROM source WHERE id = ?", String.class, revived))
        .isEqualTo("CEASED");
    JsonNode detail = JSON.readTree(call("GET", "/api/admin/sources/" + dormant, null, "test-token").body());
    assertThat(detail.path("dormantSince").asString()).isNotEmpty();
    assertThat(detail.path("dormantEvidence").path("lastPublished").asString()).isEqualTo("2024-01-01");
  }

  static boolean flag(JsonNode rows, long id, String name) {
    for (JsonNode n : rows) if (n.path("id").asLong() == id) return n.path(name).asBoolean();
    throw new AssertionError("source " + id + " not listed");
  }

  @Test
  void detailShowsRegistrationNoteAndRoutes() throws Exception {
    long id = candidate("Registered Journal", 0.3f, "REGISTERED", "NL");
    long user = jdbc.queryForObject("INSERT INTO app_user (email, display_name) VALUES ('t12b@example.org', 'Ada Reg') "
        + "RETURNING id", Long.class);
    long reg = jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, note, state, outcome, "
        + "source_id, tier_at_identification) VALUES (?, 'ISSN', '1234-5679', 'please add, it is full of beetles', "
        + "'IDENTIFIED', 'NEW_CANDIDATE', ?, 'CANDIDATE') RETURNING id", Long.class, user, id);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES (?, 'REGISTERED', ?, "
        + "'{\"input\":\"1234-5679\"}'::jsonb), (?, 'DOAJ', '', '{\"title\":\"x\"}'::jsonb)", id,
        String.valueOf(reg), id);
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', '{\"base\":\"https://a.example.org/oai\"}'::jsonb, 'PENDING', 'REGISTERED')", id);
    jdbc.update("INSERT INTO source_classification_raw (source_id, scheme, code, label, share, rank) VALUES "
        + "(?, 'OPENALEX_SUBFIELD', '1109', 'Insect Science', 0.6, 0)", id);
    jdbc.update("UPDATE source SET subfields = '{1109}', primary_subfield = '1109', discipline_source = 'OPENALEX' "
        + "WHERE id = ?", id);

    HttpResponse<String> r = call("GET", "/api/admin/sources/" + id, null, "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode d = JSON.readTree(r.body());
    assertThat(d.at("/source/id").asLong()).isEqualTo(id);
    assertThat(d.at("/source/registrations").asInt()).isEqualTo(1);
    JsonNode registered = null;
    for (JsonNode s : d.path("signals")) if (s.path("origin").asString().equals("REGISTERED")) registered = s;
    assertThat(registered).isNotNull();
    assertThat(registered.at("/detail/note").asString()).isEqualTo("please add, it is full of beetles");
    assertThat(registered.at("/detail/userDisplayName").asString()).isEqualTo("Ada Reg");
    assertThat(registered.at("/detail/input").asString()).isEqualTo("1234-5679");
    assertThat(d.path("signals").size()).isEqualTo(2);
    assertThat(d.at("/routes/0/layer").asString()).isEqualTo("OAI");
    assertThat(d.at("/routes/0/config/base").asString()).isEqualTo("https://a.example.org/oai");
    assertThat(d.at("/registrations/0/note").asString()).isEqualTo("please add, it is full of beetles");
    assertThat(d.at("/registrations/0/userDisplayName").asString()).isEqualTo("Ada Reg");
    assertThat(d.path("disciplines")).extracting(JsonNode::asString).containsExactly("1109");
    assertThat(d.path("disciplineSetBy").asString()).isEqualTo("DERIVED");
    assertThat(d.path("disciplineSource").asString()).isEqualTo("OPENALEX");
    assertThat(d.at("/classifications/0/code").asString()).isEqualTo("1109");
    assertThat(call("GET", "/api/admin/sources/999999", null, "test-token").statusCode()).isEqualTo(404);
    jdbc.update("DELETE FROM app_user WHERE email = 't12b@example.org'");
  }

  @Test
  void adminDisciplinesOverride() throws Exception {
    long id = candidate("D", null, "DOAJ", null);
    HttpResponse<String> r = call("PATCH", "/api/admin/sources/" + id, "{\"disciplines\":[\"1109\",\"1110\"]}",
        "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForMap("SELECT subfields::text AS s, primary_subfield, discipline_set_by, discipline_source "
        + "FROM source WHERE id = ?", id)).containsEntry("s", "{1109,1110}").containsEntry("primary_subfield", "1109")
        .containsEntry("discipline_set_by", "ADMIN").containsEntry("discipline_source", "ADMIN");
    // the nightly derivation leaves it alone (updateDisciplines skips ADMIN rows)
    JsonNode d = JSON.readTree(call("GET", "/api/admin/sources/" + id, null, "test-token").body());
    assertThat(d.path("disciplineSetBy").asString()).isEqualTo("ADMIN");
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"releaseDisciplines\":true}", "test-token").statusCode())
        .isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT discipline_set_by FROM source WHERE id = ?", String.class, id))
        .isEqualTo("DERIVED");
    // an empty list is a valid override: the journal has none
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"disciplines\":[]}", "test-token").statusCode())
        .isEqualTo(200);
    assertThat(jdbc.queryForMap("SELECT subfields::text AS s, primary_subfield, discipline_set_by FROM source "
        + "WHERE id = ?", id)).containsEntry("s", "{}").containsEntry("primary_subfield", null)
        .containsEntry("discipline_set_by", "ADMIN");
  }

  @Test
  void retiredDisciplineRejected() throws Exception {
    long id = candidate("D2", null, "DOAJ", null);
    jdbc.update("INSERT INTO discipline (code, label, replaced_by, sort_order) VALUES ('9998', 'Retired', '1103', 99) "
        + "ON CONFLICT DO NOTHING");
    try {
      assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"disciplines\":[\"9998\"]}", "test-token").statusCode())
          .isEqualTo(400);
      assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"disciplines\":[\"1109\",\"0000\"]}", "test-token")
          .statusCode()).isEqualTo(400);
      assertThat(jdbc.queryForObject("SELECT discipline_set_by FROM source WHERE id = ?", String.class, id))
          .isEqualTo("DERIVED");
    } finally {
      jdbc.update("DELETE FROM discipline WHERE code = '9998'");
    }
  }

  @Test
  void acceptRegisteredJournalStartsOnboarding() throws Exception {
    long id = jdbc.queryForObject("INSERT INTO source (title, tier, origin, issn_l, issns) VALUES ('Crossref only', "
        + "'CANDIDATE', 'REGISTERED', '1234-5679', '{1234-5679}') RETURNING id", Long.class);
    assertThat(call("PATCH", "/api/admin/sources/" + id, "{\"tier\":\"CORE\"}", "test-token").statusCode())
        .isEqualTo(200);
    assertThat(onboardTasks(id)).isEqualTo(1);
    // the registration view shows the source's current tier
    assertThat(jdbc.queryForObject("SELECT tier FROM source WHERE id = ?", String.class, id)).isEqualTo("CORE");
  }

  @Test
  void sampleTriggerQueuesTask() throws Exception {
    long id = candidate("S", null, "DOAJ", null);
    HttpResponse<String> r = call("POST", "/api/admin/sources/" + id + "/sample", null, "test-token");
    assertThat(r.statusCode()).isEqualTo(202);
    var task = jdbc.queryForMap("SELECT type, payload::text AS p, priority, dedup_key FROM task WHERE type = 'SAMPLE_SOURCE'");
    JsonNode p = JSON.readTree((String) task.get("p"));
    assertThat(p.path("sourceId").asLong()).isEqualTo(id);
    assertThat(p.path("force").asBoolean()).isTrue();
    assertThat(((Number) task.get("priority")).intValue()).isZero(); // LIVE
    assertThat((String) task.get("dedup_key")).isEqualTo("sample:" + id + ":manual:"
        + java.time.LocalDate.now(java.time.ZoneOffset.UTC));
    assertThat(call("POST", "/api/admin/sources/999999/sample", null, "test-token").statusCode()).isEqualTo(404);
    assertThat(call("POST", "/api/admin/sources/" + id + "/sample", null, null).statusCode()).isEqualTo(403);
  }

  @Test
  void adminReadsAreNotCached() throws Exception {
    assertThat(call("GET", "/api/admin/sources", null, "test-token").headers().firstValue("Cache-Control"))
        .contains("no-store");
  }
}
