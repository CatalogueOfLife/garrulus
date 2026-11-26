package org.catalogueoflife.garrulus.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.garrulus.web.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class NameControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
    jdbc.update("DELETE FROM name_string");
  }

  /** Other ITs insert their own current release: leave none behind. */
  @AfterEach
  void dropReleases() {
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
  }

  HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  long work(String doi, int year, String date, String state) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, year, publication_date, state, origin, text_level) "
        + "VALUES (?, ?, ?, ?::date, ?, 'LIVE', 'ABSTRACT_ONLY') RETURNING id", Long.class, doi, "T " + doi, year, date, state);
  }

  long name(String name) {
    return jdbc.queryForObject("INSERT INTO name_string (name, kind, rank) VALUES (?, 'SCIENTIFIC', 'species') RETURNING id",
        Long.class, name);
  }

  void mention(long work, long name, boolean onlyReference) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, true, false, ?, 's', 'NONE', 0.5, false)", work, name, onlyReference);
  }

  @Test
  void nameSearchNeedsTwoChars() throws Exception {
    assertThat(get("/api/names").statusCode()).isEqualTo(400);
    assertThat(get("/api/names?q=a").statusCode()).isEqualTo(400);
    assertThat(get("/api/names?q=%20a%20").statusCode()).isEqualTo(400);
    assertThat(get("/api/names?q=ab").statusCode()).isEqualTo(200);
    assertThat(get("/api/names?q=ab&limit=0").statusCode()).isEqualTo(400);
  }

  @Test
  void nameSearchPrefixThenSimilarityOrderedByWorks() throws Exception {
    long a = name("Abies alba");
    long b = name("Abies balsamea");
    long typo = name("Abies albba");
    name("Quercus robur");
    long w1 = work("10.1/a", 2020, "2020-01-01", "INDEXED");
    long w2 = work("10.1/b", 2021, "2021-01-01", "INDEXED");
    long w3 = work("10.1/c", 2021, "2021-02-01", "MATCHED");
    mention(w1, b, false);
    mention(w2, b, false);
    mention(w1, a, false);
    mention(w3, a, false); // not indexed: does not count
    mention(w1, typo, false);
    JsonNode r = JSON.readTree(get("/api/names?q=Abies%20al").body());
    assertThat(r.get(0).path("name").asString()).isEqualTo("Abies alba");
    assertThat(r.get(0).path("works").asInt()).isEqualTo(1);
    assertThat(r.get(0).path("kind").asString()).isEqualTo("SCIENTIFIC");
    // prefix hits come before similarity hits
    assertThat(r.get(1).path("name").asString()).isEqualTo("Abies albba");
    // ordered by works descending within the prefix group, 'Abies' matches all three
    JsonNode all = JSON.readTree(get("/api/names?q=Abies").body());
    assertThat(all.get(0).path("name").asString()).isEqualTo("Abies balsamea");
    assertThat(all.get(0).path("works").asInt()).isEqualTo(2);
    assertThat(all).hasSize(3);
    // a trigram-similar name that is no prefix match appears, after all prefix hits
    long notPrefix = name("Abise alba");
    mention(w1, notPrefix, false);
    mention(w2, notPrefix, false);
    JsonNode sim = JSON.readTree(get("/api/names?q=Abies%20alb").body());
    boolean seenSimilar = false;
    boolean found = false;
    for (JsonNode n : sim) {
      boolean prefix = n.path("name").asString().startsWith("Abies alb");
      assertThat(seenSimilar && prefix).as("prefix hit after a similarity hit").isFalse();
      seenSimilar |= !prefix;
      if ("Abise alba".equals(n.path("name").asString())) {
        found = true;
        assertThat(n.path("works").asInt()).isEqualTo(2);
        assertThat(prefix).isFalse();
      }
    }
    assertThat(found).isTrue();
    // LIKE wildcards are literal
    assertThat(JSON.readTree(get("/api/names?q=%25%25").body())).isEmpty();
  }

  @Test
  void nameSearchBoundsItsCandidates() throws Exception {
    long w = work("10.1/z", 2020, "2020-01-01", "INDEXED");
    long last = 0;
    for (int i = 0; i < 250; i++) {
      last = name(String.format("Zed %03d", i));
    }
    mention(w, last, false); // 'Zed 249' is beyond the 200 candidates taken by name, so it never gets counted
    JsonNode r = JSON.readTree(get("/api/names?q=Zed&limit=50").body());
    assertThat(r).hasSize(50);
    for (JsonNode n : r) {
      assertThat(n.path("name").asString()).isNotEqualTo("Zed 249");
      assertThat(n.path("works").asInt()).isZero();
    }
    assertThat(r.get(0).path("name").asString()).isEqualTo("Zed 000");
  }

  @Test
  void nameTimelineCountsWorksPerYear() throws Exception {
    long n = name("Abies alba");
    long w1 = work("10.1/a", 2020, "2020-05-01", "INDEXED");
    long w2 = work("10.1/b", 2020, "2020-03-01", "NOTIFIED");
    long w3 = work("10.1/c", 2022, "2022-01-01", "INDEXED");
    long w4 = work("10.1/d", 2021, "2021-01-01", "MATCHED");
    mention(w1, n, false);
    mention(w2, n, true); // reference only still counts: every occurrence is listed
    mention(w3, n, false);
    mention(w4, n, false);
    jdbc.update("INSERT INTO xr_release (key, alias, current) VALUES (1, 'COL26.10 XR', true)");
    jdbc.update("INSERT INTO name_match (name_string_id, hint_key, release_key, match_type, usage_id, usage_name, "
        + "usage_rank, usage_status, accepted_id, classification) VALUES (?, '', 1, 'EXACT', 'U1', 'Abies alba Mill.', "
        + "'species', 'accepted', null, '[{\"id\":\"R\",\"name\":\"Plantae\",\"rank\":\"kingdom\"}]'::jsonb)", n);
    JsonNode r = JSON.readTree(get("/api/names/" + n).body());
    assertThat(r.path("name").asString()).isEqualTo("Abies alba");
    assertThat(r.path("timeline")).hasSize(2);
    assertThat(r.path("timeline").get(0).path("year").asInt()).isEqualTo(2020);
    assertThat(r.path("timeline").get(0).path("works").asInt()).isEqualTo(2);
    assertThat(r.path("timeline").get(1).path("year").asInt()).isEqualTo(2022);
    assertThat(r.path("timeline").get(1).path("works").asInt()).isEqualTo(1);
    assertThat(r.path("firstAppearance").path("workId").asLong()).isEqualTo(w2);
    assertThat(r.path("firstAppearance").path("publicationDate").asString()).isEqualTo("2020-03-01");
    assertThat(r.path("match").path("usageId").asString()).isEqualTo("U1");
    assertThat(r.path("match").path("classification").get(0).path("name").asString()).isEqualTo("Plantae");
  }

  void match(long name, String hintKey, String type, String usageId, String usageName) {
    jdbc.update("INSERT INTO name_match (name_string_id, hint_key, release_key, match_type, usage_id, usage_name, "
        + "usage_rank, classification) VALUES (?, ?, 1, ?, ?, ?, 'species', '[]'::jsonb)", name, hintKey, type, usageId,
        usageName);
  }

  void placed(long work, long name, String method, String usageId) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, true, false, false, 's', ?, ?, 0.5, false)", work, name, method, usageId);
  }

  @Test
  void nameMatchedOnlyWithHintsShowsItsMatch() throws Exception {
    jdbc.update("INSERT INTO xr_release (key, alias, current) VALUES (1, 'COL26.10 XR', true)");
    long n = name("Uenoites gregoryi");
    // matched with context hints and authorship only: no row with hint_key ''
    match(n, "family=Carabidae|order=Coleoptera|authorship=(Jeannel, 1937)", "exact", "7DD8W", "Uenoites gregoryi");
    placed(work("10.1/u", 2025, "2025-01-01", "INDEXED"), n, "MATCH", "7DD8W");
    JsonNode r = JSON.readTree(get("/api/names/" + n).body());
    assertThat(r.path("match").path("usageId").asString()).isEqualTo("7DD8W");

    // the usage the name is placed on most often wins over the hintless match
    long m = name("Aus bus");
    match(m, "", "none", null, null);
    match(m, "family=Ai", "exact", "A1", "Aus bus");
    match(m, "family=Bi", "exact", "B1", "Aus bus");
    long w1 = work("10.1/a1", 2025, "2025-01-01", "INDEXED");
    long w2 = work("10.1/a2", 2025, "2025-01-02", "INDEXED");
    long w3 = work("10.1/a3", 2025, "2025-01-03", "INDEXED");
    placed(w1, m, "MATCH", "B1");
    placed(w2, m, "MATCH", "B1");
    placed(w3, m, "MATCH", "A1");
    assertThat(JSON.readTree(get("/api/names/" + m).body()).path("match").path("usageId").asString()).isEqualTo("B1");

    // without placements: the hintless match first, then a hinted one with a usage before one without
    long k = name("Cus dus");
    match(k, "family=Ci", "exact", "C1", "Cus dus");
    match(k, "", "exact", "C0", "Cus dus");
    assertThat(JSON.readTree(get("/api/names/" + k).body()).path("match").path("usageId").asString()).isEqualTo("C0");
    long j = name("Eus fus");
    match(j, "family=Ai", "none", null, null);
    match(j, "family=Ei", "exact", "E1", "Eus fus");
    assertThat(JSON.readTree(get("/api/names/" + j).body()).path("match").path("usageId").asString()).isEqualTo("E1");
  }

  @Test
  void nameWithoutMatchOrWorksAndUnknownName() throws Exception {
    long n = name("Zzz zzz");
    JsonNode r = JSON.readTree(get("/api/names/" + n).body());
    assertThat(r.path("match").isNull()).isTrue();
    assertThat(r.path("firstAppearance").isNull()).isTrue();
    assertThat(r.path("timeline")).isEmpty();
    assertThat(get("/api/names/999999").statusCode()).isEqualTo(404);
  }
}
