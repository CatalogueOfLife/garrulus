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

class AdminLayersControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM source_layer_coverage");
    jdbc.update("DELETE FROM layer_contribution");
    jdbc.update("DELETE FROM layer_contribution_language");
    jdbc.update("DELETE FROM layer_health");
    jdbc.update("DELETE FROM source");
  }

  HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) b.header("X-Admin-Token", token);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthContributionAndCoverage() throws Exception {
    jdbc.update("INSERT INTO layer_health (layer, day, state, failed_runs, yield, median, miss_share) VALUES "
        + "('OAI', current_date, 'OK', 0, 40, 38, 0.1), ('OAI', current_date - 3, 'DEGRADED', 1, 10, 38, 0.4), "
        + "('OAI', current_date - 60, 'FAILING', 5, 0, 38, 0.9), ('CROSSREF', current_date, 'OK', 0, 900, 880, 0.05)");
    jdbc.update("INSERT INTO layer_contribution (day, layer, country, first_found, only_found) VALUES "
        + "(current_date, 'OAI', 'BR', 12, 3), (current_date, 'OAI', '', 2, 1), (current_date, 'CROSSREF', 'BR', 5, 0), "
        + "(current_date - 1, 'OAI', 'BR', 99, 99)");
    // 205 journals with misses: only the 200 with the most are listed
    jdbc.update("INSERT INTO source (title, tier, origin, country) SELECT 'J' || g, 'CORE', 'MANUAL', "
        + "CASE WHEN g = 205 THEN 'BR' END FROM generate_series(1, 205) g");
    jdbc.update("INSERT INTO source_layer_coverage (source_id, layer, sightings_28d, misses) "
        + "SELECT id, 'OAI', 10, (substr(title, 2))::int FROM source");

    HttpResponse<String> r = get("/api/admin/layers", "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    JsonNode v = JSON.readTree(r.body());
    // 28 days by default: the day 60 days back is out
    assertThat(v.path("health").size()).isEqualTo(3);
    assertThat(v.at("/health/0/layer").asString()).isEqualTo("CROSSREF");
    assertThat(v.at("/contribution/OAI/first").asLong()).isEqualTo(14);
    assertThat(v.at("/contribution/OAI/only").asLong()).isEqualTo(4);
    assertThat(v.at("/contributionByCountry/BR/OAI/first").asLong()).isEqualTo(12);
    assertThat(v.at("/contributionByCountry/UNKNOWN/OAI/only").asLong()).isEqualTo(1);
    JsonNode cov = v.path("coverage");
    assertThat(cov.size()).isEqualTo(200);
    assertThat(cov.get(0).path("title").asString()).isEqualTo("J205");
    assertThat(cov.get(0).path("misses").asInt()).isEqualTo(205);
    assertThat(cov.get(0).path("country").asString()).isEqualTo("BR");
    assertThat(cov.get(0).path("layer").asString()).isEqualTo("OAI");
    assertThat(cov.get(0).path("sightings28d").asInt()).isEqualTo(10);
    assertThat(cov.get(199).path("misses").asInt()).isEqualTo(6);

    JsonNode ninety = JSON.readTree(get("/api/admin/layers?days=90", "test-token").body());
    assertThat(ninety.path("health").size()).isEqualTo(4);
    JsonNode one = JSON.readTree(get("/api/admin/layers?days=1", "test-token").body());
    assertThat(one.path("health").size()).isEqualTo(2); // today's rows
  }

  @Test
  void daysOutOfRangeIs400() throws Exception {
    assertThat(get("/api/admin/layers?days=0", "test-token").statusCode()).isEqualTo(400);
    assertThat(get("/api/admin/layers?days=91", "test-token").statusCode()).isEqualTo(400);
    assertThat(get("/api/admin/layers?days=90", "test-token").statusCode()).isEqualTo(200);
    assertThat(get("/api/admin/layers", null).statusCode()).isEqualTo(401);
  }

  @Test
  void languageBreakdown() throws Exception {
    jdbc.update("DELETE FROM layer_contribution_language");
    jdbc.update("INSERT INTO layer_contribution_language (day, layer, language, first_found, only_found) VALUES "
        + "(current_date, 'MAGTECH', 'zh', 7, 2), (current_date, 'OAI', 'zh', 1, 0), (current_date, 'OAI', '', 3, 1), "
        + "(current_date - 1, 'OAI', 'zh', 99, 99)");
    JsonNode v = JSON.readTree(get("/api/admin/layers", "test-token").body());
    assertThat(v.at("/contributionByLanguage/zh/MAGTECH/first").asLong()).isEqualTo(7);
    assertThat(v.at("/contributionByLanguage/zh/MAGTECH/only").asLong()).isEqualTo(2);
    assertThat(v.at("/contributionByLanguage/zh/OAI/first").asLong()).isEqualTo(1);
    assertThat(v.at("/contributionByLanguage/UNDECIDED/OAI/first").asLong()).isEqualTo(3);
    assertThat(v.at("/contributionByLanguage/UNDECIDED/OAI/only").asLong()).isEqualTo(1);
  }
}
