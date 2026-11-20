package org.catalogueoflife.nametracker.indexer.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.cost.FakeApiUsage;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineService;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.registry.IssnLinkMapper;
import org.catalogueoflife.nametracker.store.registry.PkpBeaconMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSignal;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@WireMockTest
class IdentifySourceHandlerIT extends AbstractPostgresIT {

  static final String ZOOTAXA_P = "1175-5326";
  static final String ZOOTAXA_E = "1175-5334";
  static final String ZOOTAXA_OA = "S171471881";
  static final String EJT = "2118-9773";
  static final String DISCONTINUED = "1638-9387";

  @Autowired SourceMapper sources;
  @Autowired SourceSignalMapper signals;
  @Autowired SourceRouteMapper routes;
  @Autowired ClassificationRawMapper raw;
  @Autowired IssnLinkMapper links;
  @Autowired PkpBeaconMapper beacon;
  @Autowired IdentityLocks locks;
  @Autowired DisciplineService disciplines;
  @Autowired SourceFold fold;
  @Autowired TaskQueue queue;
  @Autowired TransactionTemplate tx;
  @Autowired HarvestOutage outage;
  @Autowired ApiUsageMapper usage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work WHERE doi LIKE '10.9999/identify%'");
    jdbc.update("DELETE FROM subscription");
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM app_user WHERE email LIKE '%@identify.test'");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM source_sample");
    jdbc.update("DELETE FROM pkp_beacon");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("UPDATE source SET continued_by = NULL");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  // --- wiring -----------------------------------------------------------------------------------------------------

  private IdentifySourceHandler handler(WireMockRuntimeInfo w) {
    return handler(w, new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), clock));
  }

  private IdentifySourceHandler handler(WireMockRuntimeInfo w, OpenAlexBudget budget) {
    return handler(w, budget, w.getHttpBaseUrl() + "/doaj");
  }

  private IdentifySourceHandler handler(WireMockRuntimeInfo w, OpenAlexBudget budget, String doajBase) {
    PoliteHttp http = EuropePmcSearchClientTest.http();
    String base = w.getHttpBaseUrl();
    JournalCompletion completion = new JournalCompletion(new CrossrefClient(http, null, base + "/crossref"),
        new OpenAlexClient(http, budget, null, base + "/openalex"), new DoajClient(http, doajBase), true,
        sources, links, raw, routes, beacon, locks, fold, disciplines, queue, tx);
    return new IdentifySourceHandler(completion, sources, outage, clock);
  }

  private static Task task(long sourceId) {
    return new Task(0, IdentifySourceHandler.TYPE, "{\"sourceId\":" + sourceId + "}", TaskPriority.BACKFILL,
        TaskState.RUNNING, 1, null, null, null, null, null, null);
  }

  private long candidate(String... issns) {
    return sources.insertCandidate(new Source(null, null, null, List.of(issns), null, null, null, null, null, null,
        "DOAJ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null));
  }

  /** Every lookup not stubbed by the test is unknown: Crossref and OpenAlex 404, DOAJ no result. */
  private static void stubNothingKnown() {
    stubFor(get(urlPathMatching("/crossref/journals/.*")).atPriority(10).willReturn(notFound()));
    stubFor(get(urlPathMatching("/openalex/sources/.*")).atPriority(10).willReturn(notFound()
        .withHeader("Content-Type", "application/json").withBody("{\"error\":\"not found\"}")));
    stubFor(get(urlPathMatching("/doaj/search/journals/.*")).atPriority(10).willReturn(okJson(
        "{\"total\": 0, \"page\": 1, \"pageSize\": 10, \"results\": []}")));
  }

  private static void stubZootaxa(String crossrefIssn, String openalexIssn) throws Exception {
    stubFor(get(urlPathEqualTo("/crossref/journals/" + crossrefIssn))
        .willReturn(okJson(DoajClientTest.fixture("crossref-journal-1175-5326.json"))));
    stubFor(get(urlPathEqualTo("/openalex/sources/issn:" + openalexIssn))
        .willReturn(okJson(DoajClientTest.fixture("openalex-source-issn.json"))));
  }

  private OpenAlexBudget budget() {
    return new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), clock);
  }

  /** The DOAJ record of EJT, rewritten to list Zootaxa's print and electronic ISSN. */
  private static String doajZootaxa() throws Exception {
    String json = DoajClientTest.fixture("doaj-journal-2118-9773.json");
    assertThat(json).contains("\"eissn\": \"2118-9773\"");
    return json.replace("\"eissn\": \"2118-9773\"",
        "\"pissn\": \"" + ZOOTAXA_P + "\", \"eissn\": \"" + ZOOTAXA_E + "\"");
  }

  private long tasks(String type) {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = ?", Long.class, type);
  }

  private List<String> rawCodes(long sourceId, String scheme) {
    return raw.forSource(sourceId).stream().filter(r -> r.scheme().equals(scheme)).map(RawClassification::code)
        .toList();
  }

  // --- tests ------------------------------------------------------------------------------------------------------

  @Test
  void completesCandidateFromAllServices(WireMockRuntimeInfo w) throws Exception {
    jdbc.update("INSERT INTO issn_link VALUES (?, ?), (?, ?)", ZOOTAXA_P, ZOOTAXA_P, ZOOTAXA_E, ZOOTAXA_P);
    stubNothingKnown();
    stubZootaxa(ZOOTAXA_P, ZOOTAXA_P);
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + ZOOTAXA_P)).willReturn(okJson(doajZootaxa())));
    long id = candidate(ZOOTAXA_E);
    assertThat(sources.get(id).title()).isEqualTo(ZOOTAXA_E);

    handler(w).handle(task(id));

    Source s = sources.get(id);
    assertThat(s.issnL()).isEqualTo(ZOOTAXA_P);
    assertThat(s.issns()).containsExactly(ZOOTAXA_P, ZOOTAXA_E);
    assertThat(s.title()).isEqualTo("Zootaxa");
    assertThat(s.publisher()).isEqualTo("Magnolia Press");
    assertThat(s.openalexId()).isEqualTo(ZOOTAXA_OA);
    assertThat(s.country()).isEqualTo("NZ"); // OpenAlex; DOAJ's FR only fills a missing country
    assertThat(s.host()).isEqualTo("www.mapress.com");
    assertThat(s.isOa()).isFalse();
    assertThat(s.inDoaj()).isTrue(); // DOAJ answers for DOAJ membership
    assertThat(s.tier()).isEqualTo("CANDIDATE");
    assertThat(rawCodes(id, "OPENALEX_SUBFIELD")).containsExactly("1105", "1910");
    assertThat(rawCodes(id, "OPENALEX_TOPIC")).hasSize(4);
    assertThat(rawCodes(id, "DOAJ_LCC")).containsExactly("QL1-991", "QK1-989");
    assertThat(s.subfields()).contains("1105");
    assertThat(s.disciplineSource()).isEqualTo("OPENALEX");
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'PROBE_EUROPEPMC'", String.class))
        .containsPattern("\"sourceId\": ?" + id + "\\b");
    verify(0, getRequestedFor(urlPathEqualTo("/crossref/journals/" + ZOOTAXA_E))); // the ISSN-L answered first
  }

  @Test
  void completesWithoutIssnLTable(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubZootaxa(ZOOTAXA_E, ZOOTAXA_E);
    long id = candidate(ZOOTAXA_E);

    handler(w).handle(task(id));

    Source s = sources.get(id);
    assertThat(s.issnL()).isEqualTo(ZOOTAXA_P); // from OpenAlex
    assertThat(s.issns()).containsExactly(ZOOTAXA_P, ZOOTAXA_E);
    assertThat(s.openalexId()).isEqualTo(ZOOTAXA_OA);
    assertThat(s.inDoaj()).isFalse();
  }

  @Test
  void beaconRowGivesPendingOaiRoute(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    jdbc.update("INSERT INTO pkp_beacon (context_name, issns, oai_url, set_spec, country, unresponsive, loaded) "
        + "VALUES ('ejt', '{2118-9773}', 'https://ejt.example.org/index.php/ejt/oai', 'ejt', 'FR', false, now()), "
        + "('other', '{0000-0019}', 'https://other.example.org/oai', null, 'DE', false, now())");
    long id = candidate(EJT);

    handler(w).handle(task(id));

    assertThat(routes.forSource(id)).singleElement().satisfies(r -> {
      assertThat(r.layer()).isEqualTo("OAI");
      assertThat(r.status()).isEqualTo("PENDING");
      assertThat(r.origin()).isEqualTo("DISCOVERED");
      JsonNode c = FinderJson.mapper().readTree(r.config());
      assertThat(c.path("base").asString()).isEqualTo("https://ejt.example.org/index.php/ejt/oai");
      assertThat(c.path("set").asString()).isEqualTo("ejt");
      assertThat(c.path("prefix").asString()).isEqualTo("oai_dc");
      assertThat(c.path("issns").get(0).asString()).isEqualTo(EJT);
      assertThat(jdbc.queryForObject("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE'", String.class))
          .isEqualTo("verify-route:" + r.id());
    });

    // a source that already has an OAI route with that base and set gets no second one
    long other = candidate("0000-0019");
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES (?, 'OAI', "
        + "'{\"base\":\"https://other.example.org/oai\",\"set\":null,\"prefix\":\"jats\"}'::jsonb, 'ACTIVE', 'ADMIN')",
        other);
    handler(w).handle(task(other));
    assertThat(routes.forSource(other)).singleElement()
        .satisfies(r -> assertThat(r.config()).contains("jats"));
  }

  @Test
  void doajDiscontinuedIsStored(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + DISCONTINUED))
        .willReturn(okJson(DoajClientTest.fixture("doaj-journal-discontinued.json"))));
    long id = candidate(DISCONTINUED);

    handler(w).handle(task(id));

    Source s = sources.get(id);
    assertThat(s.inDoaj()).isTrue();
    assertThat(s.doajDiscontinued()).isEqualTo(LocalDate.of(2024, 6, 30));
    assertThat(s.successorIssns()).containsExactly("2118-9781");
    assertThat(s.country()).isEqualTo("FR");
    assertThat(s.title()).isEqualTo(DISCONTINUED); // DOAJ does not name the journal: only Crossref replaces the ISSN
    assertThat(s.publicationStatus()).isEqualTo("ACTIVE"); // detection proposes, an admin decides
    assertThat(rawCodes(id, "DOAJ_LCC")).containsExactly("QL1-991", "QK1-989");
    assertThat(s.subfields()).containsExactlyInAnyOrder("1103", "1110");
    assertThat(s.disciplineSource()).isEqualTo("DOAJ");
  }

  @Test
  void identifyFoldsDuplicateCandidateIntoHolder(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/openalex/sources/issn:" + ZOOTAXA_E))
        .willReturn(okJson(DoajClientTest.fixture("openalex-source-issn.json"))));
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + ZOOTAXA_P)).willReturn(okJson(doajZootaxa())));
    long holder = jdbc.queryForObject("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, active) "
        + "VALUES (?, ?, ?::text[], 'Zootaxa', 'CORE', 'SEED_TOPIC', true) RETURNING id", Long.class, ZOOTAXA_OA,
        ZOOTAXA_P, "{" + ZOOTAXA_P + "}");
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', '{\"base\":\"https://mapress.example/oai\"}'::jsonb, 'ACTIVE', 'SEED')", holder);
    signals.upsert(holder, "DOAJ", "", "{\"holder\":true}");
    long cand = candidate(ZOOTAXA_E);
    signals.upsert(cand, "DOAJ", "", "{\"candidate\":true}"); // the holder has it already: dropped
    signals.upsert(cand, "PKP_BEACON", "", "{\"oai\":1}");
    raw.replace(cand, "DOAJ_LCC", List.of(new RawClassification("DOAJ_LCC", "QL1-991", "Zoology", null, 0)));
    long dupRoute = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'OAI', '{\"base\":\"https://mapress.example/oai\"}'::jsonb, 'PENDING', 'DISCOVERED') RETURNING id",
        Long.class, cand);
    long movedRoute = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'EUROPEPMC', '{\"issns\":[\"1175-5334\"]}'::jsonb, 'VERIFIED', 'DISCOVERED') RETURNING id", Long.class,
        cand);
    jdbc.update("INSERT INTO source_sample (source_id, articles, with_abstract) VALUES (?, 5, 4)", cand);
    long user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('fold@identify.test') RETURNING id",
        Long.class);
    long reg = jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, state, source_id) VALUES "
        + "(?, 'ISSN', ?, 'IDENTIFIED', ?) RETURNING id", Long.class, user, ZOOTAXA_E, cand);
    jdbc.update("INSERT INTO subscription (user_id, type, target) VALUES (?, 'SOURCE', ?)", user,
        String.valueOf(cand));
    long successor = candidate("0000-0027");
    jdbc.update("UPDATE source SET continued_by = ? WHERE id = ?", cand, successor);

    handler(w).handle(task(cand));

    assertThat(sources.get(cand)).isNull();
    Source h = sources.get(holder);
    assertThat(h.issns()).containsExactly(ZOOTAXA_P, ZOOTAXA_E);
    assertThat(h.tier()).isEqualTo("CORE");
    assertThat(signals.forSource(holder)).extracting(SourceSignal::origin)
        .containsExactlyInAnyOrder("DOAJ", "PKP_BEACON");
    assertThat(signals.forSource(holder)).filteredOn(sig -> sig.origin().equals("DOAJ")).singleElement()
        .satisfies(sig -> assertThat(sig.detail()).contains("holder"));
    // the completion went on with the holder: DOAJ's answer replaced the moved LCC rows
    assertThat(rawCodes(holder, "DOAJ_LCC")).containsExactly("QL1-991", "QK1-989");
    assertThat(sources.get(holder).inDoaj()).isTrue();
    assertThat(routes.forSource(holder)).extracting(SourceRoute::layer).containsExactly("OAI", "EUROPEPMC");
    assertThat(routes.get(movedRoute).sourceId()).isEqualTo(holder);
    assertThat(routes.get(dupRoute)).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_sample WHERE source_id = ?", Long.class, holder))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT source_id FROM source_registration WHERE id = ?", Long.class, reg))
        .isEqualTo(holder);
    assertThat(jdbc.queryForObject("SELECT target FROM subscription WHERE user_id = ?", String.class, user))
        .isEqualTo(String.valueOf(holder));
    assertThat(sources.get(successor).continuedBy()).isEqualTo(holder);
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'PROBE_EUROPEPMC'", String.class))
        .containsPattern("\"sourceId\": ?" + holder + "\\b");
  }

  @Test
  void foldIsRefusedForCandidateWithActiveRouteOrWorks(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/openalex/sources/issn:" + ZOOTAXA_E))
        .willReturn(okJson(DoajClientTest.fixture("openalex-source-issn.json"))));
    long holder = jdbc.queryForObject("INSERT INTO source (openalex_id, issns, title, tier, origin, active) "
        + "VALUES (?, ?::text[], 'Zootaxa', 'CORE', 'SEED_TOPIC', true) RETURNING id", Long.class, ZOOTAXA_OA,
        "{" + ZOOTAXA_P + "}");
    long withRoute = candidate(ZOOTAXA_E);
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES "
        + "(?, 'EUROPEPMC', '{\"issns\":[\"1175-5334\"]}'::jsonb, 'ACTIVE', 'DISCOVERED')", withRoute);
    long withWork = candidate(ZOOTAXA_E);
    jdbc.update("INSERT INTO work (doi, title, origin, source_id) VALUES ('10.9999/identify.1', 'A paper', "
        + "'LIVE', ?)", withWork);

    handler(w).handle(task(withRoute));
    handler(w).handle(task(withWork));

    for (long id : new long[] {withRoute, withWork}) {
      Source s = sources.get(id);
      assertThat(s).isNotNull();
      assertThat(s.openalexId()).isNull(); // the holder keeps the unique id
      assertThat(s.country()).isEqualTo("NZ"); // the rest of the OpenAlex answer is written
    }
    assertThat(sources.get(holder).issns()).containsExactly(ZOOTAXA_P);
    assertThat(sources.get(holder).openalexId()).isEqualTo(ZOOTAXA_OA);
  }

  @Test
  void openalexBudgetOutSkipsStep(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubZootaxa(ZOOTAXA_P, ZOOTAXA_P);
    FakeApiUsage spent = new FakeApiUsage();
    spent.add(OpenAlexBudget.SERVICE, LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC)), 1, BigDecimal.ZERO,
        BigDecimal.ZERO, OpenAlexBudget.fingerprint(null));
    long id = candidate(ZOOTAXA_P);

    handler(w, new OpenAlexBudget(spent, new BigDecimal("1.00"), new BigDecimal("0.30"), clock)).handle(task(id));

    Source s = sources.get(id);
    assertThat(s.openalexId()).isNull();
    assertThat(s.title()).isEqualTo("Zootaxa"); // Crossref still ran
    assertThat(s.inDoaj()).isFalse(); // and DOAJ
    verify(0, getRequestedFor(urlPathMatching("/openalex/.*")));
    assertThat(tasks("PROBE_EUROPEPMC")).isEqualTo(1);
  }

  @Test
  void crossrefDownDefersWithoutAttempt(WireMockRuntimeInfo w) {
    stubNothingKnown();
    stubFor(get(urlPathMatching("/crossref/journals/.*")).willReturn(aResponse().withStatus(503)));
    long id = candidate(ZOOTAXA_P);

    assertThatThrownBy(() -> handler(w).handle(task(id))).isInstanceOf(ServiceOutageException.class);

    assertThat(sources.get(id).title()).isEqualTo(ZOOTAXA_P);
    assertThat(tasks("PROBE_EUROPEPMC")).isZero();
  }

  @Test
  void ceasedSourceIsSkipped(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    long id = candidate(ZOOTAXA_P);
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", id);

    handler(w).handle(task(id));

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(sources.get(id).title()).isEqualTo(ZOOTAXA_P);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }

  @Test
  void redeliveryChangesNothing(WireMockRuntimeInfo w) throws Exception {
    jdbc.update("INSERT INTO issn_link VALUES (?, ?), (?, ?)", ZOOTAXA_P, ZOOTAXA_P, ZOOTAXA_E, ZOOTAXA_P);
    jdbc.update("INSERT INTO pkp_beacon (issns, oai_url, set_spec, unresponsive, loaded) "
        + "VALUES ('{1175-5334}', 'https://mapress.example/oai', 'zt', false, now())");
    stubNothingKnown();
    stubZootaxa(ZOOTAXA_P, ZOOTAXA_P);
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + ZOOTAXA_P)).willReturn(okJson(doajZootaxa())));
    stubFor(get(urlPathEqualTo("/openalex/sources/" + ZOOTAXA_OA))
        .willReturn(okJson(DoajClientTest.fixture("openalex-source-issn.json"))));
    long id = candidate(ZOOTAXA_E);
    IdentifySourceHandler h = handler(w);

    h.handle(task(id));
    Map<String, Object> first = snapshot(id);
    assertThat(sources.get(id).inDoaj()).isTrue();
    h.handle(task(id));

    verify(1, getRequestedFor(urlPathEqualTo("/openalex/sources/" + ZOOTAXA_OA))); // the OpenAlex step ran again
    assertThat(snapshot(id)).isEqualTo(first);
    assertThat(routes.forSource(id)).hasSize(1);
  }

  @Test
  void crossrefIssnsFoldDuplicateCandidates(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathMatching("/crossref/journals/1175-53(26|34)"))
        .willReturn(okJson(DoajClientTest.fixture("crossref-journal-1175-5326.json"))));
    long p = candidate(ZOOTAXA_P);
    signals.upsert(p, "DOAJ", "", "{}");
    long e = candidate(ZOOTAXA_E);
    signals.upsert(e, "PKP_BEACON", "", "{}");

    handler(w).handle(task(e)); // the younger candidate folds into the older one

    assertThat(sources.get(e)).isNull();
    Source s = sources.get(p);
    assertThat(s.issns()).containsExactly(ZOOTAXA_P, ZOOTAXA_E);
    assertThat(s.title()).isEqualTo("Zootaxa"); // the folded candidate's real title replaces the placeholder
    assertThat(signals.forSource(p)).extracting(SourceSignal::origin).containsExactlyInAnyOrder("DOAJ", "PKP_BEACON");
    // the completion goes on with the survivor
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'PROBE_EUROPEPMC'", String.class))
        .containsPattern("\"sourceId\": ?" + p + "\\b");
  }

  @Test
  void foldsRunToAFixpoint(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/crossref/journals/0000-0019"))
        .willReturn(okJson(DoajClientTest.fixture("crossref-journal-1175-5326.json")
            .replace("\"ISSN\": [", "\"ISSN\": [\"0000-0019\", "))));
    long b = candidate(ZOOTAXA_P);
    signals.upsert(b, "DOAJ", "", "{}");
    long a = candidate("0000-0019");
    signals.upsert(a, "BLR", "", "{}");
    long c = candidate(ZOOTAXA_E);
    signals.upsert(c, "PKP_BEACON", "", "{}");

    handler(w).handle(task(a)); // A folds into the older B, then B takes the younger C

    assertThat(sources.get(a)).isNull();
    assertThat(sources.get(c)).isNull();
    assertThat(sources.get(b).issns()).containsExactly("0000-0019", ZOOTAXA_P, ZOOTAXA_E);
    assertThat(signals.forSource(b)).extracting(SourceSignal::origin)
        .containsExactlyInAnyOrder("DOAJ", "BLR", "PKP_BEACON");
  }

  @Test
  void doajFoldStillWritesDoajAnswerToSurvivor(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + ZOOTAXA_E)).willReturn(okJson(doajZootaxa())));
    jdbc.update("INSERT INTO pkp_beacon (issns, oai_url, set_spec, unresponsive, loaded) "
        + "VALUES ('{1175-5334}', 'https://mapress.example/oai', 'zt', false, now())");
    long p = candidate(ZOOTAXA_P);
    long e = candidate(ZOOTAXA_E);

    handler(w).handle(task(e)); // DOAJ links E to the older P

    assertThat(sources.get(e)).isNull();
    Source s = sources.get(p);
    assertThat(s.inDoaj()).isTrue();
    assertThat(rawCodes(p, "DOAJ_LCC")).containsExactly("QL1-991", "QK1-989");
    assertThat(routes.forSource(p)).singleElement().satisfies(r -> assertThat(r.layer()).isEqualTo("OAI"));
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'PROBE_EUROPEPMC'", String.class))
        .containsPattern("\"sourceId\": ?" + p + "\\b");
  }

  @Test
  void doajIssnsFoldYoungerCandidateIntoIdentifiedSource(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + ZOOTAXA_P)).willReturn(okJson(doajZootaxa())));
    long p = candidate(ZOOTAXA_P);
    signals.upsert(p, "DOAJ", "", "{}");
    long e = candidate(ZOOTAXA_E);
    signals.upsert(e, "PKP_BEACON", "", "{}");

    handler(w).handle(task(p)); // the identified source is the older one: the younger candidate folds into it

    assertThat(sources.get(e)).isNull();
    Source s = sources.get(p);
    assertThat(s.issns()).containsExactly(ZOOTAXA_P, ZOOTAXA_E);
    assertThat(s.inDoaj()).isTrue();
    assertThat(signals.forSource(p)).extracting(SourceSignal::origin).containsExactlyInAnyOrder("DOAJ", "PKP_BEACON");
    assertThat(tasks("PROBE_EUROPEPMC")).isEqualTo(1); // its completion went on
  }

  @Test
  void curatedCandidateIsNotFolded(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/doaj/search/journals/issn:" + ZOOTAXA_P)).willReturn(okJson(doajZootaxa())));
    long p = candidate(ZOOTAXA_P);
    long tiered = candidate(ZOOTAXA_E);
    jdbc.update("UPDATE source SET tier_set_by = 'ADMIN' WHERE id = ?", tiered);
    long disciplined = candidate(ZOOTAXA_E);
    jdbc.update("UPDATE source SET discipline_set_by = 'ADMIN' WHERE id = ?", disciplined);

    handler(w).handle(task(p));

    assertThat(sources.get(tiered)).isNotNull();
    assertThat(sources.get(disciplined)).isNotNull();
    assertThat(sources.get(p).issns()).containsExactly(ZOOTAXA_P, ZOOTAXA_E);
  }

  @Test
  void olderSourceTakesOverTheOpenalexIdOfAYoungerCandidate(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathEqualTo("/openalex/sources/issn:" + ZOOTAXA_P))
        .willReturn(okJson(DoajClientTest.fixture("openalex-source-issn.json"))));
    long older = candidate(ZOOTAXA_P);
    long younger = jdbc.queryForObject("INSERT INTO source (openalex_id, issns, title, tier, origin) "
        + "VALUES (?, '{}', 'Zootaxa', 'CANDIDATE', 'SEED_TOPIC') RETURNING id", Long.class, ZOOTAXA_OA);
    signals.upsert(younger, "SEED_TOPIC", "", "{}");

    handler(w).handle(task(older));

    assertThat(sources.get(younger)).isNull();
    Source s = sources.get(older);
    assertThat(s.openalexId()).isEqualTo(ZOOTAXA_OA); // deleted first, then set: the column is unique
    assertThat(s.country()).isEqualTo("NZ");
    assertThat(signals.forSource(older)).extracting(SourceSignal::origin).containsExactly("SEED_TOPIC");
  }

  @Test
  void doajOutageDefersAndKeepsDoajData(WireMockRuntimeInfo w) throws Exception {
    stubNothingKnown();
    stubFor(get(urlPathMatching("/doaj/search/journals/.*")).willReturn(aResponse().withStatus(502)));
    long id = candidate(EJT);
    jdbc.update("UPDATE source SET in_doaj = true WHERE id = ?", id);
    raw.replace(id, "DOAJ_LCC", List.of(new RawClassification("DOAJ_LCC", "QL1-991", "Zoology", null, 0)));

    assertThatThrownBy(() -> handler(w).handle(task(id))).isInstanceOf(ServiceOutageException.class);
    assertThatThrownBy(() -> handler(w, budget(), UNROUTABLE + "/doaj").handle(task(id)))
        .isInstanceOf(ServiceOutageException.class);

    assertThat(sources.get(id).inDoaj()).isTrue();
    assertThat(rawCodes(id, "DOAJ_LCC")).containsExactly("QL1-991");
  }

  private Map<String, Object> snapshot(long id) {
    return Map.of(
        "source", jdbc.queryForObject("SELECT row_to_json(s)::text FROM source s WHERE id = ?", String.class, id),
        "raw", jdbc.queryForList("SELECT scheme, code, label, share, rank FROM source_classification_raw "
            + "WHERE source_id = ? ORDER BY scheme, rank", id),
        "routes", jdbc.queryForList("SELECT id, layer, config::text, status FROM source_route WHERE source_id = ? "
            + "ORDER BY id", id),
        "signals", jdbc.queryForList("SELECT origin, signal_key FROM source_signal WHERE source_id = ?", id),
        "tasks", jdbc.queryForList("SELECT type, dedup_key, state FROM task ORDER BY id"));
  }
}
