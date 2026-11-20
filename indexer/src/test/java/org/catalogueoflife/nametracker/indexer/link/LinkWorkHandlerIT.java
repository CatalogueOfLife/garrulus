package org.catalogueoflife.nametracker.indexer.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.match.MatchNamesHandler;
import org.catalogueoflife.nametracker.indexer.match.PlacedWork;
import org.catalogueoflife.nametracker.indexer.match.WorkIndexing;
import org.catalogueoflife.nametracker.indexer.match.WorkPlacer;
import org.catalogueoflife.nametracker.indexer.match.XrReleaseService;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(LinkTestConfig.class)
// the built-in lookups (Task 4) are off: these tests drive the runner with scripted lookups only
@org.springframework.test.context.TestPropertySource(properties = {
    "nametracker.links.crossref.enabled=false",
    "nametracker.links.openalex.enabled=false",
    "nametracker.links.europepmc.enabled=false",
    "nametracker.links.biodiversitypmc.enabled=false",
    "nametracker.links.zenodo-blr.enabled=false",
    "nametracker.links.plazi.enabled=false",
    "nametracker.links.wikidata.enabled=false",
    "nametracker.links.ipni.enabled=false",
    "nametracker.links.crossref-journal.enabled=false"})
class LinkWorkHandlerIT extends AbstractPostgresIT {

  @Autowired LinkWorkHandler handler;
  @Autowired LinkScheduler scheduler;
  @Autowired ScriptedLookup workLookup;
  @Autowired TaskQueue queue;
  @Autowired IndexMapper index;
  @Autowired HarvestOutage outage;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM link_attempt");
    jdbc.update("DELETE FROM work");
    workLookup.reset();
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

  @Test
  void reindexDoesNotEnqueue() {
    long w = indexed();
    jdbc.update("UPDATE work SET state = 'NAMES_FOUND', first_indexed_at = NULL WHERE id = ?", w);
    XrReleaseService xr = mock(XrReleaseService.class);
    when(xr.currentVerified()).thenReturn(1);
    WorkPlacer placer = mock(WorkPlacer.class);
    when(placer.place(w, 1)).thenReturn(mock(PlacedWork.class));
    WorkIndexing indexing = (id, placed, key) -> {
      index.setIndexed(id, new String[0], new String[0], false, key);
      return WorkIndexing.Outcome.INDEXED;
    };
    MatchNamesHandler match = new MatchNamesHandler(xr, placer, indexing, outage, queue, index);
    Task t = new Task(1, MatchNamesHandler.TYPE, "{\"workId\":" + w + "}", TaskPriority.LIVE, TaskState.RUNNING, 1,
        null, null, null, null, null, null);

    try {
      match.handle(t);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertThat(linkTasks()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'LINK_WORK'", Integer.class))
        .isEqualTo(TaskPriority.BACKFILL.code());
    jdbc.update("UPDATE task SET state = 'DONE' WHERE type = 'LINK_WORK'"); // the key no longer holds a second one off
    try {
      match.handle(t); // the work is indexed again (a reprocess)
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    assertThat(linkTasks()).isEqualTo(1);
  }

  private long linkTasks() {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'LINK_WORK'", Long.class);
  }

  @Test
  void outageDefersWithoutAttempt() {
    long w = indexed();
    workLookup.failure = () -> new HttpProblem(URI.create("https://example.org/x"), 503, true, "down", null);
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}")))
        .isInstanceOf(ServiceOutageException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt", Long.class)).isZero();

    workLookup.failure = () -> new BudgetExhaustedException(Instant.now().plusSeconds(3600));
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}")))
        .isInstanceOf(BudgetExhaustedException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt", Long.class)).isZero();

    workLookup.failure = () -> new HttpProblem(URI.create("https://example.org/x"), 400, false, "bad", null);
    assertThatThrownBy(() -> handler.handle(task("{\"workId\":" + w + "}"))).isInstanceOf(HttpProblem.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt", Long.class)).isZero();
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

    int queued = scheduler.sweep(org.catalogueoflife.nametracker.store.ident.IdScheme.Entity.WORK, LinkWorkHandler.TYPE);

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
    return scheduler.sweep(org.catalogueoflife.nametracker.store.ident.IdScheme.Entity.WORK, LinkWorkHandler.TYPE);
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
