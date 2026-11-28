package org.catalogueoflife.garrulus.indexer.backfill;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.catalogueoflife.garrulus.indexer.backfill.SnapshotFixtures.record;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.content.ResolveContentHandler;
import org.catalogueoflife.garrulus.indexer.content.route.RouteTestSupport;
import org.catalogueoflife.garrulus.store.backfill.BackfillFile;
import org.catalogueoflife.garrulus.store.backfill.BackfillRun;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;

@ExtendWith(OutputCaptureExtension.class)
class BackfillIT extends AbstractBackfillIT {

  static final String FILE = "data/jsonl/works/updated_date=2026-10-01/part_0000.gz";

  @Autowired WorkMapper works;
  @Autowired ResolveContentHandler content;

  /** S1 CORE and S2 BROAD (active), S3 CANDIDATE; one file with the six records of the brief. */
  private void sixRecords() {
    source("S1", "CORE");
    source("S2", "BROAD");
    source("S3", "CANDIDATE");
    manifest(file(FILE, 6));
    serve(FILE, List.of(
        record("W1", "S1", 2005, "article"),
        record("W2", "S2", 2001, "review"),
        record("W3", "S3", 2005, "article"),
        record("W4", "S1", 1999, "article"),
        record("W5", "S1", 2005, "dataset"),
        record("W6", "S9", 2005, "article")));
  }

  @Test
  void importsOnlyRegisteredJournalsYearsAndTypes() throws Exception {
    sixRecords();
    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    assertThat(drain()).isEqualTo(2); // BACKFILL, one BACKFILL_FILE

    assertThat(jdbc.queryForList("SELECT openalex_id FROM work WHERE origin = 'BACKFILL' ORDER BY openalex_id",
        String.class)).containsExactly("W1", "W2");
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    Work w1 = works.findByOpenalexId("W1");
    assertThat(w1.sourceId()).isEqualTo(count("SELECT id FROM source WHERE openalex_id = 'S1'"));
    assertThat(w1.doi()).isEqualTo("10.5555/w1");
    assertThat(w1.title()).isEqualTo("Title of W1 & more");
    assertThat(w1.year()).isEqualTo((short) 2005);
    assertThat(w1.abstractText()).isEqualTo("An abstract.");
    assertThat(w1.state()).isEqualTo("DISCOVERED");
    for (String id : List.of("W1", "W2")) {
      long workId = works.findByOpenalexId(id).id();
      assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT' AND state = 'QUEUED' AND priority = ?"
          + " AND dedup_key = ? AND payload = ?::jsonb", TaskPriority.BACKFILL.code(), "content:" + workId,
          "{\"workId\":" + workId + "}")).isEqualTo(1);
    }
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(2);

    long run = onlyRun();
    BackfillFile f = runs.getFile(run, 0);
    assertThat(f.url()).isEqualTo(BASE + "/" + FILE);
    assertThat(f.recordsRead()).isEqualTo(6);
    assertThat(f.recordsKept()).isEqualTo(2);
    assertThat(f.worksCreated()).isEqualTo(2);
    assertThat(f.doneAt()).isNotNull();
    BackfillRun r = runs.getRun(run);
    assertThat(r.state()).isEqualTo("DONE");
    assertThat(r.finished()).isNotNull();
    assertThat(r.fromYear()).isEqualTo((short) 2000);
    assertThat(r.toYear()).isEqualTo((short) 2026);
    assertThat(r.snapshotDate()).isEqualTo(LocalDate.of(2026, 10, 14));
    assertThat(r.filesTotal()).isEqualTo(1);
  }

  @Test
  void existingWorkUntouched() throws Exception {
    long s1 = source("S1", "CORE");
    Instant firstSeen = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    long live = jdbc.queryForObject("INSERT INTO work (doi, source_id, title, origin, first_seen_at) "
        + "VALUES ('10.5555/w1', ?, 'Live title', 'LIVE', ?) RETURNING id", Long.class, s1, Timestamp.from(firstSeen));
    manifest(file(FILE, 1));
    serve(FILE, List.of(record("W1", "S1", 2005, "article")));

    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    drain();

    assertThat(count("SELECT count(*) FROM work")).isEqualTo(1);
    Work w = works.get(live);
    assertThat(w.origin()).isEqualTo("LIVE");
    assertThat(w.title()).isEqualTo("Live title");
    assertThat(w.firstSeenAt()).isEqualTo(firstSeen);
    assertThat(w.openalexId()).isNull(); // never updated, not even a missing column
    assertThat(w.year()).isNull();
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isZero();
    BackfillFile f = runs.getFile(onlyRun(), 0);
    assertThat(f.recordsKept()).isEqualTo(1);
    assertThat(f.worksCreated()).isZero();
  }

  @Test
  void rerunImportsNothingNew() throws Exception {
    sixRecords();
    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    drain();
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);

    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    assertThat(drain()).isEqualTo(2);

    List<Long> ids = jdbc.queryForList("SELECT id FROM backfill_run ORDER BY id", Long.class);
    assertThat(ids).hasSize(2);
    BackfillFile second = runs.getFile(ids.get(1), 0);
    assertThat(second.recordsRead()).isEqualTo(6);
    assertThat(second.recordsKept()).isEqualTo(2);
    assertThat(second.worksCreated()).isZero();
    assertThat(runs.getRun(ids.get(1)).state()).isEqualTo("DONE");
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(2);
  }

  @Test
  void redeliveredBackfillReusesItsRun() throws Exception {
    sixRecords();
    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    Task t = claim();
    backfill.handle(t);
    backfill.handle(t); // delivered again after a crash before completion
    queue.complete(t.id(), t.attempts());

    assertThat(count("SELECT count(*) FROM backfill_run")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM backfill_file")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'BACKFILL_FILE'")).isEqualTo(1);
    drain();
    assertThat(runs.getRun(onlyRun()).state()).isEqualTo("DONE");
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
  }

  @Test
  void repeatedBackfillResumesADeadFile() throws Exception {
    sixRecords();
    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    assertThat(step()).isTrue();
    Task dead = claim();
    assertThat(queue.fail(dead.id(), dead.attempts(), "gave up", 1, Duration.ZERO)).isTrue();
    assertThat(claim()).isNull();

    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    assertThat(step()).isTrue(); // the BACKFILL: same run, a new task for the file whose task is DEAD
    long run = onlyRun();
    assertThat(jdbc.queryForList("SELECT state FROM task WHERE type = 'BACKFILL_FILE' AND dedup_key = ? ORDER BY id",
        String.class, "backfill:" + run + ":0")).containsExactly("DEAD", "QUEUED");
    drain();
    assertThat(runs.getRun(run).state()).isEqualTo("DONE");
    assertThat(count("SELECT count(*) FROM work")).isEqualTo(2);
  }

  @Test
  void recordCountOtherThanTheManifestsIsLogged(CapturedOutput out) throws Exception {
    source("S1", "CORE");
    manifest(file(FILE, 7));
    serve(FILE, List.of(record("W1", "S1", 2005, "article"), record("W2", "S1", 2005, "article")));

    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    drain();

    assertThat(out.getOut()).contains("file 0: 2 records read, the manifest says 7");
    assertThat(runs.getRun(onlyRun()).state()).isEqualTo("DONE"); // a WARN, not a failure
  }

  @Test
  void fromYearNeverBeforeTheFirstYear() throws Exception {
    source("S1", "CORE");
    String path = "data/jsonl/works/updated_date=2026-01-01/part_0000.gz";
    manifest(file(path, 1));
    serve(path, List.of(record("W-old", "S1", 2020, "article")));
    enqueueBackfill("{\"fromYear\":1990,\"toYear\":2026}"); // garrulus.first-year is 2000
    assertThat(step()).isTrue();
    assertThat(runs.getRun(onlyRun()).fromYear()).isEqualTo((short) 2000);
  }

  @Test
  void sliceTakesOnlyGivenFiles() throws Exception {
    source("S1", "CORE");
    String[] paths = new String[4];
    SnapshotFixtures.Entry[] entries = new SnapshotFixtures.Entry[4];
    for (int i = 0; i < 4; i++) {
      paths[i] = "data/jsonl/works/updated_date=2026-0" + (i + 1) + "-01/part_000" + i + ".gz";
      entries[i] = file(paths[i], 1);
    }
    manifest(entries);
    serve(paths[1], List.of(record("W-p1", "S1", 2020, "article")));
    serve(paths[2], List.of(record("W-p2", "S1", 2021, "review")));

    enqueueBackfill("{\"fromYear\":2000,\"files\":2,\"fromFile\":1}"); // toYear: this year
    assertThat(step()).isTrue();

    long run = onlyRun();
    BackfillRun r = runs.getRun(run);
    assertThat(r.filesTotal()).isEqualTo(2);
    assertThat(r.fromYear()).isEqualTo((short) 2000);
    assertThat(r.toYear()).isEqualTo((short) LocalDate.now(ZoneOffset.UTC).getYear());
    assertThat(runs.listFiles(run)).extracting(BackfillFile::idx, BackfillFile::url)
        .containsExactly(org.assertj.core.groups.Tuple.tuple(1, BASE + "/" + paths[1]),
            org.assertj.core.groups.Tuple.tuple(2, BASE + "/" + paths[2]));
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'BACKFILL_FILE' AND priority = ? "
        + "ORDER BY dedup_key", String.class, TaskPriority.BACKFILL.code()))
        .containsExactly("backfill:" + run + ":1", "backfill:" + run + ":2");
    assertThat(count("SELECT count(*) FROM task WHERE type = 'BACKFILL_FILE' AND payload = ?::jsonb",
        "{\"runId\":" + run + ",\"idx\":1,\"records\":1}")).isEqualTo(1);

    assertThat(step()).isTrue();
    assertThat(runs.getRun(run).state()).isEqualTo("RUNNING"); // one file still to go
    assertThat(step()).isTrue();
    assertThat(runs.getRun(run).state()).isEqualTo("DONE");
    assertThat(step()).isFalse();

    assertThat(jdbc.queryForList("SELECT openalex_id FROM work ORDER BY openalex_id", String.class))
        .containsExactly("W-p1", "W-p2");
    verify(0, getRequestedFor(urlPathEqualTo("/" + paths[0])));
    verify(0, getRequestedFor(urlPathEqualTo("/" + paths[3])));

    // a file task delivered again after its file is done does nothing
    files.handle(new Task(1, BackfillFileHandler.TYPE, "{\"runId\":" + run + ",\"idx\":1}", TaskPriority.BACKFILL,
        null, 1, Instant.now(), null, null, null, null, null));
    verify(1, getRequestedFor(urlPathEqualTo("/" + paths[1])));
  }

  @Test
  void backfillWorkNeverUsesOpenAlexContent() throws Exception {
    long s1 = source("S1", "CORE");
    manifest(file(FILE, 1));
    serve(FILE, List.of(record("W1", "S1", 2005, "article", true)));
    // the free routes find nothing: Europe PMC knows neither work, so PMC has no PMCID to fetch
    stubFor(get(urlPathEqualTo("/rest/search")).willReturn(aResponse().withHeader("Content-Type", "application/json")
        .withBody(RouteTestSupport.fixture("europepmc/search-empty.json"))));
    stubFor(get(urlPathMatching("/works/.*\\.grobid-xml")).willReturn(aResponse()
        .withHeader("Content-Type", "application/xml").withBody(RouteTestSupport.fixture("openalex/tei-small.xml"))));

    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    drain();
    Work w1 = works.findByOpenalexId("W1");
    assertThat(w1.origin()).isEqualTo("BACKFILL");
    assertThat(w1.openalexHasTei()).isTrue();

    content.handle(resolve(w1.id()));
    verify(0, getRequestedFor(urlPathMatching("/works/.*\\.grobid-xml")));
    assertThat(works.get(w1.id()).state()).isEqualTo("CONTENT_RESOLVED");

    // the control: a LIVE work of the same CORE source with TEI does fetch it
    long live = jdbc.queryForObject("INSERT INTO work (openalex_id, doi, source_id, title, abstract, origin, "
        + "openalex_has_tei) VALUES ('WL', '10.5555/wl', ?, 'T', 'An abstract.', 'LIVE', true) RETURNING id", Long.class,
        s1);
    content.handle(resolve(live));
    verify(1, getRequestedFor(urlPathEqualTo("/works/WL.grobid-xml")));
    verify(0, getRequestedFor(urlPathEqualTo("/works/W1.grobid-xml")));
  }

  private static Task resolve(long workId) {
    return new Task(1, "RESOLVE_CONTENT", "{\"workId\":" + workId + "}", TaskPriority.BACKFILL, null, 1, Instant.now(),
        null, null, null, "content:" + workId, null);
  }
}
