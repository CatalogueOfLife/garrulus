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

  /** A candidate as the origin offers it: DOAJ's ISSNs are identity evidence, the other origins' are not. */
  static Candidate cand(String origin, String key, List<String> issns, String title) {
    return new Candidate(origin, key, issns, title, "Publisher", "NZ", null, "{\"n\":1}",
        List.of(new RawClassification("DOAJ_LCC", "QL", "Zoology", null, 0)),
        new RouteHint("OAI", "{\"url\":\"https://example.org/oai\"}"), origin.equals("DOAJ"));
  }

  /** A candidate whose ISSNs are Crossref, OpenAlex or DOAJ evidence (a resolved title, a DOAJ record). */
  static Candidate evidence(String origin, String key, List<String> issns, String title) {
    return new Candidate(origin, key, issns, title, "Publisher", "NZ", null, "{\"n\":1}", List.of(), null, true);
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
    // a candidate's route waits for the admin's promotion before anything fetches it
    assertThat(tasks("VERIFY_ROUTE")).isZero();
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
    CandidateRegistry.Offer o = registry.offer(cand("BLR", "blr", List.of(PRINT, "0002-936X"), "Other title"),
        TaskPriority.BACKFILL);
    assertThat(o.created()).isFalse();
    assertThat(o.sourceId()).isEqualTo(id);
    assertThat(o.tier()).isEqualTo("CORE");
    Source s = sources.get(id);
    assertThat(s.tier()).isEqualTo("CORE");
    assertThat(s.title()).isEqualTo("Existing");
    assertThat(s.issns()).containsExactly(PRINT); // self-declared ISSNs are a signal, not identity
    assertThat(signals.forSource(id)).hasSize(1);
    assertThat(routes.forSource(id)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
    assertThat(raw.forSource(id)).isEmpty();
  }

  @Test
  void evidenceOfferWidensAnExistingSource() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of(PRINT), "Existing", null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null));
    jdbc.update("UPDATE source SET tier = 'CORE' WHERE id = ?", id);
    CandidateRegistry.Offer o = registry.offer(cand("DOAJ", "doaj", List.of(PRINT, "0002-936X"), "Other title"),
        TaskPriority.BACKFILL);
    assertThat(o.sourceId()).isEqualTo(id);
    assertThat(sources.get(id).issns()).containsExactly("0002-936X", PRINT);
    assertThat(raw.forSource(id)).extracting(RawClassification::code).containsExactly("QL");
  }

  @Test
  void beaconIssnsNeverWidenOrFoldAFollowedJournal() {
    long core = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('Zootaxa', 'MANUAL', "
        + "'CORE', ?::text[]) RETURNING id", Long.class, "{" + PRINT + "}");
    CandidateRegistry.Offer other = registry.offer(cand("BLR", "blr", List.of(ONLINE), "Another journal"),
        TaskPriority.BACKFILL);
    // one OJS row typed with the CORE journal's ISSN and another journal's (and the 0000-0000 placeholder)
    CandidateRegistry.Offer o = registry.offer(cand("PKP_BEACON", "", List.of(PRINT, ONLINE, "0000-0000"), "Mixed"),
        TaskPriority.BACKFILL);
    assertThat(o.sourceId()).isEqualTo(core);
    assertThat(sources.get(core).issns()).containsExactly(PRINT);
    assertThat(sources.get(other.sourceId())).isNotNull();
    assertThat(sources.get(other.sourceId()).issns()).containsExactly(ONLINE);
    assertThat(signals.forSource(core)).extracting(org.catalogueoflife.nametracker.store.registry.SourceSignal::origin)
        .containsExactly("PKP_BEACON");
  }

  @Test
  void registrationPageIssnsDoNotWidenACandidate() {
    CandidateRegistry.Offer cand = registry.offer(new Candidate("BLR", "", List.of(PRINT), "Candidate", null, null, null,
        "{}", List.of(), null), TaskPriority.BACKFILL);
    CandidateRegistry.Offer o = registry.offer(cand("REGISTERED", "7", List.of(PRINT, ONLINE), "Page"), TaskPriority.LIVE);
    assertThat(o.sourceId()).isEqualTo(cand.sourceId());
    assertThat(sources.get(cand.sourceId()).issns()).containsExactly(PRINT);
    assertThat(routes.forSource(cand.sourceId())).isEmpty(); // a user's page adds no route to a known journal
  }

  @Test
  void placeholderIssnAloneIsNoIdentity() {
    assertThat(registry.offer(cand("PKP_BEACON", "", List.of("0000-0000"), "Placeholder"), TaskPriority.BACKFILL))
        .isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isZero();
  }

  @Test
  void offerJoiningTwoSourcesFoldsThem() {
    CandidateRegistry.Offer p = registry.offer(cand("DOAJ", "doaj", List.of(PRINT), "Zootaxa"), TaskPriority.BACKFILL);
    CandidateRegistry.Offer e = registry.offer(cand("PKP_BEACON", "pkp", List.of(ONLINE), "Zootaxa"),
        TaskPriority.BACKFILL);
    assertThat(e.sourceId()).isNotEqualTo(p.sourceId());

    CandidateRegistry.Offer both = registry.offer(evidence("PLAZI", "plazi", List.of(PRINT, ONLINE), "Zootaxa"),
        TaskPriority.BACKFILL);

    assertThat(both.sourceId()).isEqualTo(p.sourceId());
    assertThat(sources.get(e.sourceId())).isNull();
    assertThat(sources.get(p.sourceId()).issns()).containsExactly(PRINT, ONLINE);
    assertThat(signals.forSource(p.sourceId())).extracting(org.catalogueoflife.nametracker.store.registry.SourceSignal::origin)
        .containsExactlyInAnyOrder("DOAJ", "PKP_BEACON", "PLAZI");
  }

  @Test
  void foldOntoAFollowedJournalVerifiesTheMovedRoutes() {
    long core = jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES ('Zootaxa', 'MANUAL', "
        + "'CORE', ?::text[]) RETURNING id", Long.class, "{" + PRINT + "}");
    CandidateRegistry.Offer e = registry.offer(cand("PKP_BEACON", "pkp", List.of(ONLINE), "Zootaxa"),
        TaskPriority.BACKFILL);
    long route = routes.forSource(e.sourceId()).getFirst().id();
    assertThat(tasks("VERIFY_ROUTE")).isZero(); // a candidate's route waits

    registry.offer(evidence("PLAZI", "plazi", List.of(PRINT, ONLINE), "Zootaxa"), TaskPriority.BACKFILL);

    assertThat(sources.get(e.sourceId())).isNull();
    assertThat(routes.get(route).sourceId()).isEqualTo(core);
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE'", String.class))
        .containsExactly("verify-route:" + route);
  }

  @Test
  void printAndElectronicIssnMeetThroughIssnL() {
    jdbc.update("INSERT INTO issn_link VALUES (?, ?), (?, ?)", PRINT, PRINT, ONLINE, PRINT);
    CandidateRegistry.Offer a = registry.offer(cand("DOAJ", "doaj", List.of(PRINT), "J"), TaskPriority.BACKFILL);
    CandidateRegistry.Offer b = registry.offer(cand("PKP_BEACON", "pkp", List.of(ONLINE), "J"), TaskPriority.BACKFILL);
    assertThat(b.created()).isFalse();
    assertThat(b.sourceId()).isEqualTo(a.sourceId());
    assertThat(signals.forSource(a.sourceId())).hasSize(2);
    assertThat(sources.get(a.sourceId()).issns()).containsExactly(PRINT); // the Beacon's ISSN is only a signal
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
    assertThat(tasks("VERIFY_ROUTE")).isZero();
    assertThat(tasks("IDENTIFY_SOURCE")).isEqualTo(1);
  }

  private void race(Candidate a, Candidate b) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch go = new CountDownLatch(1);
      Future<CandidateRegistry.Offer> f1 = pool.submit(() -> { go.await(); return registry.offer(a, TaskPriority.BACKFILL); });
      Future<CandidateRegistry.Offer> f2 = pool.submit(() -> { go.await(); return registry.offer(b, TaskPriority.BACKFILL); });
      go.countDown();
      assertThat(f1.get().sourceId()).isEqualTo(f2.get().sourceId());
      assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentPrintPlusElectronicAndElectronicOnlyMeet() throws Exception {
    for (int i = 0; i < 5; i++) {
      clean();
      race(cand("DOAJ", "doaj", List.of(PRINT, ONLINE), "J"), cand("PKP_BEACON", "pkp", List.of(ONLINE), "J"));
    }
  }

  @Test
  void concurrentIssnWithOpenalexIdAndOpenalexIdOnlyMeet() throws Exception {
    for (int i = 0; i < 5; i++) {
      clean();
      race(new Candidate("DOAJ", "d", List.of(PRINT), "J", null, null, "S42", "{}", List.of(), null),
          new Candidate("BLR", "b", List.of(), "J", null, null, "S42", "{}", List.of(), null));
    }
  }

  @Test
  void concurrentPrintAndElectronicLinkedByTableMeet() throws Exception {
    jdbc.update("INSERT INTO issn_link VALUES (?, ?), (?, ?)", PRINT, PRINT, ONLINE, PRINT);
    for (int i = 0; i < 5; i++) {
      jdbc.update("DELETE FROM task");
      jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
      race(cand("DOAJ", "doaj", List.of(PRINT), "J"), cand("PKP_BEACON", "pkp", List.of(ONLINE), "J"));
    }
  }

  @Test
  void candidateWithoutIdentityIsRejected() {
    assertThat(registry.offer(new Candidate("BLR", "b", List.of("1175-5327", "junk"), "J", null, null, null, "{}",
        List.of(), null), TaskPriority.BACKFILL)).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source", Long.class)).isZero();
  }
}
