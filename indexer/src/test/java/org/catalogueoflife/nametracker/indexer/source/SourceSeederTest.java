package org.catalogueoflife.nametracker.indexer.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.catalogueoflife.nametracker.indexer.cost.FakeApiUsage;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClassifications;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import tools.jackson.databind.JsonNode;
import org.catalogueoflife.nametracker.indexer.registry.SourceIdentity;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineService;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.indexer.registry.SeedUpsert;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@WireMockTest
class SourceSeederTest {

  private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC);

  private final SourceMapper sources = mock(SourceMapper.class);
  private final TaskQueue queue = mock(TaskQueue.class);
  private final DisciplineService disciplines = mock(DisciplineService.class);
  private final SourceIdentity identity = mock(SourceIdentity.class);
  private final SourceSignalMapper signals = mock(SourceSignalMapper.class);
  private final AtomicLong ids = new AtomicLong(100);

  static String group(String meta, String... keyCount) {
    StringBuilder sb = new StringBuilder("{\"meta\":{\"groups_count\":" + keyCount.length / 2 + "},\"group_by\":[");
    for (int i = 0; i < keyCount.length; i += 2) {
      if (i > 0) sb.append(',');
      sb.append("{\"key\":\"https://openalex.org/").append(keyCount[i]).append("\",\"count\":").append(keyCount[i + 1])
          .append('}');
    }
    return sb.append("]}").toString();
  }

  private SourceSeeder seeder(WireMockRuntimeInfo w) {
    PoliteHttp http = new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO),
        PoliteHttp.userAgent(), NOON, d -> { });
    OpenAlexBudget budget = new OpenAlexBudget(new FakeApiUsage(), new BigDecimal("1.00"), new BigDecimal("0.30"), NOON);
    OpenAlexClient client = new OpenAlexClient(http, budget, null, w.getHttpBaseUrl());
    when(sources.upsertSeed(any())).thenAnswer(i -> ids.incrementAndGet());
    // the real SeedUpsert over the mocked mappers: locks and fold do nothing, the transaction just runs
    org.catalogueoflife.nametracker.indexer.registry.SourceFold fold =
        mock(org.catalogueoflife.nametracker.indexer.registry.SourceFold.class);
    when(fold.foldDuplicates(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(i -> i.getArgument(0));
    SeedUpsert upsert = new SeedUpsert(sources, identity,
        mock(org.catalogueoflife.nametracker.indexer.registry.IdentityLocks.class), fold,
        new org.springframework.transaction.support.TransactionTemplate(
            mock(org.springframework.transaction.PlatformTransactionManager.class)), NOON);
    return new SourceSeeder(client, sources, mock(org.catalogueoflife.nametracker.store.route.SourceRouteMapper.class),
        queue, SeedProperties.DEFAULTS, NOON, disciplines, upsert, signals, false);
  }

  private static void stubGroups(String taxonomy, String broad, String totals) {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.id:T14399"))
        .willReturn(okJson(taxonomy)));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.subfield.id:1105"))
        .willReturn(okJson(broad)));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_location.source.id:S"))
        .willReturn(okJson(totals)));
  }

  static String source(String id, String name, String extra) {
    return "{\"id\":\"https://openalex.org/" + id + "\",\"display_name\":\"" + name
        + "\",\"issn_l\":\"1111-222" + id.charAt(1) + "\",\"issn\":[\"1111-222" + id.charAt(1)
        + "\"],\"host_organization_name\":\"Pub\",\"homepage_url\":\"https://journal.example.org/about\",\"is_oa\":true"
        + extra + "}";
  }

  @Test
  void tiersFromShares(WireMockRuntimeInfo w) {
    stubGroups(group("t", "S1", "50"),
        group("b", "S1", "60", "S2", "80", "S3", "12", "S4", "8"),
        group("n", "S1", "100", "S2", "100", "S3", "100", "S4", "100"));
    stubFor(get(urlPathEqualTo("/sources/S1")).willReturn(okJson(source("S1", "Taxon Journal", ""))));
    stubFor(get(urlPathEqualTo("/sources/S2")).willReturn(okJson(source("S2", "Broad Journal", ""))));
    stubFor(get(urlPathEqualTo("/sources/S3")).willReturn(okJson(source("S3", "Candidate Journal", ""))));

    SourceSeeder.SeedReport report = seeder(w).seed();

    assertThat(report).isEqualTo(new SourceSeeder.SeedReport(1, 1, 1, 1, false));
    ArgumentCaptor<Source> cap = ArgumentCaptor.forClass(Source.class);
    verify(sources, org.mockito.Mockito.times(3)).upsertSeed(cap.capture());
    List<Source> out = cap.getAllValues();
    assertThat(out).extracting(Source::openalexId).containsExactly("S1", "S2", "S3");
    assertThat(out).extracting(Source::tier).containsExactly("CORE", "BROAD", "CANDIDATE");
    assertThat(out).extracting(Source::journalType).containsExactly("TAXONOMIC", "GENERAL", "GENERAL");
    assertThat(out).allSatisfy(s -> {
      assertThat(s.origin()).isEqualTo("SEED_TOPIC");
      assertThat(s.tierSetBy()).isEqualTo("SEED");
      assertThat(s.worksPerYear()).isEqualTo(33);
      assertThat(s.host()).isEqualTo("journal.example.org");
    });
    // the window starts three years before the injected clock; the totals filter uses bare ids
    com.github.tomakehurst.wiremock.client.WireMock.verify(
        getRequestedFor(urlPathEqualTo("/works")).withQueryParam("filter", containing("from_publication_date:2023-11-15"))
            .withQueryParam("filter", containing("type:article"))
            .withQueryParam("filter", containing("primary_location.source.type:journal"))
            .withQueryParam("filter", containing("primary_location.source.id:S1|S2|S3|S4")));
  }

  @Test
  void zeroTotalIsSkippedNotDividedBy(WireMockRuntimeInfo w) {
    stubGroups(group("t", "S1", "50"), group("b", "S1", "50"), group("n"));

    assertThat(seeder(w).seed()).isEqualTo(new SourceSeeder.SeedReport(0, 0, 0, 1, false));
    verify(sources, never()).upsertSeed(any());
  }

  @Test
  void oaModelAndRawClassifications(WireMockRuntimeInfo w) {
    stubGroups(group("t"), group("b", "S2", "80"), group("n", "S2", "100"));
    stubFor(get(urlPathEqualTo("/sources/S2")).willReturn(okJson(source("S2", "Diamond", ",\"is_in_doaj\":true,"
        + "\"apc_usd\":0,\"country_code\":\"BG\",\"topics\":["
        + "{\"count\":80,\"subfield\":{\"id\":\"https://openalex.org/subfields/1105\"}},"
        + "{\"count\":15,\"subfield\":{\"id\":\"https://openalex.org/subfields/1109\"}},"
        + "{\"count\":3,\"subfield\":{\"id\":\"https://openalex.org/subfields/1103\"}},"
        + "{\"count\":2,\"subfield\":{\"id\":\"https://openalex.org/subfields/1702\"}}]"))));

    seeder(w).seed();

    ArgumentCaptor<Source> cap = ArgumentCaptor.forClass(Source.class);
    verify(sources).upsertSeed(cap.capture());
    Source s = cap.getValue();
    assertThat(s.oaModel()).isEqualTo("DIAMOND");
    // the disciplines are derived by the DisciplineService from the raw rows, not by the seeder
    assertThat(s.subfields()).isEmpty();
    verify(disciplines).applyOpenAlex(eq(101L), any());
    assertThat(s.country()).isEqualTo("BG");
    assertThat(s.inDoaj()).isTrue();
    assertThat(s.issnL()).isEqualTo("1111-2222");
    assertThat(s.title()).isEqualTo("Diamond");
    assertThat(s.publisher()).isEqualTo("Pub");
  }

  @Test
  void buildStoresIssnsInTheLookupForm() {
    JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
        "{\"issn_l\":\"0002-936x\",\"issn\":[\"0002-936x\",\"13132989\",\"1313-2989\"],\"display_name\":\"J\"}");
    Source s = SourceSeeder.build(node, "S1", "CORE", "SEED_TOPIC", "SEED", null);
    assertThat(s.issnL()).isEqualTo("0002-936X");
    assertThat(s.issns()).containsExactly("0002-936X", "1313-2989");
  }

  @Test
  void seedWritesRawSubfieldsAndTopics() {
    JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree("{\"topics\":["
        + "{\"id\":\"https://openalex.org/T1\",\"display_name\":\"One\",\"count\":60,"
        + "\"subfield\":{\"id\":\"https://openalex.org/subfields/1105\",\"display_name\":\"Ecology\"}},"
        + "{\"id\":\"https://openalex.org/T2\",\"display_name\":\"Two\",\"count\":30,"
        + "\"subfield\":{\"id\":\"https://openalex.org/subfields/1109\"}},"
        + "{\"id\":\"https://openalex.org/T3\",\"display_name\":\"Three\",\"count\":10,"
        + "\"subfield\":{\"id\":\"https://openalex.org/subfields/1105\"}}]}");
    Map<String, List<RawClassification>> raw = OpenAlexClassifications.raw(node);
    assertThat(raw.get("OPENALEX_SUBFIELD")).containsExactly(
        new RawClassification("OPENALEX_SUBFIELD", "1105", "Ecology", 0.7f, 0),
        new RawClassification("OPENALEX_SUBFIELD", "1109", null, 0.3f, 1));
    assertThat(raw.get("OPENALEX_TOPIC")).extracting(RawClassification::code).containsExactly("T1", "T2", "T3");
    assertThat(raw.get("OPENALEX_TOPIC").get(0).share()).isEqualTo(0.6f);
  }

  @Test
  void unknownConfiguredTopicIsWarned(WireMockRuntimeInfo w) {
    stubGroups(group("t"), group("b"), group("n"));
    stubFor(get(urlPathEqualTo("/topics/T14399")).willReturn(okJson("{\"id\":\"T14399\"}")));
    SourceSeeder seeder = seeder(w);
    ch.qos.logback.classic.Logger log = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SourceSeeder.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app = new ch.qos.logback.core.read.ListAppender<>();
    app.start();
    log.addAppender(app);
    try {
      seeder.seed();
    } finally {
      log.detachAppender(app);
    }
    assertThat(app.list).filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
        .extracting(e -> e.getFormattedMessage())
        .contains("configured taxonomy topic T11494 is unknown to OpenAlex")
        .doesNotContain("configured taxonomy topic T14399 is unknown to OpenAlex");
  }

  @Test
  void goldWhenApcAndNullWhenNeither() {
    assertThat(SourceSeeder.oaModel(true, true, 1500.0)).isEqualTo("GOLD");
    assertThat(SourceSeeder.oaModel(true, false, 1500.0)).isEqualTo("GOLD");
    assertThat(SourceSeeder.oaModel(true, true, null)).isEqualTo("DIAMOND");
    assertThat(SourceSeeder.oaModel(true, true, 0.0)).isEqualTo("DIAMOND");
    assertThat(SourceSeeder.oaModel(true, false, null)).isNull();
    assertThat(SourceSeeder.oaModel(false, false, null)).isNull();
    assertThat(SourceSeeder.oaModel(null, null, null)).isNull();
  }

  @Test
  void existingAdminTierIsKeptAndSeedTierIsRefreshed(WireMockRuntimeInfo w) {
    stubGroups(group("t", "S1", "50", "S2", "50"), group("b", "S1", "60", "S2", "60"),
        group("n", "S1", "100", "S2", "100"));
    stubFor(get(urlPathEqualTo("/sources/S1")).willReturn(okJson(source("S1", "A", ""))));
    stubFor(get(urlPathEqualTo("/sources/S2")).willReturn(okJson(source("S2", "B", ""))));
    SourceSeeder seeder = seeder(w);
    when(sources.findByOpenalexId("S1")).thenReturn(existing(1L, "S1", "BROAD", "SEED"));
    when(sources.findByOpenalexId("S2")).thenReturn(existing(2L, "S2", "EXCLUDED", "ADMIN"));
    when(sources.upsertSeed(any())).thenReturn(1L, 2L);

    seeder.seed();

    verify(sources).setTier(1L, "CORE", true, "SEED");
    verify(sources, never()).setTier(eq(2L), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
  }

  @Test
  void seedAdoptsIssnOnlyCandidateAndSignals(WireMockRuntimeInfo w) {
    stubGroups(group("t", "S1", "50"), group("b", "S1", "60"), group("n", "S1", "100"));
    stubFor(get(urlPathEqualTo("/sources/S1")).willReturn(okJson(source("S1", "A", "").replace("1111-2221", "1313-2989"))));
    SourceSeeder seeder = seeder(w);
    Source candidate = new Source(5L, null, null, List.of("1313-2989"), "A", null, null, null, null, "CANDIDATE",
        "DOAJ", "{}", true, List.of(), null, null, List.of(), "{}", List.of(), null, null, null, null, null, null, null,
        null, null, null);
    when(identity.find(List.of("1313-2989"), null)).thenReturn(candidate);
    when(sources.get(5L)).thenReturn(candidate); // re-read under its locks

    seeder.seed();

    verify(sources).updateIdentity(5L, null, List.of("1313-2989"), null, null, null, "S1", null);
  }

  @Test
  void adoptionKeepsTheCandidatesOwnIssns(WireMockRuntimeInfo w) {
    stubGroups(group("t", "S1", "50"), group("b", "S1", "60"), group("n", "S1", "100"));
    stubFor(get(urlPathEqualTo("/sources/S1")).willReturn(okJson(source("S1", "A", "").replace("1111-2221", "1313-2989"))));
    SourceSeeder seeder = seeder(w);
    Source candidate = new Source(5L, null, null, List.of("1175-5326"), "A", null, null, null, null, "CANDIDATE",
        "DOAJ", "{}", true, List.of(), null, null, List.of(), "{}", List.of(), null, null, null, null, null, null, null,
        null, null, null);
    when(identity.find(List.of("1313-2989"), null)).thenReturn(candidate);
    when(sources.get(5L)).thenReturn(candidate); // re-read under its locks

    seeder.seed();

    ArgumentCaptor<Source> cap = ArgumentCaptor.forClass(Source.class);
    verify(sources).upsertSeed(cap.capture());
    assertThat(cap.getValue().issns()).containsExactly("1313-2989", "1175-5326"); // OpenAlex's, then the DOAJ-only one
    verify(sources).updateIdentity(eq(5L), org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.argThat(l -> l.containsAll(List.of("1313-2989", "1175-5326"))), any(), any(), any(),
        eq("S1"), any());
    verify(signals).upsert(eq(101L), eq("SEED_TOPIC"), eq(""), org.mockito.ArgumentMatchers.contains("\"totalWorks\":100"));
  }

  @Test
  void ceasedSourceIsNotReTiered(WireMockRuntimeInfo w) {
    stubGroups(group("t", "S1", "50"), group("b", "S1", "60"), group("n", "S1", "100"));
    stubFor(get(urlPathEqualTo("/sources/S1")).willReturn(okJson(source("S1", "A", ""))));
    SourceSeeder seeder = seeder(w);
    Source ceased = mock(Source.class);
    when(ceased.id()).thenReturn(1L);
    when(ceased.tier()).thenReturn("BROAD");
    when(ceased.tierSetBy()).thenReturn("SEED");
    when(ceased.active()).thenReturn(true);
    when(ceased.publicationStatus()).thenReturn("CEASED");
    when(sources.findByOpenalexId("S1")).thenReturn(ceased);
    when(sources.upsertSeed(any())).thenReturn(1L);

    seeder.seed();

    verify(sources, never()).setTier(eq(1L), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
  }

  @Test
  void onboardsEverySourceStillToOnboardOnce(WireMockRuntimeInfo w) {
    stubGroups(group("t"), group("b"), group("n"));
    SourceSeeder seeder = seeder(w);
    when(sources.listToOnboard()).thenReturn(List.of(existing(7L, "S7", "CORE", "SEED")));

    seeder.seed();

    verify(queue).enqueue("ONBOARD_SOURCE", "{\"sourceId\":7}", TaskPriority.BACKFILL, "onboard:7");
  }

  private static Source existing(long id, String oaId, String tier, String by) {
    return new Source(id, oaId, null, List.of(), "t", null, null, null, null, tier, "SEED_TOPIC", "{}", true,
        List.of(), null, null, List.of(), "{}", List.of(), null, null, null, null, null, null, null, null, null, by);
  }
}
