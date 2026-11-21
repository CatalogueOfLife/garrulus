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

@org.springframework.context.annotation.Import(WorkControllerIT.ExtraScheme.class)
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

  /** An extra scheme bean, as a lookup module would define one. */
  @org.springframework.boot.test.context.TestConfiguration
  static class ExtraScheme {
    @org.springframework.context.annotation.Bean
    org.catalogueoflife.nametracker.store.ident.IdScheme testdb() {
      return org.catalogueoflife.nametracker.store.ident.IdScheme.define("TESTDB", "Test DB",
          java.util.EnumSet.of(org.catalogueoflife.nametracker.store.ident.IdScheme.Entity.WORK),
          raw -> raw.strip().isEmpty() ? null : raw.strip(),
          Map.of(org.catalogueoflife.nametracker.store.ident.IdScheme.Entity.WORK, "https://testdb.example/{v}"), 500, true);
    }
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
  void crossrefJatsAbstractWinsOverTheOpenAlexAbstract() throws Exception {
    long w = work("10.1/jats", "Crossref only");
    jdbc.update("UPDATE work SET abstract = NULL, abstract_jats = ? WHERE id = ?",
        "<jats:title>Abstract</jats:title><jats:p>Two species of <jats:italic>Kalanchoe</jats:italic>.</jats:p>"
            + "<jats:p>Second.</jats:p>", w);
    assertThat(JSON.readTree(get("/api/works/" + w).body()).path("abstract").asString())
        .isEqualTo("Two species of Kalanchoe.\n\nSecond.");
    // the JATS abstract wins, as in the finder; the OpenAlex one only stands in for a missing or empty JATS abstract
    jdbc.update("UPDATE work SET abstract = 'From OpenAlex' WHERE id = ?", w);
    assertThat(JSON.readTree(get("/api/works/" + w).body()).path("abstract").asString())
        .isEqualTo("Two species of Kalanchoe.\n\nSecond.");
    jdbc.update("UPDATE work SET abstract_jats = '<jats:p> </jats:p>' WHERE id = ?", w);
    assertThat(JSON.readTree(get("/api/works/" + w).body()).path("abstract").asString()).isEqualTo("From OpenAlex");
  }

  void mention(long work, long nameString, String block, String type, int start, String verbatim, String snippet) {
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet) VALUES (?, ?, ?, ?, ?, ?, ?, 0.9, ?)",
        work, nameString, block, type, start, start + verbatim.codePointCount(0, verbatim.length()), verbatim, snippet);
  }

  void workName(long work, long nameString, float salience) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, false, false, false, 'best', 'NONE', ?, false)", work, nameString, salience);
  }

  @Test
  void abstractMentionsAndBodySnippetsForAClosedWork() throws Exception {
    long w = work("10.1/abs", "Closed with abstract");
    jdbc.update("UPDATE work SET abstract = 'Abies alba and Picea abies sp. nov. grow here. Abies alba again.' WHERE id = ?", w);
    long abies = nameString("Abies alba");
    long picea = nameString("Picea abies");
    long pinus = nameString("Pinus nigra");
    workName(w, abies, 0.9f);
    workName(w, picea, 0.5f);
    workName(w, pinus, 0.2f);
    // the abstract in two blocks of the finder's document: b10 comes after b2
    mention(w, abies, "b10", "ABSTRACT", 0, "Abies alba", "x");
    mention(w, abies, "b2", "ABSTRACT", 0, "Abies alba", "x");
    mention(w, picea, "b2", "ABSTRACT", 15, "Picea abies", "x");
    jdbc.update("UPDATE mention SET act_type = 'SP_NOV' WHERE name_string_id = ?", picea);
    // body: a table cell before a paragraph, a reference only for Pinus
    mention(w, abies, "b20", "TABLE_CELL", 0, "Abies alba", "Abies alba 12");
    mention(w, abies, "b30", "PARAGRAPH", 4, "Abies alba", "The Abies alba stands were sampled.");
    mention(w, pinus, "b90", "REFERENCE", 0, "Pinus nigra", "Smith (2001) Pinus nigra.");

    JsonNode d = JSON.readTree(get("/api/works/" + w).body());
    JsonNode am = d.path("abstractMentions");
    assertThat(am).hasSize(3);
    assertThat(am.get(0).path("startCp").asInt()).isEqualTo(0);
    assertThat(am.get(0).path("nameId").asLong()).isEqualTo(abies);
    assertThat(am.get(1).path("startCp").asInt()).isEqualTo(15);
    assertThat(am.get(1).path("nameId").asLong()).isEqualTo(picea);
    assertThat(am.get(2).path("startCp").asInt()).isEqualTo(47);
    JsonNode acts = d.path("abstractActs");
    assertThat(acts).hasSize(1);
    assertThat(acts.get(0).path("startCp").asInt()).isEqualTo(27);
    assertThat(acts.get(0).path("endCp").asInt()).isEqualTo(35);
    assertThat(acts.get(0).path("actType").asString()).isEqualTo("SP_NOV");
    assertThat(acts.get(0).path("mentionId").asLong()).isEqualTo(am.get(1).path("id").asLong());

    JsonNode sn = d.path("snippets");
    assertThat(sn).hasSize(1);
    assertThat(sn.get(0).path("nameId").asLong()).isEqualTo(abies);
    assertThat(sn.get(0).path("blockType").asString()).isEqualTo("PARAGRAPH");
    assertThat(sn.get(0).path("text").asString()).isEqualTo("The Abies alba stands were sampled.");
    assertThat(sn.get(0).path("mentions").get(0).path("startCp").asInt()).isEqualTo(4);
    assertThat(sn.get(0).path("mentions").get(0).path("endCp").asInt()).isEqualTo(14);
  }

  @Test
  void searchCoversAnAbstractThatIsOnlyCrossrefJats() throws Exception {
    long w = work("10.1/fts", "Crossref only");
    jdbc.update("UPDATE work SET abstract = NULL, abstract_jats = ? WHERE id = ?",
        "<jats:title>Abstract</jats:title><jats:p>Named for <jats:italic>humbertii</jats:italic> under "
            + "CO<jats:sub>2</jats:sub> &amp; light.</jats:p><jats:p>Second</jats:p>", w);
    work("10.1/other", "Other");
    for (String q : List.of("humbertii", "CO2", "light second")) {
      JsonNode page = JSON.readTree(get("/api/works?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8)).body());
      assertThat(page.path("total").asInt()).as(q).isEqualTo(1);
      assertThat(page.path("results").get(0).path("id").asLong()).as(q).isEqualTo(w);
    }
    // markup and entity names are not words of the abstract
    assertThat(JSON.readTree(get("/api/works?q=italic").body()).path("total").asInt()).isZero();
    assertThat(JSON.readTree(get("/api/works?q=amp").body()).path("total").asInt()).isZero();
  }

  @Test
  void titleMentionsServedForClosedWork() throws Exception {
    long w = work("10.1/closed", "Revision of Syntelia");
    long ns = nameString("Syntelia");
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet) VALUES (?, ?, 't', 'TITLE', 12, 20, 'Syntelia', 0.9, 's')", w, ns);
    JsonNode s = JSON.readTree(get("/api/works").body()).path("results").get(0);
    JsonNode tm = s.path("titleMentions").get(0);
    assertThat(tm.path("startCp").asInt()).isEqualTo(12);
    assertThat(tm.path("endCp").asInt()).isEqualTo(20);
    assertThat(tm.path("nameId").asLong()).isEqualTo(ns);
    JsonNode d = JSON.readTree(get("/api/works/" + w).body());
    assertThat(d.path("titleMentions").get(0).path("startCp").asInt()).isEqualTo(12);
    assertThat(d.path("titleMentions").get(0).path("endCp").asInt()).isEqualTo(20);
    assertThat(d.path("titleMentions").get(0).path("nameId").asLong()).isEqualTo(ns);
    assertThat(d.path("text").isNull()).isTrue();
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

  private long sourceWithDiscipline(String title, String code) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, subfields, primary_subfield) "
        + "VALUES (?, 'CORE', 'MANUAL', ARRAY[?]::text[], ?) RETURNING id", Long.class, title, code, code);
  }

  private long workOf(String doi, long source) {
    long w = work(doi, "T " + doi);
    jdbc.update("UPDATE work SET source_id = ? WHERE id = ?", source, w);
    return w;
  }

  private java.util.List<String> dois(String query) throws Exception {
    JsonNode r = JSON.readTree(get("/api/works?" + query).body());
    java.util.List<String> out = new java.util.ArrayList<>();
    r.path("results").forEach(x -> out.add(x.path("doi").asString()));
    return out;
  }

  @Test
  void disciplineAliasFilters() throws Exception {
    long a = sourceWithDiscipline("JA", "1105");
    long b = sourceWithDiscipline("JB", "1109");
    workOf("10.9/da", a);
    workOf("10.9/db", b);
    assertThat(dois("discipline=1105")).containsExactly("10.9/da");
    assertThat(dois("subfield=1105")).containsExactly("10.9/da");
    assertThat(dois("subfield=1105&discipline=1105")).containsExactly("10.9/da");
  }

  @Test
  void retiredSubfieldStillFilters() throws Exception {
    long a = sourceWithDiscipline("JA", "1105");
    workOf("10.9/ra", a);
    jdbc.update("INSERT INTO discipline (code, label, replaced_by, sort_order) VALUES ('1199', 'Old', '1105', 99)");
    try {
      assertThat(dois("subfield=1199")).containsExactly("10.9/ra");
      assertThat(dois("discipline=1199")).containsExactly("10.9/ra");
    } finally {
      jdbc.update("DELETE FROM discipline WHERE code = '1199'");
    }
  }

  @Test
  void conflictingSubfieldAndDisciplineIs400() throws Exception {
    assertThat(get("/api/works?subfield=1105&discipline=1109").statusCode()).isEqualTo(400);
  }

  void ident(long work, String scheme, String value) {
    jdbc.update("INSERT INTO work_identifier (work_id, scheme, value, first_layer) VALUES (?, ?, ?, 'TEST')", work, scheme, value);
  }

  @Test
  void detailListsIdentifiersInOrder() throws Exception {
    long w = work("10.1/ids", "Ids");
    ident(w, "PLAZI", "A1B2C3D4E5F60718293A4B5C6D7E8F90");
    ident(w, "OAI", "oai:example.org:1");
    ident(w, "BLR", "1234567");
    ident(w, "BIODIVERSITY_PMC", "pmc/PMC10768028");
    ident(w, "PMCID", "PMC10768028");
    ident(w, "PMID", "38163000");
    ident(w, "OPENALEX", "W123");
    ident(w, "CROSSREF", "10.1/ids");
    ident(w, "DOI", "10.1/ids");
    JsonNode ids = JSON.readTree(get("/api/works/" + w).body()).path("identifiers");
    assertThat(ids.valueStream().map(n -> n.path("scheme").asString()).toList())
        .containsExactly("DOI", "CROSSREF", "OPENALEX", "PMID", "PMCID", "BIODIVERSITY_PMC", "BLR", "PLAZI");
    assertThat(ids.valueStream().map(n -> n.path("url").asString()).toList()).containsExactly(
        "https://doi.org/10.1/ids", "https://api.crossref.org/works/10.1/ids", "https://openalex.org/works/W123",
        "https://europepmc.org/article/MED/38163000", "https://europepmc.org/articles/PMC10768028",
        "https://biodiversitypmc.sibils.org/pmc/PMC10768028", "https://zenodo.org/records/1234567",
        "https://tb.plazi.org/GgServer/summary/A1B2C3D4E5F60718293A4B5C6D7E8F90");
    assertThat(ids.get(0).path("label").asString()).isEqualTo("DOI");
    assertThat(ids.get(0).path("value").asString()).isEqualTo("10.1/ids");
  }

  @Test
  void identifierSearchFindsByPmcid() throws Exception {
    long a = work("10.1/a", "A");
    long b = work("10.1/b", "B");
    ident(a, "PMCID", "PMC10768028");
    ident(b, "PMCID", "PMC1");
    JsonNode r = JSON.readTree(get("/api/works?identifier=PMCID:10768028").body());
    assertThat(r.path("total").asLong()).isEqualTo(1);
    assertThat(r.path("results").get(0).path("id").asLong()).isEqualTo(a);
    assertThat(JSON.readTree(get("/api/works?identifier=pmcid:PMC10768028&hasActs=false").body()).path("total").asLong())
        .isEqualTo(1);
    assertThat(JSON.readTree(get("/api/works?identifier=PMCID:PMC10768028&yearTo=2000").body()).path("total").asLong())
        .isZero();
    // unlisted works are never found
    jdbc.update("UPDATE work SET state = 'MATCHED' WHERE id = ?", a);
    assertThat(JSON.readTree(get("/api/works?identifier=PMCID:PMC10768028").body()).path("total").asLong()).isZero();
  }

  @Test
  void identifierSearchSplitsAtFirstColon() throws Exception {
    String sici = "10.1002/(SICI)1097-4571(199806)49:7<627::AID-ASI4>3.0.CO;2-X";
    long w = work(sici, "Review Focus 1");
    ident(w, "DOI", sici.toLowerCase());
    String q = java.net.URLEncoder.encode("DOI:" + sici, java.nio.charset.StandardCharsets.UTF_8);
    JsonNode r = JSON.readTree(get("/api/works?identifier=" + q).body());
    assertThat(r.path("total").asLong()).isEqualTo(1);
    assertThat(r.path("results").get(0).path("id").asLong()).isEqualTo(w);
  }

  @Test
  void unknownSchemeIs400() throws Exception {
    assertThat(get("/api/works?identifier=NOPE:1").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?identifier=PMCID:abc").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?identifier=nocolon").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?identifier=ISSN:1234-5679").statusCode()).isEqualTo(400);
  }

  @Test
  void extraSchemeRowsAreListed() throws Exception {
    long w = work("10.1/x", "X");
    ident(w, "TESTDB", "42");
    ident(w, "UNREGISTERED", "7");
    JsonNode ids = JSON.readTree(get("/api/works/" + w).body()).path("identifiers");
    assertThat(ids.valueStream().filter(n -> n.path("scheme").asString().equals("TESTDB")).findFirst().orElseThrow()
        .path("url").asString()).isEqualTo("https://testdb.example/42");
    assertThat(JSON.readTree(get("/api/works?identifier=TESTDB:42").body()).path("total").asLong()).isEqualTo(1);
  }

  /** One work_name with explicit parts; taxon path is {U1, MI}. */
  long partsWork(String doi, boolean title, boolean abs, boolean heading, int body, boolean ref, boolean main) {
    long w = work(doi, "T " + doi);
    long ns = nameString("Name " + doi);
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, 's', 'MATCH', 'U1', '{U1,MI}', 0.9, ?)", w, ns, body, title, abs, heading, ref, main);
    return w;
  }

  List<String> sortedDois(String path) throws Exception {
    return JSON.readTree(get(path).body()).path("results").valueStream().map(n -> n.path("doi").asString()).sorted().toList();
  }

  @Test
  void taxonTitleOrAbstract() throws Exception {
    partsWork("10.9/body", false, false, false, 4, false, true);
    partsWork("10.9/abs", false, true, false, 0, false, true);
    partsWork("10.9/title", true, false, false, 0, false, true);
    assertThat(sortedDois("/api/works?taxon=MI&mentionedIn=TITLE&mentionedIn=ABSTRACT")).containsExactly("10.9/abs", "10.9/title");
    assertThat(sortedDois("/api/works?taxon=MI")).hasSize(3);
    assertThat(sortedDois("/api/works?name=" + jdbc.queryForObject("SELECT name_string_id FROM work_name wn JOIN work w ON w.id = wn.work_id WHERE w.doi = '10.9/title'", Long.class)
        + "&mentionedIn=ABSTRACT")).isEmpty();
  }

  @Test
  void bodyIncludesHeadings() throws Exception {
    partsWork("10.9/text", false, false, false, 2, false, true);
    partsWork("10.9/heading", false, false, true, 0, false, true);
    partsWork("10.9/none", true, false, false, 0, false, true);
    assertThat(sortedDois("/api/works?mentionedIn=BODY")).containsExactly("10.9/heading", "10.9/text");
  }

  @Test
  void referencesNeverCount() throws Exception {
    partsWork("10.9/ref", true, true, true, 3, true, false);
    assertThat(sortedDois("/api/works?mentionedIn=TITLE")).isEmpty();
    assertThat(sortedDois("/api/works?taxon=MI&mentionedIn=BODY")).isEmpty();
  }

  @Test
  void withMainSubjectOnly() throws Exception {
    partsWork("10.9/main", true, false, false, 0, false, true);
    partsWork("10.9/minor", true, false, false, 0, false, false);
    assertThat(sortedDois("/api/works?mentionedIn=TITLE")).containsExactly("10.9/main", "10.9/minor");
    assertThat(sortedDois("/api/works?mentionedIn=TITLE&mainSubjectOnly=true")).containsExactly("10.9/main");
  }

  @Test
  void badValueIs400() throws Exception {
    assertThat(get("/api/works?mentionedIn=TITEL").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?mentionedIn=TITLE&mentionedIn=title").statusCode()).isEqualTo(400);
    assertThat(get("/api/works?mentionedIn=TITLE&mentionedIn=TITLE").statusCode()).isEqualTo(200);
  }

  @Test
  void nameDetailHasFlags() throws Exception {
    long w = partsWork("10.9/flags", true, false, true, 5, false, true);
    JsonNode n = JSON.readTree(get("/api/works/" + w).body()).path("names").get(0);
    assertThat(n.path("inTitle").asBoolean()).isTrue();
    assertThat(n.path("inAbstract").asBoolean()).isFalse();
    assertThat(n.path("inHeading").asBoolean()).isTrue();
    assertThat(n.path("bodyCount").asInt()).isEqualTo(5);
  }
}
