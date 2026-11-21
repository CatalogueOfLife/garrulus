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

class AdminStatusControllerIT extends AbstractPostgresIT {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("DELETE FROM harvest_run");
  }

  HttpResponse<String> get(String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/status"));
    if (token != null) {
      b.header("X-Admin-Token", token);
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void reportsCounts() throws Exception {
    jdbc.update("INSERT INTO source (openalex_id, title, origin, tier) VALUES "
        + "('S1','a','MANUAL','CORE'), ('S2','b','MANUAL','CORE'), ('S3','c','MANUAL','BROAD')");
    jdbc.update("INSERT INTO work (doi, origin, state, text_level) VALUES "
        + "('10.1/a','LIVE','CONTENT_RESOLVED','FULLTEXT'), ('10.1/b','LIVE','CONTENT_RESOLVED','FULLTEXT'), "
        + "('10.1/c','LIVE','CONTENT_RESOLVED','ABSTRACT_ONLY'), ('10.1/d','LIVE','DISCOVERED', NULL)");
    jdbc.update("INSERT INTO task (type, priority, dedup_key) VALUES ('RESOLVE_CONTENT', 0, 'a'), "
        + "('RESOLVE_CONTENT', 0, 'b'), ('HARVEST_CROSSREF', 0, 'c')");
    jdbc.update("INSERT INTO api_usage (service, day, calls, usd, remaining_usd) VALUES "
        + "('openalex', (now() AT TIME ZONE 'UTC')::date, 12, 0.0012, 0.0988), "
        + "('openalex', (now() AT TIME ZONE 'UTC')::date - 1, 99, 0.0099, NULL)");
    for (int i = 0; i < 25; i++) {
      jdbc.update("INSERT INTO harvest_run (adapter, started) VALUES ('OPENALEX_LIVE', now() - make_interval(mins => ?))", i);
    }

    HttpResponse<String> r = get("test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode s = new ObjectMapper().readTree(r.body());

    assertThat(s.at("/tasks/RESOLVE_CONTENT/QUEUED").asLong()).isEqualTo(2);
    assertThat(s.at("/tasks/HARVEST_CROSSREF/QUEUED").asLong()).isEqualTo(1);
    assertThat(s.at("/works/CONTENT_RESOLVED/FULLTEXT").asLong()).isEqualTo(2);
    assertThat(s.at("/works/CONTENT_RESOLVED/ABSTRACT_ONLY").asLong()).isEqualTo(1);
    assertThat(s.at("/works/DISCOVERED/NONE").asLong()).isEqualTo(1);
    assertThat(s.at("/sources/CORE").asLong()).isEqualTo(2);
    assertThat(s.at("/sources/BROAD").asLong()).isEqualTo(1);
    assertThat(s.path("apiUsage").size()).isEqualTo(1); // today only
    assertThat(s.at("/apiUsage/0/service").asString()).isEqualTo("openalex");
    assertThat(s.at("/apiUsage/0/calls").asLong()).isEqualTo(12);
    assertThat(s.at("/apiUsage/0/remainingUsd").asDouble()).isEqualTo(0.0988);
    assertThat(s.path("harvestRuns").size()).isEqualTo(20);
    assertThat(s.at("/harvestRuns/0/adapter").asString()).isEqualTo("OPENALEX_LIVE");
  }

  @Test
  void reportsXrBackfillAndRollouts() throws Exception {
    jdbc.update("DELETE FROM backfill_run");
    jdbc.update("DELETE FROM finder_rollout");
    jdbc.update("INSERT INTO xr_release (key, alias, current, state, switch_started, switched_at, keys_total, keys_lost, note) "
        + "VALUES (910001, 'XR old', false, 'RETIRED', NULL, NULL, NULL, NULL, NULL), "
        + "(910002, 'XR new', true, 'SWITCHING', '2026-11-10T10:00:00Z', '2026-11-10T11:00:00Z', 1000, 12, "
        + "'{\"refusedBy\":\"secret\"}')");
    long run = jdbc.queryForObject("INSERT INTO backfill_run (from_year, to_year, snapshot_date, files_total, state) "
        + "VALUES (2020, 2022, '2026-10-01', 3, 'RUNNING') RETURNING id", Long.class);
    jdbc.update("INSERT INTO backfill_file (run_id, idx, url, records_read, records_kept, works_created, done_at) VALUES "
        + "(?, 0, 'u0', 100, 40, 30, now()), (?, 1, 'u1', 50, 10, 5, now()), (?, 2, 'u2', NULL, NULL, 7, NULL)",
        run, run, run);
    jdbc.update("INSERT INTO finder_rollout (config_hash, started, enqueued_at, baseline) VALUES "
        + "('h1', '2026-11-01T00:00:00Z', NULL, true), ('h2', '2026-11-12T00:00:00Z', '2026-11-12T05:00:00Z', false)");

    HttpResponse<String> r = get("test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode s = new ObjectMapper().readTree(r.body());

    JsonNode xr = null;
    JsonNode old = null;
    for (JsonNode n : s.path("xrReleases")) {
      if (n.path("key").asInt() == 910002) {
        xr = n;
      } else if (n.path("key").asInt() == 910001) {
        old = n;
      }
    }
    assertThat(xr).isNotNull();
    assertThat(xr.path("alias").asString()).isEqualTo("XR new");
    assertThat(xr.path("state").asString()).isEqualTo("SWITCHING");
    assertThat(xr.path("current").asBoolean()).isTrue();
    assertThat(xr.path("keysTotal").asInt()).isEqualTo(1000);
    assertThat(xr.path("keysLost").asInt()).isEqualTo(12);
    assertThat(xr.path("switchStarted").asString()).startsWith("2026-11-10T10:00");
    assertThat(xr.path("switchedAt").asString()).startsWith("2026-11-10T11:00");
    assertThat(xr.has("note")).isFalse();
    assertThat(r.body()).doesNotContain("secret");
    assertThat(old).isNotNull();
    assertThat(old.path("current").asBoolean()).isFalse();
    assertThat(old.path("keysTotal").isNull()).isTrue();

    assertThat(s.path("backfillRuns").size()).isEqualTo(1);
    JsonNode b = s.path("backfillRuns").get(0);
    assertThat(b.path("id").asLong()).isEqualTo(run);
    assertThat(b.path("fromYear").asInt()).isEqualTo(2020);
    assertThat(b.path("toYear").asInt()).isEqualTo(2022);
    assertThat(b.path("snapshotDate").asString()).isEqualTo("2026-10-01");
    assertThat(b.path("state").asString()).isEqualTo("RUNNING");
    assertThat(b.path("filesDone").asInt()).isEqualTo(2);
    assertThat(b.path("filesTotal").asInt()).isEqualTo(3);
    assertThat(b.path("recordsRead").asLong()).isEqualTo(150);
    assertThat(b.path("recordsKept").asLong()).isEqualTo(50);
    assertThat(b.path("worksCreated").asLong()).isEqualTo(42);

    assertThat(s.path("finderRollouts").size()).isEqualTo(2);
    assertThat(s.at("/finderRollouts/0/configHash").asString()).isEqualTo("h2");
    assertThat(s.at("/finderRollouts/0/enqueuedAt").asString()).startsWith("2026-11-12T05:00");
    assertThat(s.at("/finderRollouts/0/baseline").asBoolean()).isFalse();
    assertThat(s.at("/finderRollouts/1/configHash").asString()).isEqualTo("h1");
    assertThat(s.at("/finderRollouts/1/enqueuedAt").isNull()).isTrue();
    assertThat(s.at("/finderRollouts/1/baseline").asBoolean()).isTrue();

    jdbc.update("DELETE FROM backfill_run");
    jdbc.update("DELETE FROM finder_rollout");
    jdbc.update("DELETE FROM xr_release WHERE key IN (910001, 910002)");
  }

  @Test
  void withoutToken401() throws Exception {
    assertThat(get(null).statusCode()).isEqualTo(401);
    assertThat(get("wrong").statusCode()).isEqualTo(401);
  }

  @Test
  void statusListsLayersAndRoutes() throws Exception {
    jdbc.update("DELETE FROM layer_health");
    jdbc.update("DELETE FROM source_route");
    long cn = jdbc.queryForObject("INSERT INTO source (openalex_id, title, origin, tier, country) "
        + "VALUES ('SC','c','MANUAL','CORE','CN') RETURNING id", Long.class);
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'MAGTECH', '{\"a\":1}', 'ACTIVE', 'SEED'), (?, 'MAGTECH', '{\"a\":2}', 'BROKEN', 'SEED'), "
        + "(?, 'OAI', '{}', 'ACTIVE', 'SEED')", cn, cn, cn);
    jdbc.update("INSERT INTO layer_health (layer, day, state, failed_runs, yield, median, miss_share) VALUES "
        + "('OPENALEX', (now() AT TIME ZONE 'UTC')::date - 1, 'FAILING', 1, 10, 70, 0.4), "
        + "('OPENALEX', (now() AT TIME ZONE 'UTC')::date - 30, 'OK', 0, 10, 70, 0)");
    jdbc.update("DELETE FROM layer_contribution");
    jdbc.update("INSERT INTO layer_contribution (day, layer, country, first_found, only_found) VALUES "
        + "('2026-01-01', 'MAGTECH', 'CN', 99, 99), "
        + "('2026-01-02', 'MAGTECH', 'CN', 2, 1), ('2026-01-02', 'MAGTECH', '', 1, 0), ('2026-01-02', 'OPENALEX', 'CN', 0, 0)");

    JsonNode s = new ObjectMapper().readTree(get("test-token").body());

    assertThat(s.path("layers").size()).isEqualTo(1); // the last 7 days only
    assertThat(s.at("/layers/0/layer").asString()).isEqualTo("OPENALEX");
    assertThat(s.at("/layers/0/state").asString()).isEqualTo("FAILING");
    assertThat(s.at("/routes/MAGTECH/ACTIVE").asLong()).isEqualTo(1);
    assertThat(s.at("/routes/MAGTECH/BROKEN").asLong()).isEqualTo(1);
    assertThat(s.at("/routes/OAI/ACTIVE").asLong()).isEqualTo(1);
    assertThat(s.at("/contribution/MAGTECH/first").asLong()).isEqualTo(3);
    assertThat(s.at("/contribution/MAGTECH/only").asLong()).isEqualTo(1);
    assertThat(s.at("/contribution/OPENALEX/first").asLong()).isEqualTo(0);
    assertThat(s.at("/contributionByCountry/CN/MAGTECH/first").asLong()).isEqualTo(2);
    assertThat(s.at("/contributionByCountry/CN/MAGTECH/only").asLong()).isEqualTo(1);
  }

  @Test
  void institutionalCounts() throws Exception {
    jdbc.update("DELETE FROM institutional_fetch");
    jdbc.update("INSERT INTO institutional_fetch (doi, publisher, api, status, bytes, outcome, fetched_at) VALUES "
        + "('10.1016/a', 'ELSEVIER', 'ELSEVIER_TDM', 200, 10, 'OK', now()), "
        + "('10.1016/b', 'ELSEVIER', 'ELSEVIER_TDM', 404, 0, 'NOT_FOUND', now() - interval '10 days'), "
        + "('10.1016/c', 'ELSEVIER', 'ELSEVIER_TDM', 200, 10, 'OK', now() - interval '40 days'), "
        + "('10.1111/d', 'WILEY', 'WILEY_TDM', 200, 10, 'OK', now() - interval '2 days')");

    JsonNode s = new ObjectMapper().readTree(get("test-token").body());

    assertThat(s.at("/institutional/ELSEVIER/today").asLong()).isEqualTo(1);
    assertThat(s.at("/institutional/ELSEVIER/last30Days").asLong()).isEqualTo(2);
    assertThat(s.at("/institutional/WILEY/today").asLong()).isEqualTo(0);
    assertThat(s.at("/institutional/WILEY/last30Days").asLong()).isEqualTo(1);
    jdbc.update("DELETE FROM institutional_fetch");
  }
}
