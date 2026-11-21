package org.catalogueoflife.nametracker.indexer.content.institutional;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.fixture;
import static org.catalogueoflife.nametracker.indexer.content.route.RouteTestSupport.http;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
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
import org.catalogueoflife.nametracker.indexer.TestHttpClients;
import org.catalogueoflife.nametracker.indexer.content.ContentRoute;
import org.catalogueoflife.nametracker.indexer.content.LicencePolicy;
import org.catalogueoflife.nametracker.indexer.content.ResolveContentHandler;
import org.catalogueoflife.nametracker.indexer.content.route.EuropePmcRoute;
import org.catalogueoflife.nametracker.indexer.content.route.PmcidResolver;
import org.catalogueoflife.nametracker.indexer.finder.FindNamesHandler;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.finder.Spool;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.content.Content;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.content.InstitutionalFetchMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** The institutional route against WireMock as Elsevier, Wiley and GROBID; never a live publisher API, no real token. */
@WireMockTest
@Import(InstitutionalRouteIT.StubConfig.class)
class InstitutionalRouteIT extends AbstractPostgresIT {

  static final Path DIR = tempDir();
  static final String ELSEVIER_DOI = "10.1016/j.ympev.2020.106789";
  static final String ELSEVIER_PATH = "/content/article/doi/" + ELSEVIER_DOI;
  static final String WILEY_DOI = "10.1111/zoj.12345";
  static final String WILEY_PATH = "/onlinelibrary/tdm/v1/articles/10.1111%2Fzoj.12345";

  static Path tempDir() {
    try {
      Path p = Files.createTempDirectory("garrulus-institutional");
      p.toFile().deleteOnExit();
      return p;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.blobs.dir", () -> DIR.resolve("blobs").toString());
    r.add("nametracker.content.spool-dir", () -> DIR.resolve("spool").toString());
  }

  /** A finder that records the documents it is given and finds "Aus bus" in any block. */
  static class StubFinder implements NameFinder {
    final List<Document> seen = new CopyOnWriteArrayList<>();

    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", "cfg1", null);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      seen.add(doc);
      List<Mention> ms = new ArrayList<>();
      for (Block b : doc.blocks()) {
        int cs = b.text().indexOf("Aus bus");
        if (cs < 0) continue;
        int start = CodePoints.toCodePoint(b.text(), cs);
        ms.add(new Mention("m" + (ms.size() + 1), b.id(), start, start + 7, "Aus bus", "Aus bus", NameKind.BINOMIAL,
            Rank.SPECIES, null, null, 0.9, new Evidence(Set.of(Generator.DICTIONARY), null, null), null, null,
            Map.of()));
      }
      return new FinderResult(info(), ms, List.of(), List.of(), List.of(), Map.of());
    }
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    StubFinder institutionalStubFinder() {
      return new StubFinder();
    }
  }

  @Autowired StubFinder finder;
  @Autowired InstitutionalRoute contextRoute;
  @Autowired FindNamesHandler findNames;
  @Autowired TaskQueue queue;
  @Autowired WorkMapper works;
  @Autowired SourceMapper sources;
  @Autowired ContentMapper contents;
  @Autowired InstitutionalFetchMapper audit;
  @Autowired BlobStore blobs;
  @Autowired LicencePolicy policy;
  @Autowired Spool spool;
  @Autowired IndexMapper index;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() throws IOException {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM institutional_fetch");
    finder.seen.clear();
    for (Path d : List.of(DIR.resolve("blobs"), DIR.resolve("spool"))) {
      if (!Files.isDirectory(d)) continue;
      try (Stream<Path> s = Files.walk(d)) {
        s.filter(Files::isRegularFile).forEach(f -> f.toFile().delete());
      }
    }
  }

  // ---- set up

  /** Everything configured and pointed at WireMock; only the switch, the allow-list, the cap and GROBID vary. */
  private static InstitutionalProperties props(WireMockRuntimeInfo w, boolean enabled, String publishers, int cap,
      String grobid) {
    String base = w.getHttpBaseUrl();
    return props(w, enabled, publishers, cap, grobid, 5);
  }

  private static InstitutionalProperties props(WireMockRuntimeInfo w, boolean enabled, String publishers, int cap,
      String grobid, int max403) {
    String base = w.getHttpBaseUrl();
    return new InstitutionalProperties(enabled, publishers, grobid, Duration.ofDays(365), max403, base,
        "test-els-key", "test-els-inst", cap, base, "test-wiley-token", cap);
  }

  private static InstitutionalProperties on(WireMockRuntimeInfo w) {
    return props(w, true, "elsevier,wiley", 500, w.getHttpBaseUrl() + "/grobid");
  }

  private InstitutionalRoute route(InstitutionalProperties p) {
    return route(p, http(), InstitutionalRoute.LOCK_WAIT);
  }

  private InstitutionalRoute route(InstitutionalProperties p, PoliteHttp http, Duration lockWait) {
    return new InstitutionalRoute(p, http, audit, contents, policy, Clock.systemUTC(), word -> false, lockWait);
  }

  /** A route that throws what it is given, standing in for an open route that is down or out of budget. */
  private static ContentRoute failing(RuntimeException e) {
    return new ContentRoute() {
      @Override
      public String route() {
        return "PMC";
      }

      @Override
      public boolean applies(Work w, @Nullable Source s) {
        return true;
      }

      @Override
      public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
        throw e;
      }
    };
  }

  private ResolveContentHandler handler(ContentRoute... routes) {
    return new ResolveContentHandler(List.of(routes), works, sources, contents, blobs, policy, spool, index, queue, tx,
        Clock.systemUTC());
  }

  private long work(String doi, String abs) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, abstract, abstract_source, license, origin) "
        + "VALUES (?, 'A title', ?, ?, NULL, 'LIVE') RETURNING id", Long.class, doi, abs,
        abs == null ? null : "CROSSREF_JATS");
  }

  private static Task resolve(long workId) {
    return resolve("{\"workId\":" + workId + "}");
  }

  private static Task resolve(String payload) {
    return new Task(1, "RESOLVE_CONTENT", payload, TaskPriority.LIVE, null, 1, Instant.now(), null, null, null,
        "content:x", null);
  }

  private static Task find(long workId) {
    return new Task(2, "FIND_NAMES", "{\"workId\":" + workId + "}", TaskPriority.LIVE, null, 1,
        Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS), null, null, null, "k",
        null);
  }

  private static String elsevierXml(String description, String para) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<full-text-retrieval-response xmlns=\"http://www.elsevier.com/xml/svapi/article/dtd\" "
        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:ce=\"http://www.elsevier.com/xml/common/dtd\">"
        + "<coredata><dc:title>A title</dc:title><dc:description>" + description + "</dc:description></coredata>"
        + "<originalText><ce:sections><ce:section><ce:section-title>Results</ce:section-title><ce:para>" + para
        + "</ce:para></ce:section></ce:sections></originalText></full-text-retrieval-response>";
  }

  private static void elsevierAnswers(String xml) {
    stubFor(get(urlPathEqualTo(ELSEVIER_PATH)).willReturn(aResponse().withHeader("Content-Type", "text/xml")
        .withBody(xml)));
  }

  private List<Map<String, Object>> auditRows() {
    return jdbc.queryForList("SELECT work_id, doi, publisher, api, status, bytes, outcome FROM institutional_fetch "
        + "ORDER BY id");
  }

  private static long fileCount(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) return 0;
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).count();
    }
  }

  private Content row(long workId, String route) {
    return contents.listByWork(workId).stream().filter(c -> route.equals(c.route())).findFirst().orElseThrow();
  }

  // ---- tests

  @Test
  void offByDefaultMakesNoCall(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    long id = work(ELSEVIER_DOI, null);

    // the application's own bean: default properties are off
    assertThat(contextRoute.publishers()).isEmpty();
    assertThat(contextRoute.applies(works.get(id), null)).isFalse();
    // everything configured except the switch
    handler(route(props(w, false, "elsevier,wiley", 500, w.getHttpBaseUrl()))).handle(resolve(id));

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(auditRows()).isEmpty();
    assertThat(works.get(id).textLevel()).isEqualTo("ABSTRACT_ONLY");
  }

  @Test
  void allowListRequired(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    long id = work(ELSEVIER_DOI, null);
    InstitutionalRoute r = route(props(w, true, "", 500, w.getHttpBaseUrl()));

    handler(r).handle(resolve(id));

    assertThat(r.publishers()).isEmpty();
    verify(0, anyRequestedFor(anyUrl()));
    assertThat(auditRows()).isEmpty();
  }

  @Test
  void wileyNeedsGrobid(WireMockRuntimeInfo w) {
    assertThat(route(props(w, true, "elsevier,wiley", 500, "")).publishers()).containsExactly("ELSEVIER");
    assertThat(route(props(w, true, "ELSEVIER, Wiley", 500, w.getHttpBaseUrl())).publishers())
        .containsExactly("ELSEVIER", "WILEY");
  }

  @Test
  void elsevierFullTextIsClosedAndSpooled(WireMockRuntimeInfo w) throws Exception {
    String para = "word ".repeat(80) + "Aus bus" + " more".repeat(80);
    elsevierAnswers(elsevierXml("An abstract.", para));
    long id = work(ELSEVIER_DOI, "The stored abstract.");

    handler(route(on(w))).handle(resolve(id));

    verify(1, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)).withQueryParam("httpAccept", equalTo("text/xml"))
        .withHeader("X-ELS-APIKey", equalTo("test-els-key")).withHeader("X-ELS-Insttoken", equalTo("test-els-inst")));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
    Content c = row(id, "INSTITUTIONAL");
    assertThat(c.kind()).isEqualTo("ELSEVIER_XML");
    assertThat(c.license()).isNull();
    assertThat(c.retained()).isFalse();
    assertThat(c.sourceUrl()).isEqualTo(w.getHttpBaseUrl() + ELSEVIER_PATH + "?httpAccept=text/xml");
    assertThat(fileCount(DIR.resolve("blobs"))).isZero(); // closed: hashes only
    assertThat(spool.get(id, c.docSha256())).isPresent(); // waits for FIND_NAMES
    assertThat(auditRows()).singleElement().satisfies(a -> {
      assertThat(a).containsEntry("work_id", id).containsEntry("doi", ELSEVIER_DOI)
          .containsEntry("publisher", "ELSEVIER").containsEntry("api", "ELSEVIER_TDM").containsEntry("status", 200)
          .containsEntry("outcome", "OK");
      assertThat((Integer) a.get("bytes")).isPositive();
    });

    findNames.handle(find(id));

    assertThat(spool.get(id, c.docSha256())).isEmpty(); // purged once named
    assertThat(fileCount(DIR.resolve("spool"))).isZero();
    List<MentionRow> ms = index.mentions(id);
    assertThat(ms).singleElement().satisfies(m -> {
      assertThat(m.blockType()).isEqualTo("PARAGRAPH");
      assertThat(m.verbatim()).isEqualTo("Aus bus");
      assertThat(CodePoints.length(m.snippet())).isLessThanOrEqualTo(200);
      assertThat(m.snippet()).contains("Aus bus");
    });
  }

  @Test
  void abstractNeverStored(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("We describe Aus bus sp. nov.", "Body text without names."));
    long id = work(ELSEVIER_DOI, null);

    handler(route(on(w))).handle(resolve(id));
    findNames.handle(find(id));

    Map<String, Object> wk = jdbc.queryForMap("SELECT abstract, abstract_jats, abstract_source FROM work WHERE id = ?",
        id);
    assertThat(wk.get("abstract")).isNull();
    assertThat(wk.get("abstract_jats")).isNull();
    assertThat(wk.get("abstract_source")).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_text WHERE work_id = ?", Integer.class, id)).isZero();
    // the finder read it inside the spooled document, and only a mention with its snippet is kept
    assertThat(finder.seen).singleElement().satisfies(d -> assertThat(d.blocks())
        .anySatisfy(b -> assertThat(b.type()).isEqualTo(BlockType.ABSTRACT)));
    assertThat(index.mentions(id)).singleElement()
        .satisfies(m -> assertThat(m.blockType()).isEqualTo("ABSTRACT"));
  }

  @Test
  void everyFetchAudited(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.1")).willReturn(aResponse().withStatus(403)));
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.2")).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo(WILEY_PATH)).willReturn(aResponse().withHeader("Content-Type", "application/pdf")
        .withBody("%PDF-1.7 fake pdf bytes")));
    stubFor(post(urlPathEqualTo("/grobid/api/processFulltextDocument")).willReturn(aResponse()
        .withHeader("Content-Type", "application/xml").withBody(fixture("openalex/tei-small.xml"))));
    long refused = work("10.1016/j.a.2020.1", null);
    long missing = work("10.1016/j.a.2020.2", null);
    long wiley = work(WILEY_DOI, null);
    ResolveContentHandler h = handler(route(on(w)));

    for (long id : List.of(refused, missing, wiley)) h.handle(resolve(id));

    assertThat(auditRows()).extracting(a -> a.get("work_id") + " " + a.get("publisher") + " " + a.get("status") + " "
        + a.get("outcome")).containsExactly(refused + " ELSEVIER 403 REFUSED", missing + " ELSEVIER 404 NOT_FOUND",
        wiley + " WILEY 200 OK");
    assertThat(works.get(refused).textLevel()).isEqualTo("ABSTRACT_ONLY");
    assertThat(works.get(wiley).textLevel()).isEqualTo("FULLTEXT");
    Content c = row(wiley, "INSTITUTIONAL");
    assertThat(c.kind()).isEqualTo("TEI");
    assertThat(c.retained()).isFalse();
    verify(getRequestedFor(urlPathEqualTo(WILEY_PATH)).withHeader("Wiley-TDM-Client-Token",
        equalTo("test-wiley-token")));
    verify(1, postRequestedFor(urlPathEqualTo("/grobid/api/processFulltextDocument"))
        .withHeader("Wiley-TDM-Client-Token", absent())
        .withRequestBodyPart(aMultipart().withName("input").build()));
  }

  @Test
  void unauthorizedSwitchesThePublisherOff(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/content/article/doi/.*")).willReturn(aResponse().withStatus(401)));
    long a = work("10.1016/j.a.2020.1", null);
    long b = work("10.1016/j.a.2020.2", null);
    ResolveContentHandler h = handler(route(on(w)));

    h.handle(resolve(a));
    h.handle(resolve(b));

    verify(1, getRequestedFor(urlPathMatching("/content/article/doi/.*")));
    assertThat(auditRows()).extracting(r -> r.get("status") + " " + r.get("outcome"))
        .containsExactly("401 UNAUTHORIZED");
  }

  @Test
  void dailyCapStops(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/content/article/doi/.*")).willReturn(aResponse().withStatus(404)));
    // yesterday's fetches do not count
    jdbc.update("INSERT INTO institutional_fetch (doi, publisher, api, status, bytes, outcome, fetched_at) "
        + "VALUES ('10.1016/old', 'ELSEVIER', 'ELSEVIER_TDM', 404, 0, 'NOT_FOUND', now() - interval '1 day')");
    long a = work("10.1016/j.a.2020.1", null);
    long b = work("10.1016/j.a.2020.2", null);
    long c = work("10.1016/j.a.2020.3", null);
    ResolveContentHandler h = handler(route(props(w, true, "elsevier", 2, "")));

    h.handle(resolve(a));
    h.handle(resolve(b));
    ServiceOutageException deferred = assertThrows(ServiceOutageException.class, () -> h.handle(resolve(c)));

    verify(2, getRequestedFor(urlPathMatching("/content/article/doi/.*")));
    assertThat(auditRows()).extracting(r -> r.get("work_id")).containsExactly(null, a, b);
    // to the next UTC day, nothing written meanwhile
    assertThat(deferred.retryAt()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1).atTime(0, 5)
        .toInstant(ZoneOffset.UTC));
    assertThat(works.get(c).textLevel()).isNull();
  }

  @Test
  void openRouteFirst(WireMockRuntimeInfo w) throws Exception {
    long id = work(ELSEVIER_DOI, null);
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withBody("{\"resultList\":{\"result\":[{"
        + "\"pmcid\":\"PMC9836452\",\"doi\":\"" + ELSEVIER_DOI + "\",\"isOpenAccess\":\"Y\"}]}}")));
    stubFor(get(urlPathEqualTo("/rest/PMC9836452/fullTextXML"))
        .willReturn(aResponse().withBody(fixture("europepmc/PMC9836452-fulltext.xml"))));
    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    String base = w.getHttpBaseUrl();
    EuropePmcRoute open = new EuropePmcRoute(http(), new PmcidResolver(http(), works, base), base);

    handler(open, route(on(w))).handle(resolve(id));

    verify(0, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
    assertThat(auditRows()).isEmpty();
    assertThat(contents.listByWork(id)).extracting(Content::route).containsExactlyInAnyOrder("EUROPEPMC", "ABSTRACT");
  }

  @Test
  void fetchedWorkIsNotFetchedAgainExceptOnReprocess(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    long id = work(ELSEVIER_DOI, null);
    ResolveContentHandler h = handler(route(on(w)));
    h.handle(resolve(id));
    findNames.handle(find(id)); // the spool is purged

    h.handle(resolve("{\"workId\":" + id + ",\"recheck\":1}"));
    h.handle(resolve("{\"workId\":" + id + ",\"freeOnly\":true}"));
    verify(1, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT"); // never downgraded

    // a forced FIND_NAMES found the text gone: the re-fetch for reprocessing may ask again
    h.handle(resolve("{\"workId\":" + id + ",\"freeOnly\":true,\"reprocess\":true}"));
    verify(2, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
    assertThat(auditRows()).hasSize(2);
    Content c = row(id, "INSTITUTIONAL");
    assertThat(spool.get(id, c.docSha256())).isPresent();
  }

  @Test
  void anOutageIsOneRequestOneAuditRowAndDefers(WireMockRuntimeInfo w) {
    stubFor(get(urlPathEqualTo(ELSEVIER_PATH)).willReturn(aResponse().withStatus(503)));
    long id = work(ELSEVIER_DOI, null);
    Instant before = Instant.now();

    ServiceOutageException e = assertThrows(ServiceOutageException.class,
        () -> handler(route(on(w))).handle(resolve(id)));

    verify(1, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH))); // no retry inside the route
    assertThat(auditRows()).extracting(r -> r.get("status") + " " + r.get("outcome")).containsExactly("503 ERROR");
    assertThat(audit.countSince("ELSEVIER", before.minusSeconds(5))).isEqualTo(1); // one cap unit
    assertThat(e.retryAt()).isAfter(before.plus(Duration.ofMinutes(29)));
    assertThat(works.get(id).textLevel()).isNull(); // deferred before anything was written
  }

  @Test
  void anOutageDoesNotBlockTheNextTry(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo(ELSEVIER_PATH)).willReturn(aResponse().withStatus(503)));
    long id = work(ELSEVIER_DOI, null);
    ResolveContentHandler h = handler(route(on(w)));
    assertThrows(ServiceOutageException.class, () -> h.handle(resolve(id)));

    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    h.handle(resolve("{\"workId\":" + id + ",\"recheck\":1}"));

    assertThat(auditRows()).extracting(r -> r.get("outcome")).containsExactly("ERROR", "OK");
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
  }

  @Test
  void skippedWhenAnOpenRouteWasDown(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    long id = work(ELSEVIER_DOI, "The stored abstract.");
    HttpProblem down = new HttpProblem(URI.create("http://localhost/x"), 503, true, "down", null);

    handler(failing(down), route(on(w))).handle(resolve(id));

    verify(0, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
    assertThat(auditRows()).isEmpty();
    assertThat(works.get(id).textLevel()).isEqualTo("ABSTRACT_ONLY"); // the normal recheck follows
  }

  @Test
  void skippedWhenAnOpenRouteWaitsForItsBudget(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("An abstract.", "Aus bus here."));
    long id = work(ELSEVIER_DOI, "The stored abstract.");

    handler(failing(new BudgetExhaustedException(Instant.now().plus(Duration.ofHours(3)))), route(on(w)))
        .handle(resolve(id));

    verify(0, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
    assertThat(works.get(id).textLevel()).isEqualTo("ABSTRACT_ONLY");
  }

  @Test
  void consecutive403sSwitchThePublisherOffWithoutBlockingTheWorks(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathMatching("/content/article/doi/.*")).willReturn(aResponse().withStatus(403)));
    List<Long> ids = new ArrayList<>();
    for (int i = 1; i <= 4; i++) ids.add(work("10.1016/j.a.2020." + i, null));
    InstitutionalRoute r = route(props(w, true, "elsevier", 500, "", 3));
    ResolveContentHandler h = handler(r);

    for (long id : ids) h.handle(resolve(id));

    verify(3, getRequestedFor(urlPathMatching("/content/article/doi/.*")));
    assertThat(auditRows()).extracting(a -> a.get("status") + " " + a.get("outcome"))
        .containsExactly("403 REFUSED_SYSTEMIC", "403 REFUSED_SYSTEMIC", "403 REFUSED_SYSTEMIC");
    // after a restart (a new route) the works are asked again: the token was at fault, not the articles
    InstitutionalRoute restarted = route(props(w, true, "elsevier", 500, "", 3));
    assertThat(restarted.applies(works.get(ids.get(0)), null)).isTrue();
  }

  @Test
  void aRefusalBelowTheBreakerKeepsItsBlock(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.1")).willReturn(aResponse().withStatus(403)));
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.2")).willReturn(aResponse().withStatus(404)));
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.3")).willReturn(aResponse().withStatus(403)));
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.4")).willReturn(aResponse().withStatus(403)));
    long a = work("10.1016/j.a.2020.1", null);
    long b = work("10.1016/j.a.2020.2", null);
    long c = work("10.1016/j.a.2020.3", null);
    long d = work("10.1016/j.a.2020.4", null);
    InstitutionalRoute r = route(props(w, true, "elsevier", 500, "", 3));
    ResolveContentHandler h = handler(r);

    for (long id : List.of(a, b, c, d)) h.handle(resolve(id)); // the 404 broke the streak: 1, then 2 in a row

    assertThat(r.publishers()).containsExactly("ELSEVIER");
    assertThat(auditRows()).extracting(x -> x.get("outcome"))
        .containsExactly("REFUSED", "NOT_FOUND", "REFUSED", "REFUSED");
    assertThat(r.applies(works.get(a), null)).isFalse(); // not asked again for a year
  }

  @Test
  void aFailedFetchBlocksTheWorkForThirtyDays(WireMockRuntimeInfo w) throws Exception {
    elsevierAnswers(elsevierXml("An abstract.", "x".repeat(500)));
    long id = work(ELSEVIER_DOI, null);
    PoliteHttp small = new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> { }, 200);
    ResolveContentHandler h = handler(route(on(w), small, InstitutionalRoute.LOCK_WAIT));

    h.handle(resolve(id)); // the body is over the size limit: a failure that would repeat
    h.handle(resolve("{\"workId\":" + id + ",\"recheck\":1}"));

    verify(1, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
    assertThat(auditRows()).extracting(r -> r.get("outcome")).containsExactly("FAILED");
    jdbc.update("UPDATE institutional_fetch SET fetched_at = now() - interval '31 days'");
    h.handle(resolve("{\"workId\":" + id + ",\"recheck\":2}"));
    verify(2, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)));
  }

  @Test
  void aBusyPublisherDefersInsteadOfWaiting(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.1")).willReturn(aResponse()
        .withFixedDelay(1500).withBody(elsevierXml("An abstract.", "Aus bus here."))));
    long a = work("10.1016/j.a.2020.1", null);
    long b = work("10.1016/j.a.2020.2", null);
    ResolveContentHandler h = handler(route(on(w), http(), Duration.ofMillis(100)));

    var slow = java.util.concurrent.CompletableFuture.runAsync(() -> {
      try {
        h.handle(resolve(a));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    Thread.sleep(500); // a holds Elsevier's lock while its request runs
    ServiceOutageException e = assertThrows(ServiceOutageException.class, () -> h.handle(resolve(b)));
    slow.get();

    assertThat(e.retryAt()).isAfter(Instant.now());
    verify(0, getRequestedFor(urlPathEqualTo("/content/article/doi/10.1016/j.a.2020.2")));
    assertThat(auditRows()).extracting(r -> r.get("work_id")).containsExactly(a);
  }

  @Test
  void tokenNotSentAfterCrossHostRedirect(WireMockRuntimeInfo w) throws Exception {
    // localhost and 127.0.0.1 are two authorities of the same WireMock
    stubFor(get(urlPathEqualTo(ELSEVIER_PATH)).willReturn(aResponse().withStatus(302)
        .withHeader("Location", "http://127.0.0.1:" + w.getHttpPort() + "/moved")));
    stubFor(get(urlPathEqualTo("/moved")).willReturn(aResponse().withBody(elsevierXml("An abstract.",
        "Aus bus here."))));
    long id = work(ELSEVIER_DOI, null);

    handler(route(on(w))).handle(resolve(id));

    verify(1, getRequestedFor(urlPathEqualTo(ELSEVIER_PATH)).withHeader("X-ELS-APIKey", equalTo("test-els-key")));
    verify(1, getRequestedFor(urlPathEqualTo("/moved")).withHeader("X-ELS-APIKey", absent())
        .withHeader("X-ELS-Insttoken", absent()));
    assertThat(works.get(id).textLevel()).isEqualTo("FULLTEXT");
  }
}
