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
  void withoutToken401() throws Exception {
    assertThat(get(null).statusCode()).isEqualTo(401);
    assertThat(get("wrong").statusCode()).isEqualTo(401);
  }
}
