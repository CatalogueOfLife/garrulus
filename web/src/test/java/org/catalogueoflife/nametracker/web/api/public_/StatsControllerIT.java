package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The language statistics are read from the tables REBUILD_STATS fills; the tests fill them with its statements. */
class StatsControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired StatsMapper stats;

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

  void work(String doi, String state, int year, String language, String taxgroups, String codes, Long source) {
    jdbc.update("INSERT INTO work (doi, title, year, state, origin, language, taxgroups, codes, source_id) "
        + "VALUES (?, 't', ?, ?, 'LIVE', ?, ?::text[], ?::text[], ?)", doi, year, state, language, taxgroups, codes, source);
  }

  JsonNode languages(String query) throws Exception {
    return JSON.readTree(get("/api/stats/languages" + query).body());
  }

  @Test
  void perYearAndTaxgroup() throws Exception {
    long de = jdbc.queryForObject("INSERT INTO source (title, tier, origin, country) VALUES ('J', 'CORE', 'MANUAL', 'DE') RETURNING id", Long.class);
    work("10.1/a", "INDEXED", 2025, "zh", "{Insects,Animals}", "{ZOOLOGICAL}", de);
    work("10.1/b", "INDEXED", 2025, "zh", "{Plants}", "{BOTANICAL}", de);
    work("10.1/c", "INDEXED", 2025, "en", "{Insects,Animals}", "{ZOOLOGICAL}", null);
    work("10.1/d", "NOTIFIED", 2024, "en", "{Insects,Animals}", "{ZOOLOGICAL}", de);
    stats.rebuildLanguageStats();

    // the whole set counts each work once although it holds several groups and codes
    JsonNode all = languages("");
    long total = 0;
    for (JsonNode r : all) total += r.path("works").asLong();
    assertThat(total).isEqualTo(4);
    // ordered by year, then works descending
    assertThat(all.get(0).path("year").asInt()).isEqualTo(2024);
    assertThat(all.get(1).path("year").asInt()).isEqualTo(2025);
    assertThat(all.get(1).path("works").asLong()).isEqualTo(2);
    assertThat(all.get(1).path("language").asString()).isEqualTo("zh");

    JsonNode insects = languages("?taxgroup=Insects");
    assertThat(insects).hasSize(3);
    JsonNode insects25 = languages("?taxgroup=Insects&yearFrom=2025");
    assertThat(insects25).hasSize(2);
    assertThat(languages("?taxgroup=Insects&yearTo=2024")).hasSize(1);
    assertThat(languages("?code=BOTANICAL")).hasSize(1);
    assertThat(languages("?country=DE")).hasSize(2);
    // one dimension at most: a combination is a 400 saying so
    HttpResponse<String> two = get("/api/stats/languages?country=DE&taxgroup=Insects");
    assertThat(two.statusCode()).isEqualTo(400);
    assertThat(two.body()).contains("at most one of taxgroup, code and country");
    assertThat(get("/api/stats/languages?code=ZOOLOGICAL&taxgroup=Insects&country=DE").statusCode()).isEqualTo(400);
    assertThat(languages("?country=FR")).isEmpty();
    assertThat(get("/api/stats/languages?taxgroup=Unicorns").statusCode()).isEqualTo(400);
    assertThat(get("/api/stats/languages?code=NOPE").statusCode()).isEqualTo(400);
  }

  @Test
  void undecidedAsEmpty() throws Exception {
    work("10.2/a", "INDEXED", 2025, null, "{}", "{}", null);
    stats.rebuildLanguageStats();
    JsonNode r = languages("");
    assertThat(r).hasSize(1);
    assertThat(r.get(0).path("language").asString()).isEmpty();
    assertThat(r.get(0).path("works").asLong()).isEqualTo(1);
  }

  @Test
  void onlyListedWorks() throws Exception {
    work("10.3/a", "INDEXED", 2025, "fr", "{}", "{}", null);
    work("10.3/b", "NAMES_FOUND", 2025, "fr", "{}", "{}", null);
    work("10.3/c", "MATCHED", 2025, "fr", "{}", "{}", null);
    stats.rebuildLanguageStats();
    assertThat(languages("")).hasSize(1);
    assertThat(languages("").get(0).path("works").asLong()).isEqualTo(1);
    // a rebuild drops what is no longer listed and the read never scans work
    jdbc.update("UPDATE work SET state = 'NAMES_FOUND'");
    stats.rebuildLanguageStats();
    assertThat(languages("")).isEmpty();
  }
}
