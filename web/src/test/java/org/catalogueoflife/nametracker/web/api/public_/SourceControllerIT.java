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

  @Test
  void ceasedFieldsAndFilter() throws Exception {
    long successor = source("New Journal", "CORE", true, "TAXONOMIC");
    long ceased = source("Old Journal", "CORE", true, "TAXONOMIC");
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'TITLE_CHANGE', last_issue_year = 2019, "
        + "continued_by = ? WHERE id = ?", successor, ceased);
    JsonNode d = JSON.readTree(get("/api/sources/" + ceased).body());
    assertThat(d.path("publicationStatus").asString()).isEqualTo("CEASED");
    assertThat(d.path("ceasedReason").asString()).isEqualTo("TITLE_CHANGE");
    assertThat(d.path("lastIssueYear").asInt()).isEqualTo(2019);
    assertThat(d.path("continuedBy").path("id").asLong()).isEqualTo(successor);
    assertThat(d.path("continuedBy").path("title").asString()).isEqualTo("New Journal");
    JsonNode live = JSON.readTree(get("/api/sources/" + successor).body());
    assertThat(live.path("publicationStatus").asString()).isEqualTo("ACTIVE");
    assertThat(live.path("continuedBy").isNull()).isTrue();

    JsonNode all = JSON.readTree(get("/api/sources").body());
    assertThat(all.path("total").asInt()).isEqualTo(2);
    JsonNode only = JSON.readTree(get("/api/sources?publicationStatus=CEASED").body());
    assertThat(only.path("total").asInt()).isEqualTo(1);
    assertThat(only.path("results").get(0).path("title").asString()).isEqualTo("Old Journal");
    assertThat(only.path("results").get(0).path("lastIssueYear").asInt()).isEqualTo(2019);
    assertThat(JSON.readTree(get("/api/sources?publicationStatus=ACTIVE").body()).path("total").asInt()).isEqualTo(1);
    assertThat(get("/api/sources?publicationStatus=GONE").statusCode()).isEqualTo(400);

    // a successor that is not itself public is not named
    jdbc.update("UPDATE source SET tier = 'CANDIDATE' WHERE id = ?", successor);
    assertThat(JSON.readTree(get("/api/sources/" + ceased).body()).path("continuedBy").isNull()).isTrue();
  }

  @Test
  void ceasedWorksStayListed() throws Exception {
    long ceased = source("Old Journal", "CORE", true, "TAXONOMIC");
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED', last_issue_year = 2019 "
        + "WHERE id = ?", ceased);
    jdbc.update("INSERT INTO work (doi, title, year, publication_date, state, origin, source_id, text_level) "
        + "VALUES ('10.1/old', 'An old paper', 2019, '2019-05-01', 'INDEXED', 'LIVE', ?, 'ABSTRACT_ONLY')", ceased);
    JsonNode d = JSON.readTree(get("/api/sources/" + ceased).body());
    assertThat(d.path("recentWorks")).hasSize(1);
    assertThat(d.path("years").get(0).path("year").asInt()).isEqualTo(2019);
    JsonNode works = JSON.readTree(get("/api/works?source=" + ceased).body());
    assertThat(works.path("total").asInt()).isEqualTo(1);
  }

  @Test
  void journalIdentifiers() throws Exception {
    long s = source("Phytotaxa", "CORE", true, "TAXONOMIC");
    jdbc.update("UPDATE source SET issns = '{1179-3155,1179-3163}', issn_l = '1179-3155', openalex_id = 'S123' WHERE id = ?", s);
    jdbc.update("INSERT INTO source_identifier (source_id, scheme, value, origin) VALUES (?, 'IPNI_PUB', '20011462-1', 'T')", s);
    jdbc.update("INSERT INTO source_identifier (source_id, scheme, value, origin) VALUES (?, 'BPH', '999', 'T')", s);
    JsonNode ids = JSON.readTree(get("/api/sources/" + s).body()).path("identifiers");
    java.util.Map<String, String> urls = new java.util.LinkedHashMap<>();
    for (JsonNode n : ids) {
      urls.put(n.path("scheme").asString() + ":" + n.path("value").asString(), n.path("url").isNull() ? null : n.path("url").asString());
    }
    assertThat(urls).containsEntry("ISSN:1179-3155", "https://portal.issn.org/resource/ISSN/1179-3155")
        .containsEntry("ISSN:1179-3163", "https://portal.issn.org/resource/ISSN/1179-3163")
        .containsEntry("ISSN_L:1179-3155", "https://portal.issn.org/resource/ISSN/1179-3155")
        .containsEntry("OPENALEX:S123", "https://openalex.org/sources/S123")
        .containsEntry("IPNI_PUB:20011462-1", "https://www.ipni.org/p/20011462-1")
        .containsEntry("BPH:999", null);
    assertThat(ids.valueStream().map(n -> n.path("scheme").asString()).toList())
        .containsExactly("ISSN", "ISSN", "ISSN_L", "OPENALEX", "IPNI_PUB", "BPH");
  }

  @Test
  void detailLanguages() throws Exception {
    long s = source("Zootaxa", "CORE", true, "TAXONOMIC");
    long other = source("Other", "CORE", true, "TAXONOMIC");
    for (String[] w : new String[][] {{"a", "2024", "zh", "INDEXED"}, {"b", "2024", "zh", "INDEXED"},
        {"c", "2025", null, "INDEXED"}, {"d", "2025", "en", "NAMES_FOUND"}}) {
      jdbc.update("INSERT INTO work (doi, title, year, state, origin, language, source_id) VALUES (?, 't', ?, ?, 'LIVE', ?, ?)",
          "10.7/" + w[0], Integer.parseInt(w[1]), w[3], w[2], s);
    }
    jdbc.update("INSERT INTO work (doi, title, year, state, origin, language, source_id) VALUES ('10.7/o', 't', 2024, 'INDEXED', 'LIVE', 'fr', ?)", other);
    JsonNode d = JSON.readTree(get("/api/sources/" + s).body());
    assertThat(d.path("languages")).hasSize(2);
    assertThat(d.at("/languages/0/language").asString()).isEqualTo("zh");
    assertThat(d.at("/languages/0/year").asInt()).isEqualTo(2024);
    assertThat(d.at("/languages/0/works").asLong()).isEqualTo(2);
    assertThat(d.at("/languages/1/language").asString()).isEmpty();
    assertThat(d.at("/languages/1/year").asInt()).isEqualTo(2025);
  }
}
