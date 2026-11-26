package org.catalogueoflife.garrulus.indexer.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

@WireMockTest(httpPort = 18999)
class SeedSourcesHandlerIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("garrulus.openalex.base-url", () -> "http://localhost:18999");
    r.add("garrulus.http.min-interval.localhost", () -> "0s");
  }

  @Autowired SeedSourcesHandler seed;
  @Autowired AddSourceHandler add;
  @Autowired SourceMapper sources;
  @Autowired org.catalogueoflife.garrulus.indexer.stats.DisciplineService disciplines;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM app_user WHERE email LIKE '%@seed.test'");
    jdbc.update("DELETE FROM source");
  }

  private static void stubRegistry() {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.id:T14399"))
        .willReturn(okJson(SourceSeederTest.group("t", "S901", "50"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.subfield.id:1105"))
        .willReturn(okJson(SourceSeederTest.group("b", "S901", "60", "S902", "80"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_location.source.id:S"))
        .willReturn(okJson(SourceSeederTest.group("n", "S901", "100", "S902", "100"))));
    stubFor(get(urlPathEqualTo("/sources/S901"))
        .willReturn(okJson(SourceSeederTest.source("S901", "Taxon Journal", ""))));
    stubFor(get(urlPathEqualTo("/sources/S902"))
        .willReturn(okJson(SourceSeederTest.source("S902", "Broad Journal", ""))));
  }

  private static Task task(String type, String payload) {
    return new Task(1, type, payload, TaskPriority.LIVE, null, 1, null, null, null, null, null, null);
  }

  private long onboardTasks() {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'ONBOARD_SOURCE'", Long.class);
  }

  @Test
  void seedTwiceKeepsAdminTierAndEnqueuesOnboardingOnce(WireMockRuntimeInfo w) throws Exception {
    stubRegistry();
    seed.handle(task("SEED_SOURCES", "{}"));

    Source core = sources.findByOpenalexId("S901");
    Source broad = sources.findByOpenalexId("S902");
    assertThat(core.tier()).isEqualTo("CORE");
    assertThat(broad.tier()).isEqualTo("BROAD");
    assertThat(core.origin()).isEqualTo("SEED_TOPIC");
    assertThat(core.tierSetBy()).isEqualTo("SEED");
    assertThat(onboardTasks()).isEqualTo(2);

    // an admin excludes the broad journal
    sources.setTier(broad.id(), "EXCLUDED", false, "ADMIN");
    seed.handle(task("SEED_SOURCES", "{}"));

    assertThat(sources.findByOpenalexId("S902").tier()).isEqualTo("EXCLUDED");
    assertThat(sources.findByOpenalexId("S902").tierSetBy()).isEqualTo("ADMIN");
    assertThat(sources.findByOpenalexId("S902").active()).isFalse();
    assertThat(sources.findByOpenalexId("S901").tier()).isEqualTo("CORE");
    // the queued onboarding is not duplicated by the second run
    assertThat(onboardTasks()).isEqualTo(2);
    List<String> payloads = jdbc.queryForList(
        "SELECT payload::text FROM task WHERE type = 'ONBOARD_SOURCE' ORDER BY id", String.class);
    assertThat(payloads).hasSize(2).allMatch(p -> p.replace(" ", "").matches("\\{\"sourceId\":\\d+}"));
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'ONBOARD_SOURCE' LIMIT 1", Integer.class))
        .isEqualTo(TaskPriority.BACKFILL.code());
  }

  @Test
  void addSourceSetsAdminTierAndOnboards(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/sources/S903"))
        .willReturn(okJson(SourceSeederTest.source("S903", "Added Journal", ""))));

    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S903\",\"tier\":\"CORE\"}"));
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S903\",\"tier\":\"CORE\"}"));

    Source s = sources.findByOpenalexId("S903");
    assertThat(s.origin()).isEqualTo("MANUAL");
    assertThat(s.tier()).isEqualTo("CORE");
    assertThat(s.tierSetBy()).isEqualTo("ADMIN");
    assertThat(s.active()).isTrue();
    assertThat(onboardTasks()).isEqualTo(1);

    stubFor(get(urlPathEqualTo("/sources/S904"))
        .willReturn(okJson(SourceSeederTest.source("S904", "Candidate", ""))));
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S904\",\"tier\":\"CANDIDATE\"}"));
    assertThat(sources.findByOpenalexId("S904").tier()).isEqualTo("CANDIDATE");
    assertThat(onboardTasks()).isEqualTo(1);
  }

  private static String zootaxa(String id) {
    return "{\"id\":\"https://openalex.org/" + id + "\",\"display_name\":\"Zootaxa\",\"issn_l\":\"1175-5326\","
        + "\"issn\":[\"1175-5326\",\"1175-5334\"],\"host_organization_name\":\"Magnolia\",\"is_oa\":false}";
  }

  @Test
  void addSourceOfARegisteredCandidateKeepsOneSourceAndCoversTheRegistration(WireMockRuntimeInfo w) throws Exception {
    long cand = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('1175-5326', "
        + "'REGISTERED', 'CANDIDATE', '{1175-5326}') RETURNING id", Long.class);
    long user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('add@seed.test') RETURNING id", Long.class);
    long reg = jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, state, outcome, source_id, "
        + "tier_at_identification) VALUES (?, 'ISSN', '1175-5326', 'IDENTIFIED', 'NEW_CANDIDATE', ?, 'CANDIDATE') "
        + "RETURNING id", Long.class, user, cand);
    stubFor(get(urlPathEqualTo("/sources/S906")).willReturn(okJson(zootaxa("S906"))));

    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S906\",\"tier\":\"CORE\"}"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
    Source s = sources.findByOpenalexId("S906");
    assertThat(s.id()).isEqualTo(cand);
    assertThat(s.tier()).isEqualTo("CORE");
    assertThat(s.tierSetBy()).isEqualTo("ADMIN");
    assertThat(s.issns()).containsExactly("1175-5326", "1175-5334");
    assertThat(jdbc.queryForObject("SELECT source_id FROM source_registration WHERE id = ?", Long.class, reg))
        .isEqualTo(cand); // the user's registration now points at a followed journal: COVERED
  }

  @Test
  void addSourceAdoptsOneCandidateAndFoldsTheOther(WireMockRuntimeInfo w) throws Exception {
    long print = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('P', 'DOAJ', "
        + "'CANDIDATE', '{1175-5326}') RETURNING id", Long.class);
    long online = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('E', 'PKP_BEACON', "
        + "'CANDIDATE', '{1175-5334}') RETURNING id", Long.class);
    stubFor(get(urlPathEqualTo("/sources/S907")).willReturn(okJson(zootaxa("S907"))));

    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S907\",\"tier\":\"BROAD\"}"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
    assertThat(sources.get(online)).isNull();
    assertThat(sources.get(print).openalexId()).isEqualTo("S907");
    assertThat(sources.get(print).tier()).isEqualTo("BROAD");
  }

  @Test
  void seedingAdoptsTheCandidateUnderTheLocks(WireMockRuntimeInfo w) throws Exception {
    long cand = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('E', 'PKP_BEACON', "
        + "'CANDIDATE', '{1175-5334}') RETURNING id", Long.class);
    long other = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('P', 'DOAJ', "
        + "'CANDIDATE', '{1175-5326}') RETURNING id", Long.class);
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.id:T14399"))
        .willReturn(okJson(SourceSeederTest.group("t", "S908", "50"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.subfield.id:1105"))
        .willReturn(okJson(SourceSeederTest.group("b", "S908", "60"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_location.source.id:S"))
        .willReturn(okJson(SourceSeederTest.group("n", "S908", "100"))));
    stubFor(get(urlPathEqualTo("/sources/S908")).willReturn(okJson(zootaxa("S908"))));

    seed.handle(task("SEED_SOURCES", "{}"));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
    assertThat(sources.get(cand).openalexId()).isEqualTo("S908");
    assertThat(sources.get(cand).tier()).isEqualTo("CORE");
    assertThat(sources.get(other)).isNull();
  }

  /** A candidate a user registered, with the PENDING route its page suggested. */
  private long registeredCandidate(String openalexId) {
    long id = jdbc.queryForObject("INSERT INTO source (openalex_id, title, origin, tier) VALUES (?, 'Registered', "
        + "'REGISTERED', 'CANDIDATE') RETURNING id", Long.class, openalexId);
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES (?, 'OAI', "
        + "'{\"base\":\"https://journal.example.org/oai\"}'::jsonb, 'PENDING', 'REGISTERED')", id);
    return id;
  }

  private List<String> verifyTasks() {
    return jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE'", String.class);
  }

  @Test
  void promotionVerifiesTheCandidatesPendingRoutes(WireMockRuntimeInfo w) throws Exception {
    // by an admin (ADD_SOURCE of a known journal)
    long added = registeredCandidate("S903");
    stubFor(get(urlPathEqualTo("/sources/S903"))
        .willReturn(okJson(SourceSeederTest.source("S903", "Added Journal", ""))));
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S903\",\"tier\":\"CANDIDATE\"}"));
    assertThat(verifyTasks()).isEmpty();
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S903\",\"tier\":\"CORE\"}"));
    long route = jdbc.queryForObject("SELECT id FROM source_route WHERE source_id = ?", Long.class, added);
    assertThat(verifyTasks()).containsExactly("verify-route:" + route);

    // by the seeding
    jdbc.update("DELETE FROM task");
    long seeded = registeredCandidate("S901");
    stubRegistry();
    seed.handle(task("SEED_SOURCES", "{}"));
    assertThat(sources.get(seeded).tier()).isEqualTo("CORE");
    long route2 = jdbc.queryForObject("SELECT id FROM source_route WHERE source_id = ?", Long.class, seeded);
    assertThat(verifyTasks()).containsExactly("verify-route:" + route2);
  }

  private static String withTopics() {
    return SourceSeederTest.source("S905", "Topical", ",\"topics\":["
        + "{\"id\":\"https://openalex.org/T1\",\"display_name\":\"One\",\"count\":70,"
        + "\"subfield\":{\"id\":\"https://openalex.org/subfields/1109\"}},"
        + "{\"id\":\"https://openalex.org/T2\",\"display_name\":\"Two\",\"count\":30,"
        + "\"subfield\":{\"id\":\"https://openalex.org/subfields/1103\"}}]");
  }

  private String subfields(long id) {
    return jdbc.queryForObject("SELECT coalesce(array_to_string(subfields, ','), '') FROM source WHERE id = ?",
        String.class, id);
  }

  @Test
  void disciplinesAreDerivedOnAddAndSurviveReseedAndAdminSetting(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/sources/S905")).willReturn(okJson(withTopics())));
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S905\",\"tier\":\"CORE\"}"));
    long id = sources.findByOpenalexId("S905").id();
    // a fresh source ends with the disciplines derived from its raw classification
    assertThat(subfields(id)).isEqualTo("1109,1103");
    assertThat(jdbc.queryForObject("SELECT discipline_source FROM source WHERE id = ?", String.class, id))
        .isEqualTo("OPENALEX");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_classification_raw WHERE source_id = ?", Long.class, id))
        .isEqualTo(4L);

    // upsertSeed on conflict leaves subfields alone
    jdbc.update("UPDATE source SET subfields = '{2404}' WHERE id = ?", id);
    JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(withTopics());
    sources.upsertSeed(SourceSeeder.build(node, "S905", "CORE", "MANUAL", "ADMIN", null));
    assertThat(subfields(id)).isEqualTo("2404");

    // an admin sets the disciplines: neither the re-seed nor applyOpenAlex changes them
    jdbc.update("UPDATE source SET subfields = '{1105}', primary_subfield = '1105', discipline_source = 'ADMIN', "
        + "discipline_set_by = 'ADMIN' WHERE id = ?", id);
    sources.upsertSeed(SourceSeeder.build(node, "S905", "CORE", "MANUAL", "ADMIN", null));
    disciplines.applyOpenAlex(id, node);
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S905\",\"tier\":\"CORE\"}"));
    assertThat(subfields(id)).isEqualTo("1105");
    assertThat(jdbc.queryForObject("SELECT discipline_source FROM source WHERE id = ?", String.class, id))
        .isEqualTo("ADMIN");
  }
}
