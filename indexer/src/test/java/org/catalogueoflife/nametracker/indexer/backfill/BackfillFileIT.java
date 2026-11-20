package org.catalogueoflife.nametracker.indexer.backfill;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.cut;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.gzip;
import static org.catalogueoflife.nametracker.indexer.backfill.SnapshotFixtures.record;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.backfill.BackfillFile;
import org.catalogueoflife.nametracker.store.task.Task;
import org.junit.jupiter.api.Test;

class BackfillFileIT extends AbstractBackfillIT {

  @org.springframework.beans.factory.annotation.Autowired org.catalogueoflife.nametracker.indexer.lang.LanguageService languages;
  @org.springframework.beans.factory.annotation.Autowired org.catalogueoflife.nametracker.store.work.WorkMapper works;

  static final String FILE = "data/jsonl/works/updated_date=2026-10-01/part_0000.gz";
  static final int N = 1200;

  /** Runs the BACKFILL task and claims the one file task. */
  private Task fileTask() throws Exception {
    manifest(file(FILE, N));
    enqueueBackfill("{\"fromYear\":2000,\"toYear\":2026}");
    assertThat(step()).isTrue();
    Task t = claim();
    assertThat(t.type()).isEqualTo(BackfillFileHandler.TYPE);
    return t;
  }

  @Test
  void truncatedFileFailsAndRetryIsIdempotent() throws Exception {
    source("S1", "CORE");
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < N; i++) lines.add(record("W" + i, "S1", 2005 + i % 20, i % 2 == 0 ? "article" : "review"));
    byte[] whole = gzip(lines);
    serve(FILE, cut(whole, 0.8));

    Task t = fileTask();
    Throwable e = catchThrowable(() -> files.handle(t));
    assertThat(e).isNotNull().isNotInstanceOf(ServiceOutageException.class);
    assertThat(HarvestOutage.retryable(e)).isNull(); // a failure that uses an attempt, not an outage
    assertThat(queue.fail(t.id(), t.attempts(), e.toString(), 5, Duration.ZERO)).isTrue();

    long run = onlyRun();
    assertThat(runs.getFile(run, 0).doneAt()).isNull();
    assertThat(runs.getRun(run).state()).isEqualTo("RUNNING");
    long partial = count("SELECT count(*) FROM work");
    assertThat(partial).as("the chunks before the cut are committed").isBetween(500L, (long) N - 1);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(partial);
    assertThat(runs.getFile(run, 0).worksCreated()).isEqualTo(partial); // committed with the works

    serve(FILE, whole);
    Task retry = claim();
    assertThat(retry.id()).isEqualTo(t.id());
    assertThat(retry.attempts()).isEqualTo(2);
    files.handle(retry);
    queue.complete(retry.id(), retry.attempts());

    assertThat(count("SELECT count(*) FROM work")).isEqualTo(N);
    assertThat(count("SELECT count(DISTINCT openalex_id) FROM work WHERE origin = 'BACKFILL'")).isEqualTo(N);
    assertThat(count("SELECT count(*) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(N);
    assertThat(count("SELECT count(DISTINCT dedup_key) FROM task WHERE type = 'RESOLVE_CONTENT'")).isEqualTo(N);
    BackfillFile f = runs.getFile(run, 0);
    assertThat(f.doneAt()).isNotNull();
    assertThat(f.recordsRead()).isEqualTo(N);
    assertThat(f.recordsKept()).isEqualTo(N);
    assertThat(f.worksCreated()).isEqualTo(N); // over both passes: the chunks of the broken pass count too
    assertThat(runs.getRun(run).state()).isEqualTo("DONE");
  }

  @Test
  void backfilledOpenAlexLanguageIsEvidenceAndSurvivesNaming() throws Exception {
    source("S1", "CORE");
    serve(FILE, gzip(List.of(record("W1", "S1", 2020, "article").replaceFirst("\\}$", ",\"language\":\"de\"}"))));
    Task t = fileTask();
    files.handle(t);
    queue.complete(t.id(), t.attempts());

    long id = count("SELECT id FROM work");
    assertThat(jdbc.queryForMap("SELECT language, language_source FROM work WHERE id = ?", id))
        .containsEntry("language", "de").containsEntry("language_source", "OPENALEX");
    assertThat(count("SELECT count(*) FROM work_language_evidence WHERE work_id = " + id
        + " AND kind = 'OPENALEX' AND lang = 'de'")).isEqualTo(1);
    // naming: the title is too unclear to detect, the OpenAlex language stays
    languages.detectFromWork(works.get(id));
    languages.decide(id);
    assertThat(jdbc.queryForMap("SELECT language, language_source FROM work WHERE id = ?", id))
        .containsEntry("language", "de").containsEntry("language_source", "OPENALEX");
  }

  @Test
  void unavailableFileIsDeferredAsOutage() throws Exception {
    source("S1", "CORE");
    stubFor(get(urlPathEqualTo("/" + FILE)).willReturn(aResponse().withStatus(503)));

    Task t = fileTask();
    Throwable e = catchThrowable(() -> files.handle(t));
    assertThat(e).isInstanceOf(ServiceOutageException.class);
    assertThat(runs.getFile(onlyRun(), 0).doneAt()).isNull();
    assertThat(count("SELECT count(*) FROM work")).isZero();
  }
}
