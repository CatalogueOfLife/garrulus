package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WorkControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();
  static final Path BLOBS;

  static {
    try {
      BLOBS = Files.createTempDirectory("garrulus-web-blobs");
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void blobs(DynamicPropertyRegistry r) {
    r.add("nametracker.blobs.dir", BLOBS::toString);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BlobStore blobs;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM name_string");
  }

  HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  long work(String doi, String title) {
    return jdbc.queryForObject(
        "INSERT INTO work (doi, title, abstract, publication_date, year, state, origin, text_level, authors, license) "
            + "VALUES (?, ?, 'An abstract', '2026-02-03', 2026, 'INDEXED', 'LIVE', 'FULLTEXT', "
            + "'[{\"name\":\"A One\",\"orcid\":null},{\"name\":\"B Two\"},{\"name\":\"C\"},{\"name\":\"D\"}]'::jsonb, 'CC-BY-4.0') "
            + "RETURNING id",
        Long.class, doi, title);
  }

  long nameString(String name) {
    return jdbc.queryForObject("INSERT INTO name_string (name, kind, rank) VALUES (?, 'SCIENTIFIC', 'species') RETURNING id",
        Long.class, name);
  }

  void content(long work, boolean retained, String docSha) {
    jdbc.update("INSERT INTO content (work_id, route, kind, license, retained, doc_sha256) "
        + "VALUES (?, 'PENSOFT', 'TAXPUB', 'CC-BY-4.0', ?, ?)", work, retained, docSha);
  }

  @Test
  void unknownCodeOrTaxgroupIs400() throws Exception {
    assertThat(get("/api/works?code=NOPE").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?taxgroup=Unicorns").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?facet=bogus").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?code=ZOOLOGICAL&taxgroup=Insects").statusCode()).isEqualTo(200);
  }

  @Test
  void limitOver100Is400() throws Exception {
    assertThat(get("/api/works?limit=101").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?limit=0").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?offset=10001").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?limit=100&offset=10000").statusCode()).isEqualTo(200);
  }

  @Test
  void searchReturnsSummariesAndFacets() throws Exception {
    long w = work("10.1/a", "Weevils");
    long ns = nameString("Abies alba");
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, true, false, false, false, 's', 'MATCH', 'U1', '{U1,R}', 0.9, true)", w, ns);
    jdbc.update("UPDATE work SET codes = '{ZOOLOGICAL}' WHERE id = ?", w);
    JsonNode r = JSON.readTree(get("/api/works?facet=code&facet=year&taxon=R").body());
    assertThat(r.path("total").asLong()).isEqualTo(1);
    assertThat(r.path("limit").asInt()).isEqualTo(20);
    JsonNode s = r.path("results").get(0);
    assertThat(s.path("id").asLong()).isEqualTo(w);
    assertThat(s.path("authorCount").asInt()).isEqualTo(4);
    assertThat(s.path("authors")).hasSize(3);
    assertThat(s.path("mainNames").get(0).path("name").asString()).isEqualTo("Abies alba");
    assertThat(s.path("mainNames").get(0).path("usageId").asString()).isEqualTo("U1");
    assertThat(r.path("facets").path("code").get(0).path("value").asString()).isEqualTo("ZOOLOGICAL");
    assertThat(r.path("facets").path("year").get(0).path("count").asLong()).isEqualTo(1);
  }

  @Test
  void searchSummaryCarriesRankActsAndPages() throws Exception {
    long w = work("10.1/rk", "Ranks");
    jdbc.update("UPDATE work SET volume='1297', issue='3', pages='311-337' WHERE id = ?", w);
    long ns = nameString("Abies alba");
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, act_types, best_snippet, placement_method, usage_id, taxon_path, salience, "
        + "main_subject) VALUES (?, ?, 1, 1, true, false, false, false, '{SP_NOV}', 's', 'MATCH', 'U1', '{U1,R}', "
        + "0.9, true)", w, ns);
    jdbc.update("INSERT INTO xr_release (key, alias, current) VALUES (1, 'x', true) ON CONFLICT DO NOTHING");
    jdbc.update("INSERT INTO name_match (name_string_id, hint_key, release_key, match_type, usage_id, usage_rank) "
        + "VALUES (?, '', 1, 'EXACT', 'U1', 'species')", ns);
    JsonNode s = JSON.readTree(get("/api/works").body()).path("results").get(0);
    assertThat(s.path("volume").asString()).isEqualTo("1297");
    assertThat(s.path("issue").asString()).isEqualTo("3");
    assertThat(s.path("pages").asString()).isEqualTo("311-337");
    JsonNode n = s.path("mainNames").get(0);
    assertThat(n.path("rank").asString()).isEqualTo("species");
    assertThat(n.path("kind").asString()).isEqualTo("SCIENTIFIC");
    assertThat(n.path("usageRank").asString()).isEqualTo("species");
    assertThat(n.path("actTypes").get(0).asString()).isEqualTo("SP_NOV");
  }

  @Test
  void theNewestFullTextRowIsServed() throws Exception {
    long w = work("10.1/routes", "Two routes");
    String sha = blobs.put(FinderJson.mapper().writeValueAsBytes(
        new Document("d", null, List.of(new Block("b1", BlockType.PARAGRAPH, 0, "Open text", List.of(), List.of())), Map.of())));
    jdbc.update("UPDATE work SET names_doc_sha = ? WHERE id = ?", sha, w);
    // an older closed route (lower id), then a later open one: the later one is the work's full text
    jdbc.update("INSERT INTO content (work_id, route, kind, license, retained, doc_sha256, fetched_at) "
        + "VALUES (?, 'PMC', 'JATS', 'other', false, 'closed', now() - interval '1 day')", w);
    jdbc.update("INSERT INTO content (work_id, route, kind, license, retained, doc_sha256, fetched_at) "
        + "VALUES (?, 'PENSOFT', 'TAXPUB', 'CC-BY-4.0', true, ?, now())", w, sha);
    jdbc.update("INSERT INTO content (work_id, route, kind, license, retained, doc_sha256, fetched_at) "
        + "VALUES (?, 'ABSTRACT', 'ABSTRACT', null, true, 'abs', now() + interval '1 minute')", w);
    JsonNode d = JSON.readTree(get("/api/works/" + w).body());
    assertThat(d.path("retained").asBoolean()).isTrue();
    assertThat(d.path("licence").asString()).isEqualTo("CC-BY-4.0");
    assertThat(d.path("text").get(0).path("text").asString()).isEqualTo("Open text");
  }

  @Test
  void closedWorkHasNoText() throws Exception {
    long w = work("10.1/closed", "Closed");
    // retained = false: the blob may exist (it must not be served) or not
    String sha = blobs.put(FinderJson.mapper().writeValueAsBytes(
        new Document("d", null, List.of(new Block("b1", BlockType.PARAGRAPH, 0, "Secret text", List.of(), List.of())), Map.of())));
    content(w, false, sha);
    JsonNode d = JSON.readTree(get("/api/works/" + w).body());
    assertThat(d.path("retained").asBoolean()).isFalse();
    assertThat(d.has("text")).isTrue();
    assertThat(d.path("text").isNull()).isTrue();
    assertThat(d.path("abstract").asString()).isEqualTo("An abstract");
    assertThat(get("/api/works/999999").statusCode()).isEqualTo(404);
    jdbc.update("UPDATE work SET state = 'DISCOVERED' WHERE id = ?", w);
    assertThat(get("/api/works/" + w).statusCode()).isEqualTo(404);
  }

  @Test
  void openWorkTextSpansMatchMentions() throws Exception {
    long w = work("10.1/open", "Open");
    String text = "Fig. 𝔄 shows Abies alba (L.) Mill. in the field";
    int start = text.codePointCount(0, text.indexOf("Abies"));
    int end = start + "Abies alba (L.) Mill.".codePointCount(0, "Abies alba (L.) Mill.".length());
    Document doc = new Document("d", "en", List.of(
        new Block("b0", BlockType.HEADING, 1, "Intro", List.of(), List.of("Intro")),
        new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(), List.of("Intro"))), Map.of());
    String docSha = blobs.put(FinderJson.mapper().writeValueAsBytes(doc));
    content(w, true, docSha);
    jdbc.update("UPDATE work SET names_doc_sha = ? WHERE id = ?", docSha, w);
    long ns = nameString("Abies alba");
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet) VALUES (?, ?, 'b1', 'PARAGRAPH', ?, ?, 'Abies alba (L.) Mill.', 0.9, 's')",
        w, ns, start, end);
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, false, false, false, 's', 'MATCH', 'U1', '{U1,R}', 0.9, true)", w, ns);
    jdbc.update("INSERT INTO classification_context (work_id, local_id, verbatim, ranks, scope, block_id) "
        + "VALUES (?, 'c1', 'Fam. Pinaceae', '{\"family\":\"Pinaceae\"}', 'BLOCK', 'b1')", w);
    jdbc.update("INSERT INTO xr_release (key, alias, current) VALUES (1, 'XR', true) ON CONFLICT DO NOTHING");
    jdbc.update("INSERT INTO name_match (name_string_id, hint_key, release_key, match_type, usage_id, usage_name, "
        + "usage_rank, usage_status, taxon_path, classification) VALUES (?, '', 1, 'EXACT', 'U1', 'Abies alba', 'species', "
        + "'accepted', '{U1,R}', '[{\"id\":\"R\",\"name\":\"Plantae\",\"rank\":\"kingdom\"},{\"id\":\"F\",\"name\":\"Pinaceae\",\"rank\":\"family\"}]')",
        ns);

    JsonNode d = JSON.readTree(get("/api/works/" + w).body());
    assertThat(d.path("retained").asBoolean()).isTrue();
    assertThat(d.path("licence").asString()).isEqualTo("CC-BY-4.0");
    JsonNode blocks = d.path("text");
    assertThat(blocks).hasSize(2);
    JsonNode b1 = blocks.get(1);
    assertThat(b1.path("id").asString()).isEqualTo("b1");
    assertThat(blocks.get(0).path("mentions")).isEmpty();
    JsonNode m = b1.path("mentions").get(0);
    int s = m.path("startCp").asInt();
    int e = m.path("endCp").asInt();
    String t = b1.path("text").asString();
    int bs = t.offsetByCodePoints(0, s);
    int be = t.offsetByCodePoints(0, e);
    assertThat(t.substring(bs, be)).isEqualTo(m.path("verbatim").asString()).isEqualTo("Abies alba (L.) Mill.");
    assertThat(m.path("nameId").asLong()).isEqualTo(ns);

    JsonNode n = d.path("names").get(0);
    assertThat(n.path("name").asString()).isEqualTo("Abies alba");
    assertThat(n.path("placementMethod").asString()).isEqualTo("MATCH");
    assertThat(n.path("classification")).hasSize(2);
    assertThat(n.path("classification").get(1).path("name").asString()).isEqualTo("Pinaceae");
    assertThat(d.path("contexts").get(0).path("verbatim").asString()).isEqualTo("Fam. Pinaceae");
    assertThat(d.path("authors")).hasSize(4);

    // the content now points at another document than the one the names were found in: no text, no stale spans
    jdbc.update("UPDATE work SET names_doc_sha = 'other' WHERE id = ?", w);
    JsonNode stale = JSON.readTree(get("/api/works/" + w).body());
    assertThat(stale.path("retained").asBoolean()).isTrue();
    assertThat(stale.path("text").isNull()).isTrue();
    assertThat(stale.path("names")).hasSize(1);
  }

  @Test
  void typeMismatchIs400WithBody() throws Exception {
    HttpResponse<String> r = get("/api/works?limit=abc");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(r.body()).path("status").asInt()).isEqualTo(400);
  }
}
