package org.catalogueoflife.nametracker.indexer.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CandidateRegistryIT extends AbstractPostgresIT {

  static final String PRINT = "1175-5326";
  static final String ONLINE = "1175-5334";

  @Autowired CandidateRegistry registry;
  @Autowired SourceMapper sources;
  @Autowired SourceSignalMapper signals;
  @Autowired SourceRouteMapper routes;
  @Autowired ClassificationRawMapper raw;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("DELETE FROM source_classification_raw");
    jdbc.update("DELETE FROM issn_link");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  static Candidate cand(String origin, String key, List<String> issns, String title) {
    return new Candidate(origin, key, issns, title, "Publisher", "NZ", null, "{\"n\":1}",
        List.of(new RawClassification("DOAJ_LCC", "QL", "Zoology", null, 0)),
        new RouteHint("OAI", "{\"url\":\"https://example.org/oai\"}"));
  }

  long tasks(String type) {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = ?", Long.class, type);
  }

  @Test
  void newCandidateGetsSignalRouteAndIdentify() {
    jdbc.update("INSERT INTO issn_link VALUES (?, ?), (?, ?)", PRINT, PRINT, ONLINE, PRINT);
    CandidateRegistry.Offer o = registry.offer(cand("DOAJ", "doaj", List.of(ONLINE), "Zootaxa-like"),
        TaskPriority.BACKFILL);
    assertThat(o.created()).isTrue();
    assertThat(o.tier()).isEqualTo("CANDIDATE");
    Source s = sources.get(o.sourceId());
    assertThat(s.origin()).isEqualTo("DOAJ");
    assertThat(s.issnL()).isEqualTo(PRINT);
    assertThat(s.title()).isEqualTo("Zootaxa-like");
    assertThat(signals.forSource(s.id())).hasSize(1);
    assertThat(raw.forSource(s.id())).extracting(RawClassification::code).containsExactly("QL");
    assertThat(routes.forSource(s.id())).singleElement().satisfies(r -> {
      assertThat(r.status()).isEqualTo("PENDING");
      assertThat(r.origin()).isEqualTo("DISCOVERED");
      assertThat(r.layer()).isEqualTo("OAI");
    });
    assertThat(tasks("VERIFY_ROUTE")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'IDENTIFY_SOURCE' AND dedup_key = ?",
        Integer.class, "identify:s" + s.id())).isEqualTo(TaskPriority.BACKFILL.code());
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'IDENTIFY_SOURCE'", String.class))
        .containsPattern("\"sourceId\": ?" + s.id() + "\\b");
  }

  @Test
  void registeredOriginGetsRegisteredRouteAndLiveIdentify() {
    CandidateRegistry.Offer o = registry.offer(cand("REGISTERED", "user:1", List.of(PRINT), "Mine"), TaskPriority.LIVE);
    assertThat(routes.forSource(o.sourceId())).singleElement().satisfies(r -> assertThat(r.origin()).isEqualTo("REGISTERED"));
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'IDENTIFY_SOURCE'", Integer.class))
        .isEqualTo(TaskPriority.LIVE.code());
  }

  @Test
  void existingSourceGetsSignalOnly() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of(PRINT), "Existing", null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null));
    jdbc.update("UPDATE source SET tier = 'CORE' WHERE id = ?", id);
    jdbc.update("DELETE FROM task");
    CandidateRegistry.Offer o = registry.offer(cand("PKP_BEACON", "pkp", List.of(PRINT, "0002-936X"), "Other title"),
        TaskPriority.BACKFILL);
    assertThat(o.created()).isFalse();
    assertThat(o.sourceId()).isEqualTo(id);
    assertThat(o.tier()).isEqualTo("CORE");
    Source s = sources.get(id);
    assertThat(s.tier()).isEqualTo("CORE");
    assertThat(s.title()).isEqualTo("Existing");
    assertThat(s.issns()).containsExactly("0002-936X", PRINT);
    assertThat(signals.forSource(id)).hasSize(1);
    assertThat(routes.forSource(id)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
    assertThat(raw.forSource(id)).extracting(RawClassification::code).containsExactly("QL");
  }

  @Test
  void printAndElectronicIssnMeetThroughIssnL() {
    jdbc.update("INSERT INTO issn_link VALUES (?, ?), (?, ?)", PRINT, PRINT, ONLINE, PRINT);
    CandidateRegistry.Offer a = registry.offer(cand("DOAJ", "doaj", List.of(PRINT), "J"), TaskPriority.BACKFILL);
    CandidateRegistry.Offer b = registry.offer(cand("PKP_BEACON", "pkp", List.of(ONLINE), "J"), TaskPriority.BACKFILL);
    assertThat(b.created()).isFalse();
    assertThat(b.sourceId()).isEqualTo(a.sourceId());
    assertThat(signals.forSource(a.sourceId())).hasSize(2);
    assertThat(sources.get(a.sourceId()).issns()).containsExactly(PRINT, ONLINE);
  }

  @Test
  void matchesByOpenalexIdWhenNoIssnKnown() {
    Candidate c = new Candidate("BLR", "blr", List.of(), "No ISSN", null, null, "S123", "{}", List.of(), null);
    CandidateRegistry.Offer a = registry.offer(c, TaskPriority.BACKFILL);
    CandidateRegistry.Offer b = registry.offer(c, TaskPriority.BACKFILL);
    assertThat(a.created()).isTrue();
    assertThat(b.created()).isFalse();
    assertThat(b.sourceId()).isEqualTo(a.sourceId());
    assertThat(routes.forSource(a.sourceId())).isEmpty();
  }

  @Test
  void ceasedSourceOnlyGetsSignal() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of(PRINT), "Old", null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null));
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id = ?", id);
    jdbc.update("DELETE FROM task");
    CandidateRegistry.Offer o = registry.offer(cand("DOAJ", "doaj", List.of(PRINT, ONLINE), "Old"), TaskPriority.BACKFILL);
    assertThat(o.created()).isFalse();
    assertThat(o.publicationStatus()).isEqualTo("CEASED");
    assertThat(signals.forSource(id)).hasSize(1);
    assertThat(sources.get(id).issns()).containsExactly(PRINT);
    assertThat(routes.forSource(id)).isEmpty();
    assertThat(raw.forSource(id)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }

  @Test
  void excludedSourceOnlyGetsSignal() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of(PRINT), "Nope", null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null));
    jdbc.update("UPDATE source SET tier = 'EXCLUDED' WHERE id = ?", id);
    CandidateRegistry.Offer o = registry.offer(cand("DOAJ", "doaj", List.of(PRINT), "Nope"), TaskPriority.BACKFILL);
    assertThat(o.created()).isFalse();
    assertThat(o.tier()).isEqualTo("EXCLUDED");
    assertThat(signals.forSource(id)).hasSize(1);
    assertThat(routes.forSource(id)).isEmpty();
    assertThat(raw.forSource(id)).isEmpty();
  }

  @Test
  void concurrentOffersCreateOneSource() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Callable<CandidateRegistry.Offer> call = () -> {
        go.await();
        return registry.offer(cand("DOAJ", "doaj", List.of(PRINT), "Race"), TaskPriority.BACKFILL);
      };
      Future<CandidateRegistry.Offer> f1 = pool.submit(call);
      Future<CandidateRegistry.Offer> f2 = pool.submit(call);
      go.countDown();
      CandidateRegistry.Offer a = f1.get();
      CandidateRegistry.Offer b = f2.get();
      assertThat(a.sourceId()).isEqualTo(b.sourceId());
      assertThat(a.created() ^ b.created()).isTrue();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE ? = ANY (issns)", Long.class, PRINT)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void secondOfferIsIdempotent() {
    Candidate c = cand("DOAJ", "doaj", List.of(PRINT), "Twice");
    CandidateRegistry.Offer a = registry.offer(c, TaskPriority.BACKFILL);
    CandidateRegistry.Offer b = registry.offer(c, TaskPriority.BACKFILL);
    assertThat(b.created()).isFalse();
    assertThat(b.sourceId()).isEqualTo(a.sourceId());
    assertThat(routes.forSource(a.sourceId())).hasSize(1);
    assertThat(signals.forSource(a.sourceId())).hasSize(1);
    assertThat(tasks("VERIFY_ROUTE")).isEqualTo(1);
    assertThat(tasks("IDENTIFY_SOURCE")).isEqualTo(1);
  }
}
