package org.catalogueoflife.nametracker.indexer.registry.sample;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.finder.ReloadableNameFinder;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiLayer;
import org.catalogueoflife.nametracker.indexer.match.ClbClient;
import org.catalogueoflife.nametracker.indexer.match.XrReleaseService;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSample;
import org.catalogueoflife.nametracker.store.registry.SourceSampleMapper;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

@WireMockTest
class SampleSourceHandlerIT extends AbstractPostgresIT {

  static final String ISSN = "1175-5326";
  static final String MATCH = "/clb/dataset/316691/match/nameusage";
  static final String ABS = "A long enough abstract that describes a revision of the genus in some detail. ";

  @Autowired SourceMapper sources;
  @Autowired SourceRouteMapper routes;
  @Autowired SourceSignalMapper signals;
  @Autowired SourceSampleMapper samples;
  @Autowired IndexMapper index;
  @Autowired ApiUsageMapper usage;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  Clock clock = Clock.systemUTC();
  Function<Document, FinderResult> findFn;

  /** Per document: two distinct names (Carabus xyzzyus twice), one OTU, and an act when the text says "sp. nov.". */
  NameFinder finder = new NameFinder() {
    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", "cfg1", null);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      return findFn.apply(doc);
    }
  };

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_sample");
    jdbc.update("DELETE FROM xr_release");
    jdbc.update("UPDATE source SET continued_by = NULL");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
    index.addRelease(316691, "COL XR test", true);
    findFn = SampleSourceHandlerIT::standard;
  }

  private static FinderResult standard(Document doc) {
    Block b = doc.blocks().get(0);
    List<Mention> ms = new ArrayList<>();
    String t = b.text();
    ms.add(m("m1", b, "Carabus xyzzyus", NameKind.BINOMIAL));
    ms.add(new Mention("m2", b.id(), 0, 1, t.substring(0, 1), "Aus bus", NameKind.BINOMIAL, null, null, null, 0.9,
        new Evidence(Set.of(Generator.DICTIONARY), null, null), null, null, Map.of()));
    ms.add(new Mention("m3", b.id(), 0, 1, t.substring(0, 1), "Carabus xyzzyus", NameKind.BINOMIAL, null, null, null,
        0.9, new Evidence(Set.of(Generator.DICTIONARY), null, null), null, null, Map.of()));
    ms.add(new Mention("m4", b.id(), 0, 1, t.substring(0, 1), "BOLD:AAA1234", NameKind.OTU, null, null, null, 1.0,
        new Evidence(Set.of(Generator.DICTIONARY), null, null), null, null, Map.of()));
    List<Act> acts = new ArrayList<>();
    if (doc.blocks().stream().anyMatch(x -> x.text().contains("sp. nov."))) {
      acts.add(new Act("a1", ActType.SP_NOV, "sp. nov.", "m1", 0.9));
    }
    return new FinderResult(new FinderInfo("stub", "1", "cfg1", null), ms, acts, List.of(), List.of(), Map.of());
  }

  private static Mention m(String id, Block b, String name, NameKind kind) {
    return new Mention(id, b.id(), 0, 1, b.text().substring(0, 1), name, kind, null, null, null, 0.9,
        new Evidence(Set.of(Generator.DICTIONARY), null, null), null, null, Map.of());
  }

  // --- wiring -----------------------------------------------------------------------------------------------------

  private SampleSourceHandler handler(WireMockRuntimeInfo w) {
    return handler(w, finder);
  }

  private SampleSourceHandler handler(WireMockRuntimeInfo w, NameFinder f) {
    PoliteHttp http = EuropePmcSearchClientTest.http();
    String base = w.getHttpBaseUrl();
    HarvestOutage outage = new HarvestOutage(Duration.ofMinutes(30), clock);
    OpenAlexBudget budget = new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), clock);
    SampleFetcher fetcher = new SampleFetcher(http, new CrossrefClient(http, null, base + "/crossref"),
        new OpenAlexClient(http, budget, null, base + "/openalex"),
        new OaiLayer(http, routes, new MockEnvironment(), clock), routes, outage, clock, base + "/epmc");
    ClbClient clb = new ClbClient(http, base + "/clb", outage);
    StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("finder", f));
    ObjectProvider<NameFinder> provider = beans.getBeanProvider(NameFinder.class);
    return new SampleSourceHandler(provider, fetcher, clb, new XrReleaseService(index, clb), sources, samples,
        new CandidateScorer(signals, samples, sources, new MockEnvironment()), tx, clock);
  }

  private static Task task(long sourceId, String extra) {
    return new Task(0, SampleSourceHandler.TYPE, "{\"sourceId\":" + sourceId + extra + "}", TaskPriority.BACKFILL,
        TaskState.RUNNING, 1, null, null, null, null, null, null);
  }

  private static Task task(long sourceId) {
    return new Task(0, SampleSourceHandler.TYPE, "{\"sourceId\":" + sourceId + "}", TaskPriority.BACKFILL,
        TaskState.RUNNING, 1, null, null, null, null, null, null);
  }

  private long candidate(String openalexId, String... issns) {
    return sources.insertCandidate(new Source(null, openalexId, null, List.of(issns), "Zootaxa test", null, null, null,
        null, null, "DOAJ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null));
  }

  private static String fixture(String name) throws Exception {
    try (var in = SampleSourceHandlerIT.class.getResourceAsStream("/clb/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static ResponseDefinitionBuilder json(String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  private static void stubClb() throws Exception {
    stubFor(get(urlPathEqualTo(MATCH)).atPriority(10).willReturn(json(fixture("match-none.json"))));
    stubFor(get(urlPathEqualTo(MATCH)).withQueryParam("scientificName", equalTo("Carabus xyzzyus"))
        .willReturn(json(fixture("match-carabus-xyzzyus.json"))));
  }

  /** {@code n} Crossref items published in the last weeks, the first {@code withAct} of them with "sp. nov." */
  private static String crossref(int n, int withAct) {
    StringBuilder sb = new StringBuilder("{\"status\":\"ok\",\"message\":{\"items\":[");
    LocalDate d = LocalDate.now(ZoneOffset.UTC).minusDays(10);
    for (int i = 0; i < n; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"DOI\":\"10.9999/sample").append(i).append("\",\"title\":[\"Article ").append(i)
          .append(i < withAct ? " sp. nov." : "").append("\"],\"abstract\":\"<jats:p>").append(ABS)
          .append("</jats:p>\",\"type\":\"journal-article\",\"published\":{\"date-parts\":[[").append(d.getYear())
          .append(',').append(d.getMonthValue()).append(',').append(d.getDayOfMonth()).append("]]}}");
    }
    return sb.append("]}}").toString();
  }

  private static void stubCrossref(int n, int withAct) {
    stubFor(get(urlPathEqualTo("/crossref/works")).willReturn(json(crossref(n, withAct))));
  }

  private static void stubNoOtherRoutes() {
    stubFor(get(urlPathEqualTo("/epmc/rest/search")).willReturn(json("{\"hitCount\":0,\"resultList\":{\"result\":[]}}")));
    stubFor(get(urlPathEqualTo("/openalex/works")).willReturn(json("{\"results\":[],\"meta\":{}}")));
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  // --- tests ------------------------------------------------------------------------------------------------------

  @Test
  void sampleStoresAggregatesOnly(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(6, 3);
    long id = candidate("S1", ISSN);
    signals.upsert(id, "DOAJ", "", "{}");
    int[] before = {count("work"), count("mention"), count("name_string"), count("name_match"),
        count("classification_context"), count("work_identifier"), count("name_ubiquity")};

    handler(w).handle(task(id));

    verify(getRequestedFor(urlPathEqualTo("/crossref/works"))
        .withQueryParam("filter", containing("issn:" + ISSN))
        .withQueryParam("filter", containing("type:journal-article"))
        .withQueryParam("filter", containing("from-pub-date:" + LocalDate.now(ZoneOffset.UTC).minusYears(2)))
        .withQueryParam("sort", equalTo("published")).withQueryParam("order", equalTo("desc"))
        .withQueryParam("rows", equalTo("20")));
    assertThat(count("source_sample")).isEqualTo(1);
    SourceSample s = samples.latest(id);
    assertThat(s.route()).isEqualTo("CROSSREF");
    assertThat(s.articles()).isEqualTo(6);
    assertThat(s.withAbstract()).isEqualTo(6);
    assertThat(s.namesPerArticle()).isEqualTo(2f); // two distinct names; the OTU and the repeat do not count
    assertThat(s.actShare()).isCloseTo(0.5f, within(1e-6f));
    assertThat(s.taxgroups()).startsWith("{").isNotEqualTo("{}"); // Carabus xyzzyus matched, Aus bus not
    assertThat(s.error()).isNull();
    Source src = sources.get(id);
    assertThat(src.candidateScore()).isEqualTo(
        CandidateScore.of(new CandidateScore.Signals(1, 0, 0, false), new CandidateScore.SampleStats(6, 2, 0.5)));
    assertThat(src.candidateScoredAt()).isNotNull();
    // the sample papers live only inside the task
    assertThat(new int[] {count("work"), count("mention"), count("name_string"), count("name_match"),
        count("classification_context"), count("work_identifier"), count("name_ubiquity")}).containsExactly(before);
  }

  @Test
  void fallsBackToOaiWhenCrossrefHasTooFew(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(3, 0);
    stubNoOtherRoutes();
    LocalDate d = LocalDate.now(ZoneOffset.UTC).minusDays(20);
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withHeader("Content-Type", "text/xml")
        .withBody(oai(7, d))));
    int works = count("work");
    long id = candidate("S2", ISSN);
    insertRoute(id, "VERIFIED", "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":null,\"prefix\":\"oai_dc\"}");

    handler(w).handle(task(id));

    SourceSample s = samples.latest(id);
    assertThat(s.route()).isEqualTo("OAI");
    assertThat(s.articles()).isEqualTo(7);
    verify(getRequestedFor(urlPathEqualTo("/crossref/works")));
    verify(getRequestedFor(urlPathEqualTo("/oai")).withQueryParam("verb", equalTo("ListRecords"))
        .withQueryParam("from", equalTo(LocalDate.now(ZoneOffset.UTC).minusYears(2).toString())));
    verify(0, getRequestedFor(urlPathEqualTo("/epmc/rest/search")));
    assertThat(count("work")).isEqualTo(works);
  }

  @Test
  void unverifiedOrBrokenOaiRouteIsNeverFetched(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(2, 0);
    stubNoOtherRoutes();
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withBody(oai(7, LocalDate.now(ZoneOffset.UTC)))));
    long id = candidate("S3", ISSN);
    String cfg = "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":null,\"prefix\":\"oai_dc\"}";
    insertRoute(id, "PENDING", cfg);
    insertRoute(id, "BROKEN", cfg.replace("\"oai\"", "\"oai2\"").replace("/oai\"", "/oai2\""));

    handler(w).handle(task(id));

    verify(0, getRequestedFor(urlPathMatching("/oai.*")));
    assertThat(samples.latest(id).route()).isEqualTo("CROSSREF"); // the largest of the small ones
    assertThat(samples.latest(id).articles()).isEqualTo(2);
  }

  @Test
  void fallsBackToEuropePmcAndOpenAlex(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(0, 0);
    StringBuilder epmc = new StringBuilder("{\"hitCount\":6,\"resultList\":{\"result\":[");
    for (int i = 0; i < 6; i++) {
      epmc.append(i > 0 ? "," : "").append("{\"title\":\"Epmc ").append(i).append("\",\"doi\":\"10.9999/e").append(i)
          .append("\",\"abstractText\":\"").append(ABS).append("\",\"pubYear\":\"2026\"}");
    }
    stubFor(get(urlPathEqualTo("/epmc/rest/search")).willReturn(json(epmc + "]}}")));
    long id = candidate(null, ISSN);
    handler(w).handle(task(id));
    assertThat(samples.latest(id).route()).isEqualTo("EUROPEPMC");
    verify(getRequestedFor(urlPathEqualTo("/epmc/rest/search")).withQueryParam("sort", equalTo("P_PDATE_D desc"))
        .withQueryParam("pageSize", equalTo("20"))
        .withQueryParam("query", containing("FIRST_PDATE:[" + LocalDate.now(ZoneOffset.UTC).minusYears(2))));

    // no ISSN route has enough: OpenAlex by source id, the abstract rebuilt from the inverted index
    stubFor(get(urlPathEqualTo("/epmc/rest/search")).willReturn(json("{\"hitCount\":0,\"resultList\":{\"result\":[]}}")));
    StringBuilder oa = new StringBuilder("{\"results\":[");
    for (int i = 0; i < 5; i++) {
      oa.append(i > 0 ? "," : "").append("{\"id\":\"https://openalex.org/W").append(i).append("\",\"title\":\"OA ")
          .append(i).append("\",\"publication_date\":\"2026-01-0").append(i + 1)
          .append("\",\"abstract_inverted_index\":{\"Revision\":[0],\"of\":[1],\"Carabus\":[2]}}");
    }
    stubFor(get(urlPathEqualTo("/openalex/works")).willReturn(json(oa + "],\"meta\":{}}")));
    long id2 = candidate("S171471881", "2118-9773");
    handler(w).handle(task(id2));
    SourceSample s = samples.latest(id2);
    assertThat(s.route()).isEqualTo("OPENALEX");
    assertThat(s.articles()).isEqualTo(5);
    verify(getRequestedFor(urlPathEqualTo("/openalex/works"))
        .withQueryParam("filter", containing("primary_location.source.id:S171471881"))
        .withQueryParam("sort", equalTo("publication_date:desc")).withQueryParam("per_page", equalTo("20")));
  }

  @Test
  void noRouteKeepsSignalScore(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(0, 0);
    stubNoOtherRoutes();
    long id = candidate(null, ISSN);
    signals.upsert(id, "DOAJ", "", "{}");
    signals.upsert(id, "PLAZI", "", "{\"articles\":9}");

    handler(w).handle(task(id));

    SourceSample s = samples.latest(id);
    assertThat(s.route()).isNull();
    assertThat(s.articles()).isZero();
    assertThat(s.namesPerArticle()).isNull();
    assertThat(sources.get(id).candidateScore())
        .isEqualTo(CandidateScore.of(new CandidateScore.Signals(2, 9, 0, false), null));
    assertThat(sources.get(id).candidateScore()).isPositive();
  }

  @Test
  void signalsOfSeveralKeysAreSummed(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(0, 0);
    stubNoOtherRoutes();
    long id = candidate(null, ISSN);
    signals.upsert(id, "COL_REFERENCES", "", "{\"references\":20,\"datasets\":2,\"title\":\"A\"}");
    signals.upsert(id, "COL_REFERENCES", "zootaxa", "{\"references\":30,\"datasets\":1,\"title\":\"Zootaxa\"}");
    signals.upsert(id, "PLAZI", "x", "{\"treatments\":5,\"articles\":4,\"label\":\"X\"}");
    signals.upsert(id, "PLAZI", "y", "{\"treatments\":5,\"articles\":5,\"label\":\"Y\"}");

    handler(w).handle(task(id));

    assertThat(sources.get(id).candidateScore())
        .isEqualTo(CandidateScore.of(new CandidateScore.Signals(2, 9, 50, false), null));
  }

  @Test
  void finderNotReadyDefers(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(6, 0);
    long id = candidate(null, ISSN);
    assertThatThrownBy(() -> handler(w, new ReloadableNameFinder(null)).handle(task(id)))
        .isInstanceOf(ServiceOutageException.class).extracting(e -> ((ServiceOutageException) e).service())
        .isEqualTo("finder");
    assertThat(count("source_sample")).isZero();
    verify(0, getRequestedFor(urlPathEqualTo("/crossref/works")));
  }

  @Test
  void clbOutageDefers(WireMockRuntimeInfo w) {
    stubFor(get(urlPathMatching(MATCH)).willReturn(aResponse().withStatus(503)));
    stubCrossref(6, 0);
    long id = candidate(null, ISSN);
    assertThatThrownBy(() -> handler(w).handle(task(id))).isInstanceOf(ServiceOutageException.class);
    assertThat(count("source_sample")).isZero();
    assertThat(sources.get(id).candidateScore()).isNull();
  }

  @Test
  void crossrefOutageDefers(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo("/crossref/works")).willReturn(aResponse().withStatus(503)));
    long id = candidate(null, ISSN);
    assertThatThrownBy(() -> handler(w).handle(task(id))).isInstanceOf(ServiceOutageException.class);
    assertThat(count("source_sample")).isZero();
  }

  @Test
  void ceasedOrPromotedSourceSkipped(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(6, 0);
    long ceased = candidate(null, ISSN);
    jdbc.update("UPDATE source SET publication_status = 'CEASED' WHERE id = ?", ceased);
    long promoted = candidate("S9", "2118-9773");
    sources.setTier(promoted, "CORE", true, "ADMIN");

    handler(w).handle(task(ceased));
    handler(w).handle(task(promoted));
    handler(w).handle(task(987654321L));

    assertThat(count("source_sample")).isZero();
    verify(0, getRequestedFor(urlPathMatching("/.*")));
  }

  @Test
  void secondDeliveryAfterFreshSampleMakesNoRequestAndNoRow(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(6, 0);
    long id = candidate(null, ISSN);
    handler(w).handle(task(id));
    assertThat(count("source_sample")).isEqualTo(1);
    int requests = findAll(getRequestedFor(urlMatching(".*"))).size();

    handler(w).handle(task(id)); // a duplicate delivery

    assertThat(count("source_sample")).isEqualTo(1);
    assertThat(findAll(getRequestedFor(urlMatching(".*")))).hasSize(requests);
  }

  @Test
  void aSampleOverAYearOldIsReplaced(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(6, 0);
    long id = candidate(null, ISSN);
    handler(w).handle(task(id));
    jdbc.update("UPDATE source_sample SET sampled_at = now() - interval '400 days'");
    handler(w).handle(task(id));
    assertThat(count("source_sample")).isEqualTo(2);
  }

  @Test
  void forceResamplesAFreshSample(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(6, 0);
    long id = candidate(null, ISSN);
    handler(w).handle(task(id));
    handler(w).handle(task(id, ",\"force\":true"));
    assertThat(count("source_sample")).isEqualTo(2);
  }

  @Test
  void pausedOaiRouteIsNotFetched(WireMockRuntimeInfo w) throws Exception {
    stubClb();
    stubCrossref(2, 0);
    stubNoOtherRoutes();
    stubFor(get(urlPathEqualTo("/oai")).willReturn(aResponse().withBody(oai(7, LocalDate.now(ZoneOffset.UTC)))));
    long id = candidate(null, ISSN);
    insertRoute(id, "PAUSED", "{\"base\":\"" + w.getHttpBaseUrl() + "/oai\",\"set\":null,\"prefix\":\"oai_dc\"}");
    handler(w).handle(task(id));
    verify(0, getRequestedFor(urlPathMatching("/oai.*")));
  }

  // --- helpers ----------------------------------------------------------------------------------------------------

  private void insertRoute(long sourceId, String status, String config) {
    routes.insert(new SourceRoute(0, sourceId, "OAI", config, status, "DISCOVERED", null, null, null, null, null,
        null));
  }

  private static String oai(int n, LocalDate date) {
    StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?><OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\">"
        + "<ListRecords>");
    for (int i = 0; i < n; i++) {
      sb.append("<record><header><identifier>oai:x:").append(i).append("</identifier><datestamp>").append(date)
          .append("</datestamp></header><metadata><oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" "
              + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>OAI article ").append(i)
          .append("</dc:title><dc:description>").append(ABS).append("</dc:description><dc:date>").append(date)
          .append("</dc:date></oai_dc:dc></metadata></record>");
    }
    return sb.append("</ListRecords></OAI-PMH>").toString();
  }
}
