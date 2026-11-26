package org.catalogueoflife.garrulus.indexer.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.harvest.WorksPager;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class MergeWorksHandlerIT extends AbstractPostgresIT {

  @Autowired MergeWorksHandler handler;
  @Autowired JdbcTemplate jdbc;
  long user;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM hit");
    jdbc.update("DELETE FROM subscription");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM work");
    user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('m@example.org') RETURNING id", Long.class);
  }

  private long work(String doi, String openalexId, String title, String metaLayer) {
    return jdbc.queryForObject("INSERT INTO work (doi, openalex_id, title, origin, state, text_level, meta_layer) "
        + "VALUES (?, ?, ?, 'LIVE', 'CONTENT_RESOLVED', 'ABSTRACT_ONLY', ?) RETURNING id",
        Long.class, doi, openalexId, title, metaLayer);
  }

  private static Task task(long survivor, long other) {
    return new Task(1, WorksPager.MERGE_WORKS, "{\"survivor\":" + survivor + ",\"other\":" + other + "}",
        TaskPriority.LIVE, null, 1, Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null,
        "k", null);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private List<String> queued(String type) {
    return jdbc.queryForList("SELECT payload::text FROM task WHERE type = ? AND state = 'QUEUED' ORDER BY id",
        String.class, type);
  }

  @Test
  void mergesIntoOlderAndMovesIdentity() throws Exception {
    long a = work(null, null, "Older", "BLR");
    long b = work("10.1/x", "W9", "Younger", "OPENALEX");
    jdbc.update("INSERT INTO work_identifier (work_id, scheme, value, first_layer) VALUES (?, 'PMID', '77', 'OPENALEX')", b);
    jdbc.update("INSERT INTO work_sighting (work_id, layer) VALUES (?, 'OPENALEX')", b);
    jdbc.update("INSERT INTO work_sighting (work_id, layer) VALUES (?, 'BLR')", a);

    handler.handle(task(a, b));

    assertThat(count("SELECT count(*) FROM work WHERE id = ?", b)).isZero();
    assertThat(jdbc.queryForMap("SELECT doi, openalex_id, title, meta_layer FROM work WHERE id = ?", a))
        .containsEntry("doi", "10.1/x").containsEntry("openalex_id", "W9").containsEntry("title", "Younger")
        .containsEntry("meta_layer", "OPENALEX");
    assertThat(count("SELECT count(*) FROM work_identifier WHERE work_id = ? AND scheme = 'PMID'", a)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM work_sighting WHERE work_id = ?", a)).isEqualTo(2);
  }

  @Test
  void keepsSurvivorHitsAndDropsDuplicates() throws Exception {
    long a = work(null, null, "Older", null);
    long b = work(null, null, "Younger", null);
    long s1 = sub("A");
    long s2 = sub("B");
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind) VALUES (?, ?, 'ARTICLE')", s1, a);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind) VALUES (?, ?, 'ARTICLE')", s1, b);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind) VALUES (?, ?, 'ARTICLE')", s2, b);

    handler.handle(task(a, b));

    assertThat(count("SELECT count(*) FROM hit WHERE subscription_id = ? AND kind = 'ARTICLE'", s1)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM hit WHERE subscription_id = ? AND work_id = ?", s2, a)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM hit WHERE work_id = ?", a)).isEqualTo(2);
  }

  private long sub(String target) {
    return jdbc.queryForObject("INSERT INTO subscription (user_id, type, target) VALUES (?, 'NAME', ?) RETURNING id",
        Long.class, user, target);
  }

  @Test
  void secondDeliveryIsNoOp() throws Exception {
    long a = work(null, null, "Older", null);
    long b = work(null, null, "Younger", null);
    handler.handle(task(a, b));
    handler.handle(task(a, b));
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(1);
  }

  @Test
  void enqueuesResolveContentWhenContentMoved() throws Exception {
    long a = work(null, null, "Older", null);
    long b = work(null, null, "Younger", null);
    jdbc.update("INSERT INTO content (work_id, route, kind, license, retained, raw_sha256, doc_sha256) "
        + "VALUES (?, 'PMC', 'JATS', 'cc-by', true, 'r', ?)", b, "f".repeat(64));
    Task t = task(a, b);
    handler.handle(t);
    assertThat(count("SELECT count(*) FROM content WHERE work_id = ?", a)).isEqualTo(1);
    // the survivor's text level and document are recomputed by RESOLVE_CONTENT, not named on its stale level
    assertThat(queued("FIND_NAMES")).isEmpty();
    java.util.Map<String, Object> r = jdbc.queryForMap("SELECT payload::text AS payload, priority, dedup_key, run_after "
        + "FROM task WHERE type = 'RESOLVE_CONTENT' AND state = 'QUEUED'");
    tools.jackson.databind.JsonNode payload = org.catalogueoflife.garrulus.finder.api.FinderJson.mapper()
        .readTree((String) r.get("payload"));
    assertThat(payload.path("workId").asLong()).isEqualTo(a);
    assertThat(payload.path("freeOnly").asBoolean()).isTrue(); // resolved before: no paid route again
    assertThat(((Number) r.get("priority")).intValue()).isEqualTo(TaskPriority.REPROCESS.code());
    assertThat(r.get("dedup_key")).isEqualTo("merge-content:" + a + ":" + b);
    assertThat(((java.sql.Timestamp) r.get("run_after")).toInstant()).isEqualTo(t.runAfter());

    // without content to move nothing is enqueued
    jdbc.update("DELETE FROM task");
    long c = work(null, null, "Third", null);
    handler.handle(task(a, c));
    assertThat(queued("RESOLVE_CONTENT")).isEmpty();
  }

  @Autowired org.catalogueoflife.garrulus.indexer.content.ResolveContentHandler resolve;

  private Task queuedTask(String type) {
    java.util.Map<String, Object> t = jdbc.queryForMap("SELECT id, payload::text AS payload, dedup_key FROM task "
        + "WHERE type = ? AND state = 'QUEUED' ORDER BY id DESC LIMIT 1", type);
    return new Task(((Number) t.get("id")).longValue(), type, (String) t.get("payload"), TaskPriority.REPROCESS,
        null, 1, Instant.now(), null, null, null, (String) t.get("dedup_key"), Instant.now());
  }

  @Test
  void movedFullTextIsNamedOnTheSurvivor() throws Exception {
    // an abstract-only survivor, named, receives the younger's full text: it is named on the full text
    long a = work(null, null, "Older", null);
    jdbc.update("UPDATE work SET state = 'INDEXED', abstract = 'An abstract.', abstract_source = 'OAI' WHERE id = ?", a);
    long b = work(null, null, "Younger", null);
    jdbc.update("INSERT INTO content (work_id, route, kind, license, retained, raw_sha256, doc_sha256) "
        + "VALUES (?, 'PMC', 'JATS', 'cc-by', true, 'r', ?)", b, "f".repeat(64));
    handler.handle(task(a, b));
    resolve.handle(queuedTask("RESOLVE_CONTENT"));
    assertThat(jdbc.queryForMap("SELECT state, text_level FROM work WHERE id = ?", a))
        .containsEntry("state", "INDEXED").containsEntry("text_level", "FULLTEXT");
    assertThat(queuedTask("FIND_NAMES").dedupKey()).isEqualTo("names:" + a + ":" + "f".repeat(12));
  }

  @Test
  void anAbstractMovedToANoTextSurvivorIsNamed() throws Exception {
    long a = jdbc.queryForObject("INSERT INTO work (doi, origin, state, text_level) "
        + "VALUES ('10.1234/notext', 'LIVE', 'INDEXED', 'NO_TEXT') RETURNING id", Long.class);
    long b = withAbstract("DISCOVERED", "Aus bus is described from Chile.", "OAI");
    handler.handle(task(a, b));
    resolve.handle(queuedTask("RESOLVE_CONTENT"));
    assertThat(jdbc.queryForObject("SELECT text_level FROM work WHERE id = ?", String.class, a))
        .isEqualTo("ABSTRACT_ONLY");
    assertThat(queuedTask("FIND_NAMES").dedupKey()).startsWith("names:" + a + ":");
  }

  @Test
  void differentDoisAreNeverMerged() throws Exception {
    long a = work("10.1/a", null, "Older", null);
    long b = work("10.1/b", null, "Younger", null);
    handler.handle(task(a, b));
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
  }

  private long withAbstract(String state, String text, String source) {
    return jdbc.queryForObject("INSERT INTO work (title, abstract, abstract_source, origin, state, text_level) "
        + "VALUES ('T', ?, ?, 'LIVE', ?, 'ABSTRACT_ONLY') RETURNING id", Long.class, text, source, state);
  }

  @Test
  void abstractChangeOnNamedSurvivorEnqueuesResolveContent() throws Exception {
    long a = withAbstract("INDEXED", "Inverted abstract", "OPENALEX_INVERTED");
    long b = withAbstract("DISCOVERED", "Better abstract", "CROSSREF_JATS");
    handler.handle(task(a, b));
    assertThat(jdbc.queryForObject("SELECT abstract FROM work WHERE id = ?", String.class, a))
        .isEqualTo("Better abstract");
    assertThat(queued("RESOLVE_CONTENT")).hasSize(1);
    assertThat(queued("FIND_NAMES")).isEmpty();
  }

  @Test
  void discoveredSurvivorGetsNoExtraResolve() throws Exception {
    long a = withAbstract("DISCOVERED", "Inverted abstract", "OPENALEX_INVERTED");
    long b = withAbstract("DISCOVERED", "Better abstract", "CROSSREF_JATS");
    handler.handle(task(a, b));
    assertThat(jdbc.queryForObject("SELECT abstract FROM work WHERE id = ?", String.class, a))
        .isEqualTo("Better abstract");
    assertThat(queued("FIND_NAMES")).isEmpty();
    assertThat(queued("RESOLVE_CONTENT")).isEmpty();
  }
}
