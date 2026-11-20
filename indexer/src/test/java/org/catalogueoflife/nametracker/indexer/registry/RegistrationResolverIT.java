package org.catalogueoflife.nametracker.indexer.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClientTest;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.net.PublicHost;
import org.catalogueoflife.nametracker.store.registry.RegistrationMapper;
import org.catalogueoflife.nametracker.store.registry.SourceRegistration;
import org.catalogueoflife.nametracker.store.registry.SourceSignal;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@WireMockTest
class RegistrationResolverIT extends AbstractPostgresIT {

  static final String DOI = "10.11646/zootaxa.5200.1.1";

  /** No DNS: journal.test (allowed, the mock server) and internal.test both name 127.0.0.1, intranet a private host. */
  static final PublicHost.Resolver FAKE = host -> {
    Map<String, String> known = Map.of("journal.test", "127.0.0.1", "internal.test", "127.0.0.1",
        "intranet.example.org", "10.0.0.5");
    String a = known.get(host);
    if (a == null) throw new UnknownHostException(host);
    return new InetAddress[] {InetAddress.getByName(a)};
  };

  @Autowired RegistrationMapper registrations;
  @Autowired SourceMapper sources;
  @Autowired SourceSignalMapper signals;
  @Autowired SourceRouteMapper routes;
  @Autowired CandidateRegistry registry;
  @Autowired SourceIdentity identity;
  @Autowired IdentityLocks locks;
  @Autowired JournalCompletion completion;
  @Autowired TransactionTemplate tx;
  @Autowired HarvestOutage outage;
  @Autowired ApiUsageMapper usage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  long user;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM subscription");
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM app_user WHERE email LIKE '%@register.test'");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM source_sample");
    jdbc.update("UPDATE source SET continued_by = NULL");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
    user = jdbc.queryForObject("INSERT INTO app_user (email, email_verified) VALUES ('u' || nextval('app_user_id_seq') "
        + "|| '@register.test', now()) RETURNING id", Long.class);
  }

  // --- wiring -----------------------------------------------------------------------------------------------------

  private IdentifySourceHandler handler(WireMockRuntimeInfo w) {
    PoliteHttp http = EuropePmcSearchClientTest.http();
    String base = w.getHttpBaseUrl();
    OpenAlexBudget budget = new OpenAlexBudget(usage, new BigDecimal("1.00"), new BigDecimal("0.30"), clock);
    JournalPage page = new JournalPage(http, new PublicHost(FAKE, Set.of("journal.test")));
    RegistrationResolver resolver = new RegistrationResolver(new CrossrefClient(http, null, base + "/crossref"),
        new OpenAlexClient(http, budget, null, base + "/openalex"), page);
    return new IdentifySourceHandler(completion, new RegistrationIdentifier(registrations, resolver, registry,
        identity, locks, sources, signals, tx), sources, outage, clock);
  }

  private long register(String kind, String input) {
    return jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, note) VALUES (?, ?, ?, "
        + "'please add') RETURNING id", Long.class, user, kind, input);
  }

  private static Task task(long registrationId) {
    return new Task(0, IdentifySourceHandler.TYPE, "{\"registrationId\":" + registrationId + "}", TaskPriority.LIVE,
        TaskState.RUNNING, 1, null, null, null, null, null, null);
  }

  private SourceRegistration identify(WireMockRuntimeInfo w, long id) {
    handler(w).handle(task(id));
    return registrations.get(id);
  }

  private static String journal(WireMockRuntimeInfo w) {
    return "http://journal.test:" + w.getHttpPort();
  }

  private List<Map<String, Object>> tasks() {
    return jdbc.queryForList("SELECT type, payload::text AS payload, priority, dedup_key FROM task ORDER BY id");
  }

  private long sourceCount() {
    return jdbc.queryForObject("SELECT count(*) FROM source", Long.class);
  }

  private long existing(String tier, String status, String... issns) {
    return jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns, publication_status) VALUES "
        + "('Existing', 'MANUAL', ?, ?::text[], ?) RETURNING id", Long.class, tier, "{" + String.join(",", issns) + "}",
        status);
  }

  // --- tests ------------------------------------------------------------------------------------------------------

  @Test
  void registerByIssnCreatesCandidateWithSignal(WireMockRuntimeInfo w) {
    long id = register("ISSN", "2118-9773");
    SourceRegistration r = identify(w, id);
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    assertThat(r.outcome()).isEqualTo("NEW_CANDIDATE");
    assertThat(r.tierAtIdentification()).isEqualTo("CANDIDATE");
    assertThat(r.identifiedAt()).isNotNull();
    Source s = sources.get(r.sourceId());
    assertThat(s.origin()).isEqualTo("REGISTERED");
    assertThat(s.tier()).isEqualTo("CANDIDATE");
    assertThat(s.issns()).containsExactly("2118-9773");
    List<SourceSignal> sig = signals.forSource(s.id());
    assertThat(sig).singleElement().satisfies(g -> {
      assertThat(g.origin()).isEqualTo("REGISTERED");
      assertThat(g.signalKey()).isEqualTo(String.valueOf(id));
      assertThat(FinderJson.mapper().readTree(g.detail()).get("registrationId").asLong()).isEqualTo(id);
    });
    // the completion of the new source runs as its own task at LIVE
    assertThat(tasks()).singleElement().satisfies(t -> {
      assertThat(t.get("type")).isEqualTo("IDENTIFY_SOURCE");
      assertThat(FinderJson.mapper().readTree((String) t.get("payload")).get("sourceId").asLong()).isEqualTo(s.id());
      assertThat(((Number) t.get("priority")).intValue()).isZero();
      assertThat(t.get("dedup_key")).isEqualTo("identify:s" + s.id());
    });
    // an ISSN needs no outbound request
    verify(0, anyRequestedFor(anyUrl()));
  }

  @Test
  void registerByArticleDoiFindsJournal(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/crossref/works/" + DOI))
        .willReturn(okJson(DoajClientTest.fixture("crossref-work-doi.json"))));
    SourceRegistration r = identify(w, register("DOI", DOI));
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    assertThat(r.outcome()).isEqualTo("NEW_CANDIDATE");
    Source s = sources.get(r.sourceId());
    assertThat(s.issns()).containsExactlyInAnyOrder("1175-5326", "1175-5334");
    assertThat(s.title()).isEqualTo("Zootaxa");
    assertThat(s.publisher()).isEqualTo("Magnolia Press");
    verify(0, getRequestedFor(urlPathMatching("/openalex/.*")));

    // Crossref does not know it: OpenAlex's source of the work
    String doi2 = "10.5852/ejt.2024.900.1";
    stubFor(get(urlPathEqualTo("/crossref/works/" + doi2)).willReturn(notFound()));
    stubFor(get(urlPathMatching("/openalex/works/.*")).willReturn(okJson("""
        {"id": "https://openalex.org/W1", "primary_location": {"source": {"id": "https://openalex.org/S4210175727",
         "display_name": "European Journal of Taxonomy", "issn_l": "2118-9773", "issn": ["2118-9773"],
         "host_organization_name": "Muséum national d'Histoire naturelle", "type": "journal"}}}""")));
    SourceRegistration r2 = identify(w, register("DOI", doi2));
    assertThat(r2.state()).isEqualTo("IDENTIFIED");
    Source s2 = sources.get(r2.sourceId());
    assertThat(s2.issns()).containsExactly("2118-9773");
    assertThat(s2.openalexId()).isEqualTo("S4210175727");
    assertThat(s2.title()).isEqualTo("European Journal of Taxonomy");
  }

  @Test
  void coveredJournalReportsCovered(WireMockRuntimeInfo w) {
    long core = existing("CORE", "ACTIVE", "1175-5326", "1175-5334");
    long before = sourceCount();
    long id = register("ISSN", "1175-5334");
    SourceRegistration r = identify(w, id);
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    assertThat(r.outcome()).isEqualTo("EXISTING");
    assertThat(r.tierAtIdentification()).isEqualTo("CORE");
    assertThat(r.sourceId()).isEqualTo(core);
    assertThat(sourceCount()).isEqualTo(before);
    assertThat(signals.forSource(core)).extracting(SourceSignal::origin, SourceSignal::signalKey)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("REGISTERED", String.valueOf(id)));
    assertThat(tasks()).isEmpty();
  }

  @Test
  void ceasedJournalReportsCeased(WireMockRuntimeInfo w) {
    long ceased = existing("BROAD", "CEASED", "1638-9387");
    SourceRegistration r = identify(w, register("ISSN", "1638-9387"));
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    assertThat(r.outcome()).isEqualTo("CEASED");
    assertThat(r.sourceId()).isEqualTo(ceased);
    assertThat(r.tierAtIdentification()).isEqualTo("BROAD");
  }

  @Test
  void privateAddressIsNeverFetched(WireMockRuntimeInfo w) {
    stubFor(get(anyUrl()).willReturn(ok("<p>ISSN 1175-5326</p>").withHeader("Content-Type", "text/html")));
    for (String url : new String[] {"http://127.0.0.1:" + w.getHttpPort() + "/journal",
        "http://localhost:" + w.getHttpPort() + "/journal", "http://internal.test:" + w.getHttpPort() + "/journal",
        "http://intranet.example.org/journal", "http://[::1]:" + w.getHttpPort() + "/journal"}) {
      SourceRegistration r = identify(w, register("URL", url));
      assertThat(r.state()).as(url).isEqualTo("NOT_IDENTIFIED");
      assertThat(r.reason()).as(url).isEqualTo("ADDRESS_NOT_PUBLIC");
      assertThat(r.sourceId()).isNull();
    }
    SourceRegistration unknown = identify(w, register("URL", "http://nowhere.example.org/"));
    assertThat(unknown.reason()).isEqualTo("ADDRESS_UNKNOWN");
    verify(0, anyRequestedFor(anyUrl()));
    assertThat(tasks()).isEmpty();
  }

  @Test
  void redirectToPrivateHostStops(WireMockRuntimeInfo w) {
    int port = w.getHttpPort();
    stubFor(get(urlPathEqualTo("/start")).willReturn(temporaryRedirect("/hop")));
    stubFor(get(urlPathEqualTo("/hop")).willReturn(permanentRedirect("http://127.0.0.1:" + port + "/secret")));
    stubFor(get(urlPathEqualTo("/start2")).willReturn(seeOther("http://internal.test:" + port + "/secret")));
    stubFor(get(urlPathEqualTo("/secret")).willReturn(ok("<p>ISSN 1175-5326</p>")
        .withHeader("Content-Type", "text/html")));

    SourceRegistration r = identify(w, register("URL", journal(w) + "/start"));
    assertThat(r.state()).isEqualTo("NOT_IDENTIFIED");
    assertThat(r.reason()).isEqualTo("ADDRESS_NOT_PUBLIC");
    SourceRegistration r2 = identify(w, register("URL", journal(w) + "/start2"));
    assertThat(r2.reason()).isEqualTo("ADDRESS_NOT_PUBLIC");
    verify(1, getRequestedFor(urlPathEqualTo("/start")));
    verify(1, getRequestedFor(urlPathEqualTo("/hop")));
    verify(1, getRequestedFor(urlPathEqualTo("/start2")));
    verify(0, getRequestedFor(urlPathEqualTo("/secret")));
    // each request carries the Garrulus user agent
    verify(getRequestedFor(urlPathEqualTo("/start")).withHeader("User-Agent", matching("Garrulus/.*")));

    // more than three redirects
    stubFor(get(urlPathEqualTo("/loop")).willReturn(temporaryRedirect("/loop")));
    assertThat(identify(w, register("URL", journal(w) + "/loop")).reason()).isEqualTo("PAGE_UNREACHABLE");
    verify(4, getRequestedFor(urlPathEqualTo("/loop")));
  }

  @Test
  void magtechFooterGivesMagtechRoute(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/EN/")).willReturn(ok(DoajClientTest.fixture("journal-page-magtech.html"))
        .withHeader("Content-Type", "text/html;charset=UTF-8")));
    SourceRegistration r = identify(w, register("URL", journal(w) + "/EN/"));
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    Source s = sources.get(r.sourceId());
    assertThat(s.issns()).containsExactly("1005-0094");
    List<SourceRoute> rs = routes.forSource(s.id());
    assertThat(rs).singleElement().satisfies(route -> {
      assertThat(route.layer()).isEqualTo("MAGTECH");
      assertThat(route.status()).isEqualTo("PENDING");
      assertThat(route.origin()).isEqualTo("REGISTERED");
      JsonNode cfg = FinderJson.mapper().readTree(route.config());
      assertThat(cfg.get("site").asString()).isEqualTo(journal(w));
      assertThat(cfg.get("issn").asString()).isEqualTo("1005-0094");
    });
    // a candidate's route is not verified (no request to the site) until an admin promotes the journal
    assertThat(tasks()).extracting(t -> t.get("type")).containsExactly("IDENTIFY_SOURCE");

    // a Magtech site without the footer, by its path: /CN/<ISSN>/
    stubFor(get(urlPathEqualTo("/CN/0254-5853/home.shtml")).willReturn(ok("<html><head><title>Zoological Research"
        + "</title></head><body>no marker</body></html>").withHeader("Content-Type", "text/html")));
    SourceRegistration r2 = identify(w, register("URL", journal(w) + "/CN/0254-5853/home.shtml"));
    assertThat(r2.state()).isEqualTo("IDENTIFIED");
    assertThat(sources.get(r2.sourceId()).issns()).containsExactly("0254-5853");
    assertThat(routes.forSource(r2.sourceId())).singleElement()
        .satisfies(route -> assertThat(route.layer()).isEqualTo("MAGTECH"));
  }

  @Test
  void ojsGeneratorGivesOaiRoute(WireMockRuntimeInfo w) throws Exception {
    String page = DoajClientTest.fixture("journal-page-ojs.html").replace("https://www.mapress.com", journal(w));
    stubFor(get(urlPathEqualTo("/zt")).willReturn(aResponse().withStatus(301).withHeader("Location", "/zt/index")));
    stubFor(get(urlPathEqualTo("/zt/index")).willReturn(ok(page).withHeader("Content-Type",
        "text/html; charset=utf-8")));
    SourceRegistration r = identify(w, register("URL", journal(w) + "/zt"));
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    Source s = sources.get(r.sourceId());
    assertThat(s.issns()).containsExactlyInAnyOrder("1175-5326", "1175-5334");
    assertThat(routes.forSource(s.id())).singleElement().satisfies(route -> {
      assertThat(route.layer()).isEqualTo("OAI");
      JsonNode cfg = FinderJson.mapper().readTree(route.config());
      assertThat(cfg.get("base").asString()).isEqualTo(journal(w) + "/zt/oai");
      assertThat(cfg.get("prefix").asString()).isEqualTo("oai_dc");
      assertThat(cfg.get("issns").toString()).isEqualTo("[\"1175-5326\",\"1175-5334\"]");
      assertThat(route.status()).isEqualTo("PENDING");
    });
    assertThat(tasks()).noneMatch(t -> "VERIFY_ROUTE".equals(t.get("type")));

    // without the feed links: the path up to /index.php/<journal>
    String bare = page.replaceAll("(?m)^<link rel=\"alternate\".*$", "");
    stubFor(get(urlPathEqualTo("/ojs/index.php/bdj/issue/current")).willReturn(ok(bare.replace("1175-5326",
        "1313-2989").replace("1175-5334", "1313-2970")).withHeader("Content-Type", "text/html")));
    SourceRegistration r2 = identify(w, register("URL", journal(w) + "/ojs/index.php/bdj/issue/current"));
    assertThat(routes.forSource(r2.sourceId())).singleElement().satisfies(route -> assertThat(
        FinderJson.mapper().readTree(route.config()).get("base").asString())
        .isEqualTo(journal(w) + "/ojs/index.php/bdj/oai"));
  }

  @Test
  void pageWithoutIssnOrNotHtmlIsNotIdentified(WireMockRuntimeInfo w) {
    long before = sourceCount();
    stubFor(get(urlPathEqualTo("/plain")).willReturn(ok("<html><body>A journal, pp. 1175-5326</body></html>")
        .withHeader("Content-Type", "text/html")));
    stubFor(get(urlPathEqualTo("/pdf")).willReturn(ok("ISSN 1175-5326").withHeader("Content-Type",
        "application/pdf")));
    stubFor(get(urlPathEqualTo("/gone")).willReturn(notFound()));
    // only the first 2 MB are read: an ISSN after them is not seen
    stubFor(get(urlPathEqualTo("/big")).willReturn(ok("<html><body>" + "x".repeat(JournalPage.MAX_BYTES)
        + " ISSN 1175-5326</body></html>").withHeader("Content-Type", "text/html")));
    assertThat(identify(w, register("URL", journal(w) + "/plain")).reason()).isEqualTo("NO_ISSN_FOUND");
    assertThat(identify(w, register("URL", journal(w) + "/pdf")).reason()).isEqualTo("NO_ISSN_FOUND");
    assertThat(identify(w, register("URL", journal(w) + "/gone")).reason()).isEqualTo("PAGE_UNREACHABLE");
    assertThat(identify(w, register("URL", journal(w) + "/big")).reason()).isEqualTo("NO_ISSN_FOUND");
    assertThat(sourceCount()).isEqualTo(before);
  }

  @Test
  void unknownDoiNotIdentified(WireMockRuntimeInfo w) {
    long before = sourceCount();
    stubFor(get(urlPathMatching("/crossref/works/.*")).willReturn(notFound()));
    stubFor(get(urlPathMatching("/openalex/works/.*")).willReturn(notFound()
        .withHeader("Content-Type", "application/json").withBody("{\"error\":\"not found\"}")));
    SourceRegistration r = identify(w, register("DOI", "10.9999/nothing.here"));
    assertThat(r.state()).isEqualTo("NOT_IDENTIFIED");
    assertThat(r.reason()).isEqualTo("DOI_UNKNOWN");
    assertThat(sourceCount()).isEqualTo(before);
  }

  @Test
  void crossrefOutageDefers(WireMockRuntimeInfo w) {
    stubFor(get(urlPathMatching("/crossref/works/.*")).willReturn(serviceUnavailable()));
    long id = register("DOI", DOI);
    assertThatThrownBy(() -> handler(w).handle(task(id))).isInstanceOf(ServiceOutageException.class);
    assertThat(registrations.get(id).state()).isEqualTo("IDENTIFYING");
  }

  @Test
  void secondDeliveryIsNoOp(WireMockRuntimeInfo w) {
    long id = register("ISSN", "2118-9773");
    SourceRegistration first = identify(w, id);
    List<Map<String, Object>> tasksBefore = tasks();
    SourceRegistration second = identify(w, id);
    assertThat(second).isEqualTo(first);
    assertThat(tasks()).isEqualTo(tasksBefore);
    assertThat(signals.forSource(first.sourceId())).hasSize(1);
    // a withdrawn registration is not identified either
    long withdrawn = register("ISSN", "1175-5326");
    jdbc.update("UPDATE source_registration SET state = 'WITHDRAWN' WHERE id = ?", withdrawn);
    assertThat(identify(w, withdrawn).state()).isEqualTo("WITHDRAWN");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE '1175-5326' = ANY(issns)", Long.class)).isZero();
  }

  @Test
  void pageListingOtherJournalsChangesNoIdentity(WireMockRuntimeInfo w) {
    long core = existing("CORE", "ACTIVE", "1175-5326");
    long candidate = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('Other', 'DOAJ', 'CANDIDATE', "
        + "'{2118-9773}') RETURNING id", Long.class);
    long before = sourceCount();
    stubFor(get(urlPathEqualTo("/portal")).willReturn(ok("<html><head>"
        + "<meta name=\"citation_issn\" content=\"2118-9773\"><meta name=\"citation_issn\" content=\"1175-5326\">"
        + "<meta name=\"citation_issn\" content=\"1313-2989\"></head></html>").withHeader("Content-Type", "text/html")));
    long id = register("URL", journal(w) + "/portal");
    SourceRegistration r = identify(w, id);
    assertThat(r.state()).isEqualTo("IDENTIFIED");
    assertThat(r.outcome()).isEqualTo("EXISTING");
    assertThat(r.sourceId()).isEqualTo(core); // the best match: the journal Garrulus follows
    assertThat(r.tierAtIdentification()).isEqualTo("CORE");
    // only the signal: nothing widened, nothing folded, nothing created
    assertThat(sources.get(core).issns()).containsExactly("1175-5326");
    assertThat(sources.get(candidate).issns()).containsExactly("2118-9773");
    assertThat(sourceCount()).isEqualTo(before);
    assertThat(signals.forSource(core)).extracting(SourceSignal::signalKey).containsExactly(String.valueOf(id));
    assertThat(signals.forSource(candidate)).isEmpty();
    assertThat(tasks()).isEmpty();

    // a page naming a followed journal and an unknown ISSN does not widen the followed journal either
    stubFor(get(urlPathEqualTo("/core-plus")).willReturn(ok("<html><head>"
        + "<meta name=\"citation_issn\" content=\"1175-5326\"><meta name=\"citation_issn\" content=\"1005-0094\">"
        + "</head></html>").withHeader("Content-Type", "text/html")));
    SourceRegistration r2 = identify(w, register("URL", journal(w) + "/core-plus"));
    assertThat(r2.sourceId()).isEqualTo(core);
    assertThat(sources.get(core).issns()).containsExactly("1175-5326");
    assertThat(sourceCount()).isEqualTo(before);
  }

  @Test
  void lookupFailureEndsNotIdentified(WireMockRuntimeInfo w) {
    stubFor(get(urlPathMatching("/crossref/works/.*")).willReturn(aResponse().withStatus(400).withBody("bad")));
    long id = register("DOI", DOI);
    SourceRegistration r = identify(w, id);
    assertThat(r.state()).isEqualTo("NOT_IDENTIFIED");
    assertThat(r.reason()).isEqualTo("LOOKUP_FAILED");
  }
}
