package org.catalogueoflife.garrulus.indexer.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.match.MatchNamesHandler;
import org.catalogueoflife.garrulus.indexer.match.PlacedWork;
import org.catalogueoflife.garrulus.indexer.match.WorkIndexing;
import org.catalogueoflife.garrulus.indexer.match.WorkPlacer;
import org.catalogueoflife.garrulus.indexer.match.XrReleaseService;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(LinkTestConfig.class)
// the built-in lookups (Task 4) are off: these tests drive the runner with scripted lookups only
@org.springframework.test.context.TestPropertySource(properties = {
    "garrulus.links.crossref.enabled=false",
    "garrulus.links.openalex.enabled=false",
    "garrulus.links.europepmc.enabled=false",
    "garrulus.links.biodiversitypmc.enabled=false",
    "garrulus.links.zenodo-blr.enabled=false",
    "garrulus.links.plazi.enabled=false",
    "garrulus.links.wikidata.enabled=false",
    "garrulus.links.ipni.enabled=false",
    "garrulus.links.crossref-journal.enabled=false"})
class LinkWorkHandlerIT extends AbstractPostgresIT {

  @Autowired LinkWorkHandler handler;
  @Autowired LinkScheduler scheduler;
  @Autowired ScriptedLookup workLookup;
  @Autowired ScriptedLookup laterLookup;
  @Autowired org.springframework.core.env.ConfigurableEnvironment env;
  @Autowired TaskQueue queue;
  @Autowired IndexMapper index;
  @Autowired HarvestOutage outage;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM link_attempt");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM job_cursor");
    workLookup.reset();
    laterLookup.reset();
  }

  private long work(String doi, String state) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level) VALUES (?, 't', "
        + "'cc-by', 'LIVE', ?, 'ABSTRACT_ONLY') RETURNING id", Long.class, doi, state);
  }

  private long indexed() {
    return work("10.7777/link" + System.nanoTime(), "INDEXED");
  }

  private static Task task(String payload) {
    return new Task(1, LinkWorkHandler.TYPE, payload, TaskPriority.BACKFILL, TaskState.RUNNING, 1, null, null, null,
        null, null, null);
  }

  private Map<String, Object> attempt(long work) {
    return jdbc.queryForMap("SELECT attempts, found, next_try, last_tried FROM link_attempt WHERE entity = 'W' "
        + "AND subject_id = ? AND lookup = 'test'", work);
  }

  private void makeDue(long work) {
    jdbc.update("UPDATE link_attempt SET next_try = now() - interval '1 hour' WHERE entity = 'W' AND subject_id = ?",
        work);
  }

  private static Duration until(Object nextTry) {
    return Duration.between(Instant.now(), ((java.sql.Timestamp) nextTry).toInstant());
  }

  @Test
  void newSchemeAndLookupNeedNoMigration() {
    long w = indexed();
    long other = indexed();
    workLookup.answers.put(w, List.of(new Identifier("TESTDB", "42")));
    int migrations = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class);

    handler.handle(task("{\"afterId\":0,\"schemes\":[\"TESTDB\"]}"));

    assertThat(jdbc.queryForList("SELECT scheme || '/' || value || '/' || first_layer FROM work_identifier "
        + "WHERE scheme = 'TESTDB'", String.class)).containsExactly("TESTDB/42/LINK:test");
    assertThat(jdbc.queryForObject("SELECT work_id FROM work_identifier WHERE scheme = 'TESTDB'", Long.class))
        .isEqualTo(w);
    assertThat(attempt(w)).containsEntry("found", true).containsEntry("next_try", null);
    assertThat(attempt(other)).containsEntry("found", false);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class)).isEqualTo(migrations);
  }

  @Test
  void schemesFilterSkipsLookupsThatProduceOtherSchemes() {
    long w = indexed();
    workLookup.answers.put(w, List.of(new Identifier("TESTDB", "42")));
    handler.handle(task("{\"afterId\":0,\"schemes\":[\"BHL_PART\"]}"));
    assertThat(workLookup.calls()).isZero();
  }

  @Test
  void missRetriesAfter30Then180ThenNever() {
    long w = indexed();
    String payload = "{\"workId\":" + w + "}";

    handler.handle(task(payload));
    assertThat(workLookup.calls()).isEqualTo(1);
    assertThat(attempt(w)).containsEntry("attempts", 1).containsEntry("found", false);
    assertThat(until(attempt(w).get("next_try"))).isBetween(Duration.ofDays(29), Duration.ofDays(31));

    handler.handle(task(payload)); // not due: nothing is asked
    assertThat(workLookup.calls()).isEqualTo(1);

    makeDue(w);
    handler.handle(task(payload));
    assertThat(workLookup.calls()).isEqualTo(2);
    assertThat(attempt(w)).containsEntry("attempts", 2);
    assertThat(until(attempt(w).get("next_try"))).isBetween(Duration.ofDays(179), Duration.ofDays(181));

    makeDue(w);
    handler.handle(task(payload));
    assertThat(workLookup.calls()).isEqualTo(3);
    assertThat(attempt(w)).containsEntry("attempts", 3).containsEntry("found", false)
        .containsEntry("next_try", null);

    handler.handle(task(payload)); // a third miss is final
    assertThat(workLookup.calls()).isEqualTo(3);
  }

  @Test
  void foundIsNotQueriedAgain() {
    long w = indexed();
    workLookup.answers.put(w, List.of(new Identifier("TESTDB", "7")));
    handler.handle(task("{\"workId\":" + w + "}"));
    handler.handle(task("{\"workId\":" + w + "}"));
    assertThat(workLookup.calls()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_identifier WHERE scheme = 'TESTDB'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void aWorkThatIsNotListedIsSkipped() {
    long w = work("10.7777/link-resolved", "CONTENT_RESOLVED");
    handler.handle(task("{\"workId\":" + w + "}"));
    assertThat(workLookup.calls()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt", Long.class)).isZero();
  }

  @Autowired LinkGate gate;
  @Autowired org.springframework.beans.factory.ObjectProvider<IdentifierLookup> allLookups;

  /** MATCH_NAMES of the work with a placement that always indexes; the work is made ready for its first indexing. */
  private void match(long w, LinkGate links) {
    jdbc.update("UPDATE work SET state = 'NAMES_FOUND', first_indexed_at = NULL WHERE id = ?", w);
    matchAgain(w, links);
  }

  private void matchAgain(long w, LinkGate links) {
    XrReleaseService xr = mock(XrReleaseService.class);
    when(xr.currentVerified()).thenReturn(1);
    WorkPlacer placer = mock(WorkPlacer.class);
    when(placer.place(w, 1)).thenReturn(mock(PlacedWork.class));
    WorkIndexing indexing = (id, placed, key) -> {
      index.setIndexed(id, new String[0], new String[0], false, key);
      return WorkIndexing.Outcome.INDEXED;
    };
    MatchNamesHandler match = new MatchNamesHandler(xr, placer, indexing, outage, queue, index, links);
    Task t = new Task(1, MatchNamesHandler.TYPE, "{\"workId\":" + w + "}", TaskPriority.LIVE, TaskState.RUNNING, 1,
        null, null, null, null, null, null);
    try {
      match.handle(t);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void reindexDoesNotEnqueue() {
    long w = indexed();
    match(w, gate);
    assertThat(linkTasks()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'LINK_WORK'", Integer.class))
        .isEqualTo(TaskPriority.BACKFILL.code());
    jdbc.update("UPDATE task SET state = 'DONE' WHERE type = 'LINK_WORK'"); // the key no longer holds a second one off
    matchAgain(w, gate); // the work is indexed again (a reprocess)
    assertThat(linkTasks()).isEqualTo(1);
  }

  @Test
  void aBackfilledWorkIsNotLinkedOneByOne() {
    long w = indexed();
    jdbc.update("UPDATE work SET origin = 'BACKFILL' WHERE id = ?", w);
    match(w, gate);
    assertThat(linkTasks()).isZero(); // the paged LINK_WORK {"afterId":0} after the backfill links it
    handler.handle(task("{\"afterId\":0}"));
    assertThat(workLookup.asked).anySatisfy(batch -> assertThat(batch).extracting(LinkTarget::id).contains(w));
  }

  @Test
  void noPerWorkLinkTaskWhileLinksAreOff() {
    long w = indexed();
    match(w, new LinkGate(new LinkProperties(env, false), allLookups));
    assertThat(linkTasks()).isZero();

    long v = indexed();
    workLookup.enabled = false; // the master switch is on, but no work lookup is
    env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("bhl-off",
        Map.of("garrulus.links.bhl-doi.enabled", "false")));
    try {
      match(v, gate);
    } finally {
      env.getPropertySources().remove("bhl-off");
    }
    assertThat(linkTasks()).isZero();
  }

  private long linkTasks() {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'LINK_WORK'", Long.class);
  }

  private Map<String, Object> attempt(long work, String lookup) {
    return jdbc.queryForMap("SELECT attempts, found, next_try FROM link_attempt WHERE entity = 'W' "
        + "AND subject_id = ? AND lookup = ?", work, lookup);
  }

  @Test
  void anOutageOfTheOnlyLookupDefersTheTaskAndTheTargetsWithoutAnAttempt() {
    long w = indexed();
    workLookup.failure = () -> new HttpProblem(URI.create("https://example.org/x"), 503, true, "down", null);
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}")))
        .isInstanceOf(ServiceOutageException.class);
    assertThat(attempt(w)).containsEntry("attempts", 0).containsEntry("found", false);
    assertThat(until(attempt(w).get("next_try"))).isBetween(Duration.ofMinutes(25), Duration.ofMinutes(35));

    jdbc.update("DELETE FROM link_attempt");
    Instant capReset = Instant.now().plusSeconds(3600);
    workLookup.failure = () -> new BudgetExhaustedException(capReset);
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}")))
        .isInstanceOf(BudgetExhaustedException.class);
    assertThat(attempt(w)).containsEntry("attempts", 0);
    assertThat(((java.sql.Timestamp) attempt(w).get("next_try")).toInstant()).isCloseTo(capReset,
        org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));

    jdbc.update("DELETE FROM link_attempt");
    workLookup.failure = () -> new HttpProblem(URI.create("https://example.org/x"), 400, false, "bad", null);
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}"))).isInstanceOf(HttpProblem.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt", Long.class)).isZero();
  }

  @Test
  void aDeferredTargetCountsItsFirstRealTryAsTheFirstAttempt() {
    long w = indexed();
    workLookup.failure = () -> new HttpProblem(URI.create("https://example.org/x"), 503, true, "down", null);
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}")))
        .isInstanceOf(ServiceOutageException.class);
    handler.handle(task("{\"workId\":" + w + "}")); // not due before the outage's retry time
    assertThat(workLookup.calls()).isEqualTo(1);

    workLookup.failure = null;
    makeDue(w);
    handler.handle(task("{\"workId\":" + w + "}"));
    assertThat(attempt(w)).containsEntry("attempts", 1).containsEntry("found", false);
    assertThat(until(attempt(w).get("next_try"))).isBetween(Duration.ofDays(29), Duration.ofDays(31));
  }

  @Test
  void aCapReachedMidPageDefersTheRestOfThatLookupAndTheNextLookupAndPageStillRun() {
    long w1 = indexed();
    long w2 = indexed();
    long w3 = indexed();
    long w4 = indexed();
    laterLookup.enabled = true;
    laterLookup.answers.put(w3, List.of(new Identifier("TESTDB", "33")));
    Instant capReset = Instant.now().plus(Duration.ofHours(5));
    java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
    workLookup.failure = () -> n.incrementAndGet() == 1 ? null : new BudgetExhaustedException(capReset);

    handler.handle(task("{\"afterId\":0,\"batch\":3}"));

    assertThat(workLookup.calls()).isEqualTo(2); // the call that met the cap; w3 is not asked after it
    assertThat(attempt(w1)).containsEntry("attempts", 1).containsEntry("found", false);
    for (long w : List.of(w2, w3)) {
      assertThat(attempt(w)).containsEntry("attempts", 0).containsEntry("found", false);
      assertThat(((java.sql.Timestamp) attempt(w).get("next_try")).toInstant()).isCloseTo(capReset,
          org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    }
    assertThat(laterLookup.calls()).isEqualTo(3);
    assertThat(attempt(w3, "test-later")).containsEntry("attempts", 1).containsEntry("found", true);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_identifier WHERE work_id = ? AND scheme = 'TESTDB'",
        Long.class, w3)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'LINK_WORK'", String.class))
        .containsPattern("\"afterId\": ?" + w3 + "\\b");
    assertThat(w4).isGreaterThan(w3);
  }

  @Test
  void anOutageOfALaterLookupKeepsWhatTheEarlierOneFoundAndTheTaskEnds() {
    long w = indexed();
    workLookup.answers.put(w, List.of(new Identifier("TESTDB", "8")));
    laterLookup.enabled = true;
    laterLookup.applies = true;
    laterLookup.failure = () -> new HttpProblem(URI.create("https://example.org/x"), 503, true, "down", null);
    jdbc.update("DELETE FROM work_identifier");

    handler.handle(task("{\"workId\":" + w + "}")); // no deferral: the first lookup did its part

    assertThat(attempt(w, "test")).containsEntry("found", true);
    assertThat(attempt(w, "test-later")).containsEntry("attempts", 0);
    assertThat(until(attempt(w, "test-later").get("next_try"))).isBetween(Duration.ofMinutes(25),
        Duration.ofMinutes(35));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_identifier WHERE work_id = ?", Long.class, w))
        .isEqualTo(1);
  }

  @Test
  void aRefusedBiodiversityPmcReservationLeavesItsTargetsDueAtTheCapResetAndCountsNothing() {
    long w = indexed();
    jdbc.update("UPDATE work SET has_acts = true WHERE id = ?", w);
    jdbc.update("INSERT INTO work_identifier (work_id, scheme, value, first_layer) VALUES (?, 'PMCID', 'PMC1', 'X')", w);
    java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
    jdbc.update("DELETE FROM api_usage WHERE service = 'biodiversitypmc'");
    jdbc.update("INSERT INTO api_usage (service, day, calls) VALUES ('biodiversitypmc', ?, 200)", today);
    var on = new org.springframework.core.env.MapPropertySource("bpmc-on",
        Map.of("garrulus.links.biodiversitypmc.enabled", "true"));
    env.getPropertySources().addFirst(on);
    try {
      handler.handle(task("{\"workId\":" + w + "}")); // the scripted lookup still runs: no deferral
    } finally {
      env.getPropertySources().remove("bpmc-on");
      jdbc.update("DELETE FROM api_usage WHERE service = 'biodiversitypmc'");
    }
    Map<String, Object> bpmc = attempt(w, "biodiversitypmc");
    assertThat(bpmc).containsEntry("attempts", 0).containsEntry("found", false);
    assertThat(((java.sql.Timestamp) bpmc.get("next_try")).toInstant())
        .isEqualTo(today.plusDays(1).atTime(0, 5).toInstant(java.time.ZoneOffset.UTC));
    assertThat(workLookup.calls()).isEqualTo(1);
  }

  @Test
  void idOfAnotherWorkEnqueuesMerge() {
    long a = indexed();
    long b = indexed();
    jdbc.update("INSERT INTO work_identifier (work_id, scheme, value, first_layer) VALUES (?, 'TESTDB', '7', 'X')", a);
    workLookup.answers.put(b, List.of(new Identifier("TESTDB", "7")));

    handler.handle(task("{\"workId\":" + b + "}"));

    assertThat(jdbc.queryForList("SELECT payload FROM task WHERE type = 'MERGE_WORKS'", String.class))
        .singleElement().asString().containsPattern("\"survivor\": ?" + Math.min(a, b) + "\\b").containsPattern("\"other\": ?" + Math.max(a, b) + "\\b");
    assertThat(jdbc.queryForObject("SELECT work_id FROM work_identifier WHERE scheme = 'TESTDB'", Long.class))
        .isEqualTo(a); // the identifier stays where it was until the merge
  }

  @Test
  void bhlPartFromBhlDoi() {
    long w = work("10.5962/bhl.part.4711", "INDEXED");
    handler.handle(task("{\"workId\":" + w + "}"));
    assertThat(jdbc.queryForList("SELECT scheme || '/' || value || '/' || first_layer FROM work_identifier "
        + "WHERE work_id = ? AND scheme = 'BHL_PART'", String.class, w)).containsExactly("BHL_PART/4711/LINK:bhl-doi");
    assertThat(jdbc.queryForObject("SELECT found FROM link_attempt WHERE subject_id = ? AND lookup = 'bhl-doi'",
        Boolean.class, w)).isTrue();
  }

  @Test
  void switchedOffLookupIsNotAsked() {
    long w = indexed();
    workLookup.enabled = false;
    handler.handle(task("{\"workId\":" + w + "}"));
    assertThat(workLookup.calls()).isZero();
  }

  @Test
  void batchContinues() {
    long w1 = indexed();
    long w2 = indexed();
    long w3 = indexed();
    long w4 = indexed();
    long w5 = indexed();
    workLookup.batchSize = 2;
    Instant later = Instant.now().plusSeconds(600);
    Task first = new Task(1, LinkWorkHandler.TYPE, "{\"afterId\":0,\"batch\":2,\"schemes\":null}", TaskPriority.REPROCESS,
        TaskState.RUNNING, 1, later, null, null, null, null, null);

    handler.handle(first);

    assertThat(workLookup.calls()).isEqualTo(2);
    assertThat(workLookup.asked).hasSize(1); // chunks of the lookup's batch size
    Map<String, Object> next = jdbc.queryForMap("SELECT payload, priority, dedup_key, run_after FROM task "
        + "WHERE type = 'LINK_WORK'");
    assertThat(next.get("payload").toString()).containsPattern("\"afterId\": ?" + w2 + "\\b").containsPattern("\"batch\": ?2\\b");
    assertThat(next.get("priority")).isEqualTo(TaskPriority.REPROCESS.code());
    assertThat((String) next.get("dedup_key")).startsWith("link-works:").hasSize("link-works:".length() + 12);
    assertThat(((java.sql.Timestamp) next.get("run_after")).toInstant()).isBetween(later.minusSeconds(1),
        later.plusSeconds(1));

    jdbc.update("DELETE FROM task");
    handler.handle(task("{\"afterId\":" + w2 + ",\"batch\":2}"));
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'LINK_WORK'", String.class))
        .containsPattern("\"afterId\": ?" + w4 + "\\b");
    jdbc.update("DELETE FROM task");
    handler.handle(task("{\"afterId\":" + w4 + ",\"batch\":2}")); // one work left: the last page
    assertThat(linkTasks()).isZero();
    assertThat(workLookup.calls()).isEqualTo(5);
    assertThat(w5).isGreaterThan(w1);
  }

  @Test
  void retrySweepEnqueuesDueIdsAndDeletesOrphans() {
    long w1 = indexed();
    long w2 = indexed();
    long gone = indexed();
    handler.handle(task("{\"afterId\":0}")); // a miss for all three
    jdbc.update("DELETE FROM work WHERE id = ?", gone);
    makeDue(w1);
    makeDue(gone);

    int queued = scheduler.sweep(org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK, LinkWorkHandler.TYPE);

    assertThat(queued).isEqualTo(1); // the orphan row is deleted before the due ones are read
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt WHERE subject_id = ?", Long.class, gone)).isZero();
    Map<String, Object> t = jdbc.queryForMap("SELECT payload, dedup_key FROM task WHERE type = 'LINK_WORK'");
    assertThat(t.get("payload").toString().replace(" ", "")).isEqualTo("{\"ids\":[" + w1 + "]}");
    assertThat((String) t.get("dedup_key")).startsWith("link-due:W:");

    jdbc.update("DELETE FROM task");
    long before = workLookup.calls();
    handler.handle(task("{\"ids\":[" + w1 + "," + w2 + "]}")); // w1 is due again, w2 not
    assertThat(workLookup.calls()).isEqualTo(before + 1);
    assertThat(attempt(w1)).containsEntry("attempts", 2);
  }

  private Object nextTry(long work) {
    return attempt(work).get("next_try");
  }

  private int sweep() {
    return scheduler.sweep(org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK, LinkWorkHandler.TYPE);
  }

  private List<String> untriedPayloads() {
    return jdbc.queryForList("SELECT payload FROM task WHERE type = 'LINK_WORK' AND dedup_key LIKE 'link-untried:%' "
        + "ORDER BY id", String.class).stream().map(p -> p.replace(" ", "")).toList();
  }

  private static String ids(long... ids) {
    return "{\"ids\":[" + java.util.Arrays.stream(ids).mapToObj(String::valueOf)
        .collect(java.util.stream.Collectors.joining(",")) + "]}";
  }

  @Test
  void theSweepLinksListedWorksThatWereNeverTried() {
    long tried = indexed();
    handler.handle(task("{\"workId\":" + tried + "}"));
    long routeBackfill = indexed(); // e.g. a route backfill's work: origin BACKFILL, so no LINK_WORK of its own
    jdbc.update("UPDATE work SET origin = 'BACKFILL' WHERE id = ?", routeBackfill);
    long unlisted = work("10.7777/unlisted", "CONTENT_RESOLVED");
    var entity = org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK;

    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 5000, 100)).isEqualTo(1);

    assertThat(untriedPayloads()).containsExactly(ids(routeBackfill));
    handler.handle(task(ids(routeBackfill)));
    assertThat(attempt(routeBackfill)).containsEntry("attempts", 1);
    assertThat(unlisted).isPositive();
  }

  @Test
  void aWorkNoLookupAppliesToIsMarkedSoTheSweepDoesNotQueueItAgain() {
    long w = indexed();
    workLookup.applies = false;
    handler.handle(task(ids(w)));
    assertThat(jdbc.queryForList("SELECT lookup FROM link_attempt WHERE subject_id = ?", String.class, w))
        .containsExactly(LinkRunner.NONE_APPLIED);
    var entity = org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK;
    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 5000, 100)).isZero();
  }

  @Test
  void noNeverTriedWorkIsQueuedWhileNoWorkLookupIsOn() {
    indexed();
    workLookup.enabled = false;
    var entity = org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK;
    env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("bhl-off",
        Map.of("garrulus.links.bhl-doi.enabled", "false")));
    try {
      assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 5000, 100)).isZero();
    } finally {
      env.getPropertySources().remove("bhl-off");
    }
    assertThat(untriedPayloads()).isEmpty();
    scheduler.retries(); // the scheduled entry point: off without the harvest switch (off in the tests)
    assertThat(untriedPayloads()).isEmpty();
  }

  @Test
  void theNeverTriedCursorAdvancesAcrossRunsAndWrapsAtTheEnd() {
    long[] ws = new long[5];
    for (int i = 0; i < 5; i++) ws[i] = indexed();
    var entity = org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK;

    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 2, 3)).isEqualTo(3); // the day's budget
    assertThat(untriedPayloads()).containsExactly(ids(ws[0], ws[1]), ids(ws[2]));
    assertThat(jdbc.queryForObject("SELECT position FROM job_cursor WHERE name = 'link-untried:W'", Long.class))
        .isEqualTo(ws[2]);

    jdbc.update("DELETE FROM task");
    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 2, 3)).isEqualTo(2); // the rest, then the end
    assertThat(untriedPayloads()).containsExactly(ids(ws[3], ws[4]));
    assertThat(jdbc.queryForObject("SELECT position FROM job_cursor WHERE name = 'link-untried:W'", Long.class))
        .isZero(); // wrapped: the next run starts over and finds what was indexed late below the old position

    jdbc.update("DELETE FROM task");
    handler.handle(task(ids(ws)));
    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 2, 3)).isZero(); // all tried: nothing, stays at 0
    assertThat(jdbc.queryForObject("SELECT position FROM job_cursor WHERE name = 'link-untried:W'", Long.class))
        .isZero();
  }

  @Test
  void theSweepPagesTheDueSubjectsUpToTheDailyLimit() {
    List<Long> ws = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) ws.add(indexed());
    handler.handle(task("{\"afterId\":0}"));
    ws.forEach(this::makeDue);
    var entity = org.catalogueoflife.garrulus.store.ident.IdScheme.Entity.WORK;

    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 2, 5)).isEqualTo(5);
    List<Map<String, Object>> tasks = jdbc.queryForList("SELECT payload, dedup_key FROM task WHERE type = 'LINK_WORK' "
        + "ORDER BY id");
    assertThat(tasks).extracting(t -> t.get("payload").toString().replace(" ", "")).containsExactly(
        "{\"ids\":[" + ws.get(0) + "," + ws.get(1) + "]}", "{\"ids\":[" + ws.get(2) + "," + ws.get(3) + "]}",
        "{\"ids\":[" + ws.get(4) + "]}");
    assertThat(tasks).extracting(t -> (String) t.get("dedup_key")).doesNotHaveDuplicates()
        .allMatch(k -> k.startsWith("link-due:W:"));

    jdbc.update("DELETE FROM task");
    assertThat(scheduler.sweep(entity, LinkWorkHandler.TYPE, 2, 3)).isEqualTo(3); // the daily limit
    assertThat(jdbc.queryForList("SELECT payload FROM task WHERE type = 'LINK_WORK' ORDER BY id", String.class))
        .extracting(p -> p.replace(" ", "")).containsExactly("{\"ids\":[" + ws.get(0) + "," + ws.get(1) + "]}",
            "{\"ids\":[" + ws.get(2) + "]}");
  }

  @Test
  void dueRowOfALookupThatNoLongerAppliesIsClearedAndNotSelected() {
    long w = indexed();
    handler.handle(task("{\"workId\":" + w + "}"));
    makeDue(w);
    workLookup.applies = false; // e.g. the id arrived from another layer meanwhile

    handler.handle(task("{\"workId\":" + w + "}"));

    assertThat(workLookup.calls()).isEqualTo(1);
    assertThat(nextTry(w)).isNull();
    assertThat(attempt(w)).containsEntry("attempts", 1);
    assertThat(sweep()).isZero();
  }

  @Test
  void aSchemesFilterLeavesDueRowsOfOtherLookupsAlone() {
    long w = indexed();
    handler.handle(task("{\"workId\":" + w + "}"));
    makeDue(w);
    workLookup.applies = false;

    handler.handle(task("{\"ids\":[" + w + "],\"schemes\":[\"BHL_PART\"]}"));

    assertThat(nextTry(w)).isNotNull();
  }

  @Test
  void dueRowOfADisabledLookupIsNotSelected() {
    long w = indexed();
    handler.handle(task("{\"workId\":" + w + "}"));
    makeDue(w);
    workLookup.enabled = false;

    assertThat(sweep()).isZero();
    assertThat(nextTry(w)).isNotNull(); // kept for the day the lookup is switched on again
    workLookup.enabled = true;
    assertThat(sweep()).isEqualTo(1);
  }

  @Test
  void dueRowOfAWorkThatIsNoLongerListedIsNotSelectedAndCleared() {
    long w = indexed();
    handler.handle(task("{\"workId\":" + w + "}"));
    makeDue(w);
    jdbc.update("UPDATE work SET state = 'CONTENT_RESOLVED' WHERE id = ?", w);

    assertThat(sweep()).isZero();
    handler.handle(task("{\"ids\":[" + w + "]}"));
    assertThat(nextTry(w)).isNull();
  }
}
