package org.catalogueoflife.nametracker.indexer.layer;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.task.TaskDispatcher;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class LayerHarvestHandlerIT extends AbstractPostgresIT {

  private static final String SECOND = "tok-2";

  @Autowired LayerWriter writer;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired SourceRouteMapper routes;
  @Autowired HarvestOutage outage;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  long sourceId;
  long routeId;

  /** Two pages: two works (one skipped by the layer itself), then one recent work and one from 1998. */
  static final class FakeLayer implements HarvestLayer {
    final List<String> positions = new ArrayList<>();
    @Nullable String failOn;
    long sourceId;

    @Override
    public Layer layer() {
      return Layer.OAI;
    }

    @Override
    public LayerPage page(LayerJob job, @Nullable String position) {
      positions.add(position);
      if (failOn != null && failOn.equals(position == null ? "start" : position)) {
        throw new HttpProblem(URI.create("https://journal.example.org/oai"), 503, true, "HTTP 503", null);
      }
      LocalDate recent = LocalDate.now(ZoneOffset.UTC).minusDays(5);
      if (position == null) {
        return new LayerPage(List.of(hw("10.5555/p1a", recent), hw("10.5555/p1b", recent)), SECOND, 1, 1);
      }
      return new LayerPage(List.of(hw("10.5555/p2a", recent), hw("10.5555/p2old", LocalDate.of(1998, 3, 1))), null,
          1, 0);
    }

    private HarvestedWork hw(String doi, LocalDate published) {
      return LayerWriterIT.harvested(doi, published);
    }
  }

  static final class FakeHandler extends LayerHarvestHandler {
    final FakeLayer fake;

    FakeHandler(FakeLayer fake, LayerWriter writer, TaskQueue queue, HarvestRunMapper runs,
        SourceRouteMapper routes, HarvestOutage outage, Clock clock, MockEnvironment env) {
      super(writer, queue, runs, routes, outage, clock, env);
      this.fake = fake;
    }

    @Override
    protected HarvestLayer layer() {
      return fake;
    }
  }

  FakeLayer fake;
  FakeHandler handler;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    sourceId = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, onboarded_at) "
        + "VALUES ('1234-5679', 'Fake Journal', 'CORE', 'MANUAL', now()) RETURNING id", Long.class);
    routeId = jdbc.queryForObject("INSERT INTO source_route (source_id, layer, config, status, origin) "
        + "VALUES (?, 'OAI', '{\"baseUrl\":\"https://journal.example.org/oai\"}', 'ACTIVE', 'SEED') RETURNING id",
        Long.class, sourceId);
    fake = new FakeLayer();
    handler = handler(new MockEnvironment());
  }

  private FakeHandler handler(MockEnvironment env) {
    return new FakeHandler(fake, writer, queue, runs, routes, outage, clock, env);
  }

  private LocalDate from() {
    return LocalDate.now(ZoneOffset.UTC).minusDays(3);
  }

  private void enqueueFirst(LayerMode mode, TaskPriority priority) {
    queue.enqueue(handler.type(), LayerHarvestHandler.firstPayload("r" + routeId, routeId, List.of(sourceId), from(),
        null, mode), priority, LayerHarvestHandler.key(Layer.OAI, "r" + routeId, mode, from(), null));
  }

  private @Nullable Task claim() {
    List<Task> t = queue.claim(Set.of(handler.type()), "test", 1);
    return t.isEmpty() ? null : t.getFirst();
  }

  private int drain() throws Exception {
    int n = 0;
    for (Task t = claim(); t != null; t = claim()) {
      handler.handle(t);
      queue.complete(t.id(), t.attempts());
      n++;
    }
    return n;
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  void walksPagesAndFinishesRunDone() throws Exception {
    assertThat(handler.type()).isEqualTo("HARVEST_OAI");
    enqueueFirst(LayerMode.LIVE, TaskPriority.LIVE);
    assertThat(drain()).isEqualTo(2);

    assertThat(fake.positions).containsExactly(null, SECOND);
    assertThat(jdbc.queryForList("SELECT doi FROM work ORDER BY doi", String.class))
        .containsExactly("10.5555/p1a", "10.5555/p1b", "10.5555/p2a");
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.adapter()).isEqualTo("OAI");
    assertThat(run.state()).isEqualTo("DONE");
    assertThat(run.calls()).isEqualTo(2);
    assertThat(run.worksSeen()).isEqualTo(3);
    assertThat(run.worksNew()).isEqualTo(3);
    assertThat(run.windowFrom()).isEqualTo(from());
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = 0")).isEqualTo(3);
    // the last page marks the route as working
    assertThat(routes.get(routeId).lastOk()).isNotNull();
    // the continuation carried the run and the position
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'HARVEST_OAI' ORDER BY id", String.class))
        .containsExactly(LayerHarvestHandler.key(Layer.OAI, "r" + routeId, LayerMode.LIVE, from(), null),
            LayerHarvestHandler.key(Layer.OAI, "r" + routeId, LayerMode.LIVE, from(), SECOND));
    assertThat(LayerHarvestHandler.key(Layer.OAI, "r" + routeId, LayerMode.LIVE, from(), SECOND))
        .matches("harvest:oai:r" + routeId + ":LIVE:" + from() + ":[0-9a-f]{12}");
    assertThat(LayerHarvestHandler.key(Layer.OAI, "r" + routeId, LayerMode.LIVE, from(), null))
        .isEqualTo("harvest:oai:r" + routeId + ":LIVE:" + from() + ":start");
  }

  @Test
  void onboardingAndBackfillRunsHaveTheirOwnAdapter() throws Exception {
    enqueueFirst(LayerMode.ONBOARDING, TaskPriority.REPROCESS);
    drain();
    assertThat(runs.recent(1).getFirst().adapter()).isEqualTo("OAI_ONBOARDING");
    assertThat(jdbc.queryForList("SELECT DISTINCT origin FROM work", String.class)).containsExactly("ONBOARDING");
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND priority = 1")).isEqualTo(3);

    jdbc.update("DELETE FROM work");
    fake.positions.clear();
    enqueueFirst(LayerMode.BACKFILL, TaskPriority.BACKFILL);
    drain();
    assertThat(runs.recent(1).getFirst().adapter()).isEqualTo("OAI_BACKFILL");
    assertThat(jdbc.queryForList("SELECT DISTINCT origin FROM work", String.class)).containsExactly("BACKFILL");
  }

  @Test
  void outageDefersSamePageWithoutAttempt() throws Exception {
    fake.failOn = SECOND;
    enqueueFirst(LayerMode.LIVE, TaskPriority.LIVE);
    Instant before = Instant.now();
    assertThat(drain()).isEqualTo(2); // the first page, then the second one, which defers itself and completes

    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("DEFERRED");
    Map<String, Object> next = jdbc.queryForMap("SELECT payload::text AS payload, run_after, dedup_key, attempts "
        + "FROM task WHERE state = 'QUEUED' AND type = 'HARVEST_OAI'");
    String payload = ((String) next.get("payload")).replace(" ", "");
    assertThat(payload).contains("\"position\":\"tok-2\"", "\"runId\":" + run.id(), "\"mode\":\"LIVE\"");
    Instant runAfter = ((java.sql.Timestamp) next.get("run_after")).toInstant();
    assertThat(runAfter).isBetween(before.plus(Duration.ofMinutes(30)).minusSeconds(1),
        Instant.now().plus(Duration.ofMinutes(30)).plusSeconds(1));
    assertThat((String) next.get("dedup_key")).isEqualTo(HarvestOutage.key(
        LayerHarvestHandler.key(Layer.OAI, "r" + routeId, LayerMode.LIVE, from(), SECOND), runAfter));
    assertThat(((Number) next.get("attempts")).intValue()).isZero();
    assertThat(count("SELECT count(*) FROM task WHERE state = 'DEAD'")).isZero();
    assertThat(routes.get(routeId).lastOk()).isNull();

    // once the service is back, the deferred page finishes the same run
    fake.failOn = null;
    jdbc.update("UPDATE task SET run_after = now() WHERE type = 'HARVEST_OAI' AND state = 'QUEUED'");
    assertThat(drain()).isEqualTo(1);
    assertThat(runs.get(run.id()).state()).isEqualTo("DONE");
    assertThat(fake.positions).containsExactly(null, SECOND, SECOND);
  }

  @Test
  void otherFailureMarksRunFailedAndThrows() {
    HarvestLayer broken = new HarvestLayer() {
      @Override public Layer layer() {
        return Layer.OAI;
      }

      @Override public LayerPage page(LayerJob job, @Nullable String position) {
        throw new HttpProblem(URI.create("https://journal.example.org/oai"), 400, false, "HTTP 400", null);
      }
    };
    LayerHarvestHandler h = new LayerHarvestHandler(writer, queue, runs, routes, outage, clock,
        new MockEnvironment()) {
      @Override protected HarvestLayer layer() {
        return broken;
      }
    };
    enqueueFirst(LayerMode.LIVE, TaskPriority.LIVE);
    Task t = claim();
    assertThatThrownBy(() -> h.handle(t)).isInstanceOf(HttpProblem.class);
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("FAILED");
    assertThat(run.error()).contains("HTTP 400");
    // the route shows why; one failed attempt is no reason to give it up
    assertThat(routes.get(routeId).lastError()).contains("HTTP 400");
    assertThat(routes.get(routeId).status()).isEqualTo("ACTIVE");

    // the attempt that makes the task DEAD marks the route BROKEN: no nightly re-read of a dead route
    Task last = new Task(t.id(), t.type(), t.payload(), t.priority(), t.state(), 5, t.runAfter(), null, null, null,
        t.dedupKey(), t.created());
    assertThatThrownBy(() -> h.handle(last)).isInstanceOf(HttpProblem.class);
    assertThat(routes.get(routeId).status()).isEqualTo("BROKEN");
    assertThat(routes.get(routeId).lastError()).contains("HTTP 400");
  }

  @Test
  void aDeadBackfillLeavesTheRouteActive() {
    HarvestLayer broken = new HarvestLayer() {
      @Override public Layer layer() {
        return Layer.OAI;
      }

      @Override public LayerPage page(LayerJob job, @Nullable String position) {
        throw new HttpProblem(URI.create("https://journal.example.org/oai"), 400, false, "HTTP 400", null);
      }
    };
    LayerHarvestHandler h = new LayerHarvestHandler(writer, queue, runs, routes, outage, clock,
        new MockEnvironment()) {
      @Override protected HarvestLayer layer() {
        return broken;
      }
    };
    enqueueFirst(LayerMode.BACKFILL, TaskPriority.BACKFILL);
    Task t = claim();
    Task last = new Task(t.id(), t.type(), t.payload(), t.priority(), t.state(), 5, t.runAfter(), null, null, null,
        t.dedupKey(), t.created());
    // the history failed, the daily harvest still works: the route stays ACTIVE and says why
    assertThatThrownBy(() -> h.handle(last)).isInstanceOf(HttpProblem.class);
    assertThat(routes.get(routeId).status()).isEqualTo("ACTIVE");
    assertThat(routes.get(routeId).lastError()).contains("HTTP 400");
  }

  @Test
  void redeliveredPageCreatesNoDuplicates() throws Exception {
    enqueueFirst(LayerMode.LIVE, TaskPriority.LIVE);
    Task first = claim();
    handler.handle(first);
    handler.handle(first); // delivered again after a crash before completion
    queue.complete(first.id(), first.attempts());

    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'HARVEST_OAI' AND state = 'QUEUED'")).isEqualTo(1);

    Task second = claim();
    handler.handle(second);
    handler.handle(second);
    queue.complete(second.id(), second.attempts());
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(3);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'HARVEST_OAI' AND state = 'QUEUED'")).isZero();
  }

  @Test
  void disabledLayerLeavesTasksQueued() throws Exception {
    FakeHandler off = handler(new MockEnvironment().withProperty("nametracker.layers.oai.enabled", "false"));
    assertThat(off.enabled()).isFalse();
    assertThat(handler.enabled()).isTrue();
    enqueueFirst(LayerMode.LIVE, TaskPriority.LIVE);

    TaskDispatcher dispatcher = new TaskDispatcher(queue, List.of(off), 1, 5, Duration.ofMinutes(1),
        Duration.ofHours(1), clock);
    try {
      dispatcher.onStartup();
      poll(dispatcher);
      Thread.sleep(300);
      assertThat(jdbc.queryForObject("SELECT state FROM task WHERE type = 'HARVEST_OAI'", String.class))
          .isEqualTo("QUEUED");
    } finally {
      dispatcher.destroy();
    }

    // the same task is taken as soon as the layer is on
    TaskDispatcher on = new TaskDispatcher(queue, List.of(handler), 1, 5, Duration.ofMinutes(1),
        Duration.ofHours(1), clock);
    try {
      on.onStartup();
      poll(on);
      await().atMost(10, SECONDS).until(() -> !fake.positions.isEmpty());
    } finally {
      on.destroy();
    }
  }

  /** The scheduled poll of a dispatcher that is not a Spring bean, called by hand. */
  private static void poll(TaskDispatcher d) throws Exception {
    Method m = TaskDispatcher.class.getDeclaredMethod("poll");
    m.setAccessible(true);
    m.invoke(d);
  }
}
