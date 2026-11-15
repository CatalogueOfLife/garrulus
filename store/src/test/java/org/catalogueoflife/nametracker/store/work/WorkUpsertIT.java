package org.catalogueoflife.nametracker.store.work;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkUpsertIT extends AbstractPostgresIT {

  @Autowired WorkMapper works;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  private static Work work(String openalexId, String doi, String title, String origin) {
    return new Work(null, openalexId, doi, null, title, null, null, null, null, null, null, null, null, null, null,
        null, null, null, origin, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void setPmcidWritesOnlyWhenEmpty() {
    long id = new WorkUpsert(works).upsert(work(null, "10.1/pmcid", "T", "LIVE")).id();
    assertThat(works.setPmcid(id, "PMC1")).isEqualTo(1);
    assertThat(works.setPmcid(id, "PMC2")).isZero();
    assertThat(works.get(id).pmcid()).isEqualTo("PMC1");
  }

  @Test
  void upsertMergesByDoiAndKeepsOriginAndFirstSeen() {
    WorkUpsert upsert = new WorkUpsert(works);
    WorkUpsert.UpsertResult first = upsert.upsert(work(null, "10.3897/ZooKeys.1.2", null, "LIVE"));
    assertThat(first.created()).isTrue();
    Work stored = works.get(first.id());
    Instant firstSeen = stored.firstSeenAt();

    WorkUpsert.UpsertResult second =
        upsert.upsert(work("W1", "https://doi.org/10.3897/zookeys.1.2", "A title", "ONBOARDING"));
    assertThat(second.created()).isFalse();
    assertThat(second.id()).isEqualTo(first.id());

    Work merged = works.get(first.id());
    assertThat(merged.doi()).isEqualTo("10.3897/zookeys.1.2");
    assertThat(merged.openalexId()).isEqualTo("W1");
    assertThat(merged.title()).isEqualTo("A title");
    assertThat(merged.origin()).isEqualTo("LIVE");
    assertThat(merged.state()).isEqualTo("DISCOVERED");
    assertThat(merged.firstSeenAt()).isEqualTo(firstSeen);
    assertThat(works.findByDoi("10.3897/zookeys.1.2").id()).isEqualTo(first.id());
    assertThat(works.findByOpenalexId("W1").id()).isEqualTo(first.id());
  }

  @Test
  void nullIncomingNeverWipesAndStateIsKept() {
    WorkUpsert upsert = new WorkUpsert(works);
    Work full = new Work(null, "W-keep", "doi:10.1/KEEP", null, "Title", LocalDate.of(2026, 1, 2), (short) 2026, "1",
        "2", "3-4", "[{\"name\":\"Doe\"}]", "article", "en", "gold", "cc-by", "https://x", "abs", null, "LIVE",
        null, null, null, null, null, null, null, "<abstract/>", "PMC1", true, null);
    long id = upsert.upsert(full).id();
    works.setState(id, "MATCHED", "FULLTEXT");

    upsert.upsert(work("W-keep", "10.1/keep", null, "BACKFILL"));
    Work w = works.get(id);
    assertThat(w.doi()).isEqualTo("10.1/keep");
    assertThat(w.title()).isEqualTo("Title");
    assertThat(w.volume()).isEqualTo("1");
    assertThat(w.authors()).contains("Doe");
    assertThat(w.abstractJats()).isEqualTo("<abstract/>");
    assertThat(w.pmcid()).isEqualTo("PMC1");
    assertThat(w.openalexHasTei()).isTrue();
    assertThat(w.state()).isEqualTo("MATCHED");
    assertThat(w.textLevel()).isEqualTo("FULLTEXT");
    assertThat(w.origin()).isEqualTo("LIVE");

    upsert.upsert(new Work(null, "W-keep", null, null, "New title", null, null, null, null, null, null, null, null,
        null, null, null, null, null, "SWEEP", null, null, null, null, null, null, null, "<abstract2/>", null,
        false, null));
    Work w2 = works.get(id);
    assertThat(w2.title()).isEqualTo("New title");
    assertThat(w2.abstractJats()).isEqualTo("<abstract2/>");
    assertThat(w2.openalexHasTei()).isFalse();
    assertThat(w2.pmcid()).isEqualTo("PMC1");
  }

  @Test
  void doiFormsNormalise() {
    WorkUpsert upsert = new WorkUpsert(works);
    long id = upsert.upsert(work(null, "http://dx.doi.org/10.5/ABC", null, "LIVE")).id();
    assertThat(works.get(id).doi()).isEqualTo("10.5/abc");
    assertThat(upsert.upsert(work(null, "DOI:10.5/Abc", null, "LIVE"))).isEqualTo(
        new WorkUpsert.UpsertResult(id, false, false));
  }

  @Test
  void concurrentUpsertsMakeOneRow() throws Exception {
    WorkUpsert upsert = new WorkUpsert(works);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<WorkUpsert.UpsertResult>> fs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        fs.add(pool.submit(() -> upsert.upsert(work(null, "10.9999/Concurrent", "T", "LIVE"))));
      }
      long created = 0;
      long id = -1;
      for (Future<WorkUpsert.UpsertResult> f : fs) {
        WorkUpsert.UpsertResult r = f.get(30, TimeUnit.SECONDS);
        if (r.created()) {
          created++;
        }
        assertThat(id == -1 || id == r.id()).isTrue();
        id = r.id();
      }
      assertThat(created).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(works.findByDoi("10.9999/concurrent")).isNotNull();
  }

  @Test
  void listCrossrefOnlyHonoursCheckedAt() {
    WorkUpsert upsert = new WorkUpsert(works);
    long id = upsert.upsert(work(null, "10.7/crossref-only", "T", "LIVE")).id();
    assertThat(works.listCrossrefOnly(1000)).extracting(Work::id).contains(id);
  }

  @Test
  void countByStateAndTextLevel() {
    long id = new WorkUpsert(works).upsert(work(null, "10.1/count", "T", "LIVE")).id();
    works.setState(id, "MATCHED", "FULLTEXT");
    assertThat(works.countByStateAndTextLevel()).anySatisfy(r -> {
      assertThat(r).containsEntry("state", "MATCHED").containsEntry("text_level", "FULLTEXT");
      assertThat(((Number) r.get("n")).longValue()).isGreaterThanOrEqualTo(1);
    });
  }

  @Test
  void abstractJatsAddedOnlyForAResolvedWorkWithoutOne() {
    WorkUpsert upsert = new WorkUpsert(works);
    WorkUpsert.UpsertResult created = upsert.upsert(work(null, "10.1/late", "T", "LIVE"));
    assertThat(created.abstractJatsAdded()).isFalse();
    Work withJats = new Work(null, null, "10.1/late", null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, "<abstract/>", null, null, null);

    // still DISCOVERED: the first RESOLVE_CONTENT has not run yet
    assertThat(upsert.upsert(withJats).abstractJatsAdded()).isFalse();
    long id = created.id();
    jdbc.update("UPDATE work SET abstract_jats = NULL, state = 'CONTENT_RESOLVED' WHERE id = ?", id);

    assertThat(upsert.upsert(withJats).abstractJatsAdded()).isTrue();
    assertThat(works.get(id).abstractJats()).isEqualTo("<abstract/>");
    assertThat(upsert.upsert(withJats).abstractJatsAdded()).isFalse();
  }
}
