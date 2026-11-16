package org.catalogueoflife.nametracker.web.api.public_;

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

class SourceControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
  }

  HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  long source(String title, String tier, boolean active, String journalType) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, active, journal_type, subfields, taxgroups, "
        + "taxgroup_shares, codes, oa_model, country, works_per_year) VALUES (?, ?, 'MANUAL', ?, ?, '{Taxonomy}', "
        + "'{Insects}', '{\"Insects\":0.9}'::jsonb, '{ZOOLOGICAL}', 'DIAMOND', 'DE', 40) RETURNING id",
        Long.class, title, tier, active, journalType);
  }

  @Test
  void sourcesHidesCandidatesAndExcluded() throws Exception {
    long core = source("Zootaxa", "CORE", true, "TAXONOMIC");
    long broad = source("PLOS ONE", "BROAD", true, "GENERAL");
    long cand = source("Candidate J", "CANDIDATE", true, "TAXONOMIC");
    long excl = source("Excluded J", "EXCLUDED", true, "TAXONOMIC");
    long inactive = source("Inactive J", "CORE", false, "TAXONOMIC");
    JsonNode r = JSON.readTree(get("/api/sources").body());
    assertThat(r.path("total").asInt()).isEqualTo(2);
    assertThat(r.path("results").get(0).path("title").asString()).isEqualTo("PLOS ONE");
    assertThat(r.path("results").get(1).path("title").asString()).isEqualTo("Zootaxa");
    assertThat(r.path("results").get(1).path("taxgroups").get(0).asString()).isEqualTo("Insects");
    assertThat(JSON.readTree(get("/api/sources?tier=CORE").body()).path("total").asInt()).isEqualTo(1);
    assertThat(JSON.readTree(get("/api/sources?journalType=GENERAL").body()).path("total").asInt()).isEqualTo(1);
    assertThat(JSON.readTree(get("/api/sources?q=zoo").body()).path("total").asInt()).isEqualTo(1);
    assertThat(get("/api/sources?tier=CANDIDATE").statusCode()).isEqualTo(400);
    assertThat(get("/api/sources/" + core).statusCode()).isEqualTo(200);
    assertThat(get("/api/sources/" + broad).statusCode()).isEqualTo(200);
    assertThat(get("/api/sources/" + cand).statusCode()).isEqualTo(404);
    assertThat(get("/api/sources/" + excl).statusCode()).isEqualTo(404);
    assertThat(get("/api/sources/" + inactive).statusCode()).isEqualTo(404);
  }

  @Test
  void sourceDetailHasCategorisationAndTenRecentWorks() throws Exception {
    long s = source("Zootaxa", "CORE", true, "TAXONOMIC");
    for (int i = 0; i < 12; i++) {
      jdbc.update("INSERT INTO work (doi, title, year, publication_date, state, origin, source_id, text_level) "
          + "VALUES (?, ?, 2024, ?::date, 'INDEXED', 'LIVE', ?, 'ABSTRACT_ONLY')",
          "10.1/" + i, "Work " + i, "2024-01-" + String.format("%02d", i + 1), s);
    }
    jdbc.update("INSERT INTO work (doi, title, year, state, origin, source_id) VALUES ('10.1/c', 'cand', 2024, 'MATCHED', 'LIVE', ?)", s);
    JsonNode r = JSON.readTree(get("/api/sources/" + s).body());
    assertThat(r.path("title").asString()).isEqualTo("Zootaxa");
    assertThat(r.path("journalType").asString()).isEqualTo("TAXONOMIC");
    assertThat(r.path("oaModel").asString()).isEqualTo("DIAMOND");
    assertThat(r.path("taxgroupShares").path("Insects").asDouble()).isEqualTo(0.9);
    assertThat(r.path("worksPerYear").asInt()).isEqualTo(40);
    assertThat(r.path("years")).hasSize(1);
    assertThat(r.path("years").get(0).path("year").asInt()).isEqualTo(2024);
    assertThat(r.path("years").get(0).path("works").asInt()).isEqualTo(12);
    assertThat(r.path("recentWorks")).hasSize(10);
    assertThat(r.path("recentWorks").get(0).path("title").asString()).isEqualTo("Work 11");
  }
}
