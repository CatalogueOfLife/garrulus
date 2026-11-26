package org.catalogueoflife.garrulus.web.api.public_;

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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TaxonControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.catalogueoflife.garrulus.store.stats.StatsMapper stats;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_string");
    jdbc.update("DELETE FROM taxon_stats");
    jdbc.update("DELETE FROM taxon_language_stats");
  }

  HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Response headers of a GET with an Origin header (java.net.http forbids setting Origin). */
  HttpHeaders withOrigin(String path, String origin) {
    return RestClient.create("http://localhost:" + port).get().uri(path).header("Origin", origin)
        .exchange((req, res) -> res.getHeaders());
  }

  void workWithName(String doi, String taxon, boolean main, boolean onlyRef) {
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, year, publication_date, state, origin, text_level) "
        + "VALUES (?, ?, 2024, '2024-01-01', 'INDEXED', 'LIVE', 'ABSTRACT_ONLY') RETURNING id", Long.class, doi, "T " + doi);
    long n = jdbc.queryForObject("INSERT INTO name_string (name, kind, rank) VALUES (?, 'SCIENTIFIC', 'species') RETURNING id",
        Long.class, "Name " + doi);
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, true, false, ?, 's', 'MATCH', 'U1', ARRAY['U1', ?], 0.5, ?)", w, n, onlyRef, taxon, main);
  }

  @Test
  void taxonStatsFromTaxonStatsTable() throws Exception {
    jdbc.update("INSERT INTO taxon_stats (taxon_id, year, works, main_subject_works) VALUES ('T1', 2023, 5, 2), "
        + "('T1', 2021, 3, 1), ('T2', 2023, 9, 9)");
    JsonNode r = JSON.readTree(get("/api/taxa/T1/stats").body());
    assertThat(r.path("taxonId").asString()).isEqualTo("T1");
    assertThat(r.path("total").asInt()).isEqualTo(8);
    assertThat(r.path("mainSubjectTotal").asInt()).isEqualTo(3);
    assertThat(r.path("years")).hasSize(2);
    assertThat(r.path("years").get(0).path("year").asInt()).isEqualTo(2021);
    assertThat(r.path("years").get(1).path("mainSubjectWorks").asInt()).isEqualTo(2);
    JsonNode none = JSON.readTree(get("/api/taxa/NOPE/stats").body());
    assertThat(none.path("total").asInt()).isZero();
    assertThat(none.path("years")).isEmpty();
  }

  @Test
  void taxonWorksDefaultToMainSubjectAndDelegateToWorkSearch() throws Exception {
    workWithName("10.1/main", "TX", true, false);
    workWithName("10.1/minor", "TX", false, false);
    workWithName("10.1/other", "OTHER", true, false);
    JsonNode main = JSON.readTree(get("/api/taxa/TX/works").body());
    assertThat(main.path("total").asInt()).isEqualTo(1);
    assertThat(main.path("results").get(0).path("doi").asString()).isEqualTo("10.1/main");
    assertThat(main.path("limit").asInt()).isEqualTo(20);
    JsonNode all = JSON.readTree(get("/api/taxa/TX/works?mainSubjectOnly=false").body());
    assertThat(all.path("total").asInt()).isEqualTo(2);
    JsonNode viaWorks = JSON.readTree(get("/api/works?taxon=TX&mainSubjectOnly=true").body());
    assertThat(viaWorks).isEqualTo(main);
    assertThat(get("/api/taxa/TX/works?limit=101").statusCode()).isEqualTo(400);
  }

  @Test
  void taxaCorsAllowsPortalOriginOnly() throws Exception {
    HttpHeaders ok = withOrigin("/api/taxa/T1/stats", "https://www.catalogueoflife.org");
    assertThat(ok.getFirst("Access-Control-Allow-Origin")).isEqualTo("https://www.catalogueoflife.org");
    HttpHeaders dev = withOrigin("/api/taxa/T1/works", "https://dev.catalogueoflife.org");
    assertThat(dev.getFirst("Access-Control-Allow-Origin")).isEqualTo("https://dev.catalogueoflife.org");
    HttpHeaders evil = withOrigin("/api/taxa/T1/stats", "https://evil.example");
    assertThat(evil.getFirst("Access-Control-Allow-Origin")).isNull();
    HttpHeaders works = withOrigin("/api/works", "https://www.catalogueoflife.org");
    assertThat(works.getFirst("Access-Control-Allow-Origin")).isNull();
    // preflight: only GET is allowed
    HttpHeaders pre = RestClient.create("http://localhost:" + port).options().uri("/api/taxa/T1/stats")
        .header("Origin", "https://www.catalogueoflife.org").header("Access-Control-Request-Method", "GET")
        .exchange((req, res) -> res.getHeaders());
    assertThat(pre.getFirst("Access-Control-Allow-Methods")).isEqualTo("GET");
    int post = RestClient.create("http://localhost:" + port).options().uri("/api/taxa/T1/stats")
        .header("Origin", "https://www.catalogueoflife.org").header("Access-Control-Request-Method", "POST")
        .exchange((req, res) -> res.getStatusCode().value());
    assertThat(post).isEqualTo(403);
  }

  @Test
  void mentionedInOnTaxonWorks() throws Exception {
    workWithName("10.1/abs", "TXM", true, false);
    jdbc.update("UPDATE work_name SET in_abstract = false, in_title = true WHERE work_id = (SELECT id FROM work WHERE doi = '10.1/abs')");
    workWithName("10.1/abstract", "TXM", true, false);
    assertThat(JSON.readTree(get("/api/taxa/TXM/works?mentionedIn=TITLE").body()).path("total").asInt()).isEqualTo(1);
    assertThat(JSON.readTree(get("/api/taxa/TXM/works?mentionedIn=TITLE&mentionedIn=ABSTRACT").body()).path("total").asInt()).isEqualTo(2);
    assertThat(JSON.readTree(get("/api/taxa/TXM/works?mentionedIn=BODY").body()).path("total").asInt()).isEqualTo(2);
    assertThat(get("/api/taxa/TXM/works?mentionedIn=X").statusCode()).isEqualTo(400);
  }

  @Test
  void statsLanguages() throws Exception {
    workWithName("10.8/a", "TL", true, false);
    workWithName("10.8/b", "TL", false, false);
    workWithName("10.8/c", "TL", true, true); // cited only: not counted
    jdbc.update("UPDATE work SET language = 'zh' WHERE doi = '10.8/a'");
    jdbc.update("UPDATE work SET language = 'en' WHERE doi = '10.8/c'");
    stats.rebuildTaxonLanguageStats();
    JsonNode r = JSON.readTree(get("/api/taxa/TL/stats").body());
    assertThat(r.path("languages")).hasSize(2);
    long zh = 0;
    long undecided = 0;
    for (JsonNode l : r.path("languages")) {
      assertThat(l.path("year").asInt()).isEqualTo(2024);
      if (l.path("language").asString().equals("zh")) zh = l.path("works").asLong();
      if (l.path("language").asString().isEmpty()) undecided = l.path("works").asLong();
    }
    assertThat(zh).isEqualTo(1);
    assertThat(undecided).isEqualTo(1);
    // the tree above U1 holds both works too
    assertThat(JSON.readTree(get("/api/taxa/U1/stats").body()).path("languages")).hasSize(2);
    assertThat(JSON.readTree(get("/api/taxa/NOPE/stats").body()).path("languages")).isEmpty();
  }
}
