package org.catalogueoflife.nametracker.store.work;

import static org.catalogueoflife.nametracker.store.work.LegacyUpserts.legacyInsertIfNew;
import static org.catalogueoflife.nametracker.store.work.LegacyUpserts.legacyUpsert;
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
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkUpsertIT extends AbstractPostgresIT {

  @Autowired WorkMapper works;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired WorkIdentifierMapper identifiers;
  @Autowired WorkSightingMapper sightings;
  @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

  private static Work work(String openalexId, String doi, String title, String origin) {
    return new Work(null, openalexId, doi, null, title, null, null, null, null, null, null, null, null, null, null,
        null, null, null, origin, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void insertIfNewInsertsOnlyUnknownWorksAndNeverTouchesExistingOnes() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long live = legacyUpsert(upsert, work("W-live", "10.1/live", "Live title", "LIVE")).id();
    Instant firstSeen = works.get(live).firstSeenAt();

    // same DOI (other spelling), other OpenAlex id; then same OpenAlex id, no DOI
    assertThat(legacyInsertIfNew(upsert, work("W-other", "https://doi.org/10.1/LIVE", "Snapshot title", "BACKFILL"))).isEmpty();
    assertThat(legacyInsertIfNew(upsert, work("W-live", null, "Snapshot title", "BACKFILL"))).isEmpty();
    Work kept = works.get(live);
    assertThat(kept.title()).isEqualTo("Live title");
    assertThat(kept.origin()).isEqualTo("LIVE");
    assertThat(kept.firstSeenAt()).isEqualTo(firstSeen);
    assertThat(works.findByOpenalexId("W-other")).isNull();

    var created = legacyInsertIfNew(upsert, work("W-new", "DOI:10.1/New", "A &amp; <i>B</i>", "BACKFILL"));
    assertThat(created).isPresent();
    Work w = works.get(created.get());
    assertThat(w.doi()).isEqualTo("10.1/new");
    assertThat(w.title()).isEqualTo("A & B");
    assertThat(w.origin()).isEqualTo("BACKFILL");
    assertThat(legacyInsertIfNew(upsert, work("W-new", "10.1/new", "Again", "BACKFILL"))).isEmpty();
  }

  @Test
  void setPmcidWritesOnlyWhenEmpty() {
    long id = legacyUpsert(new WorkUpsert(works, identifiers, sightings), work(null, "10.1/pmcid", "T", "LIVE")).id();
    assertThat(works.setPmcid(id, "PMC1")).isEqualTo(1);
    assertThat(works.setPmcid(id, "PMC2")).isZero();
    assertThat(works.get(id).pmcid()).isEqualTo("PMC1");
  }

  @Test
  void upsertMergesByDoiAndKeepsOriginAndFirstSeen() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    WorkUpsert.UpsertResult first = legacyUpsert(upsert, work(null, "10.3897/ZooKeys.1.2", null, "LIVE"));
    assertThat(first.created()).isTrue();
    Work stored = works.get(first.id());
    Instant firstSeen = stored.firstSeenAt();

    WorkUpsert.UpsertResult second =
        legacyUpsert(upsert, work("W1", "https://doi.org/10.3897/zookeys.1.2", "A title", "ONBOARDING"));
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
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    Work full = new Work(null, "W-keep", "doi:10.1/KEEP", null, "Title", LocalDate.of(2026, 1, 2), (short) 2026, "1",
        "2", "3-4", "[{\"name\":\"Doe\"}]", "article", "en", "gold", "cc-by", "https://x", "abs", null, "LIVE",
        null, null, null, null, null, null, null, "<abstract/>", "PMC1", true, null, null, null, null);
    long id = legacyUpsert(upsert, full).id();
    works.setState(id, "MATCHED", "FULLTEXT");

    legacyUpsert(upsert, work("W-keep", "10.1/keep", null, "BACKFILL"));
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

    legacyUpsert(upsert, new Work(null, "W-keep", null, null, "New title", null, null, null, null, null, null, null, null,
        null, null, null, null, null, "SWEEP", null, null, null, null, null, null, null, "<abstract2/>", null,
        false, null, null, null, null));
    Work w2 = works.get(id);
    assertThat(w2.title()).isEqualTo("Title"); // frozen: the finder has named the work
    assertThat(w2.abstractJats()).isEqualTo("<abstract2/>");
    assertThat(w2.openalexHasTei()).isFalse();
    assertThat(w2.pmcid()).isEqualTo("PMC1");
  }

  @Test
  void doiFormsNormalise() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long id = legacyUpsert(upsert, work(null, "http://dx.doi.org/10.5/ABC", null, "LIVE")).id();
    assertThat(works.get(id).doi()).isEqualTo("10.5/abc");
    assertThat(legacyUpsert(upsert, work(null, "DOI:10.5/Abc", null, "LIVE"))).isEqualTo(
        new WorkUpsert.UpsertResult(id, false, false, null, null));
  }

  @Test
  void concurrentUpsertsMakeOneRow() throws Exception {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<WorkUpsert.UpsertResult>> fs = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        fs.add(pool.submit(() -> legacyUpsert(upsert, work(null, "10.9999/Concurrent", "T", "LIVE"))));
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
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long id = legacyUpsert(upsert, work(null, "10.7/crossref-only", "T", "LIVE")).id();
    assertThat(works.listCrossrefOnly(1000)).extracting(Work::id).contains(id);
  }

  @Test
  void countByStateAndTextLevel() {
    long id = legacyUpsert(new WorkUpsert(works, identifiers, sightings), work(null, "10.1/count", "T", "LIVE")).id();
    works.setState(id, "MATCHED", "FULLTEXT");
    assertThat(works.countByStateAndTextLevel()).anySatisfy(r -> {
      assertThat(r).containsEntry("state", "MATCHED").containsEntry("text_level", "FULLTEXT");
      assertThat(((Number) r.get("n")).longValue()).isGreaterThanOrEqualTo(1);
    });
  }

  @Test
  void abstractChangedOnlyForAResolvedWork() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    WorkUpsert.UpsertResult created = legacyUpsert(upsert, work(null, "10.1/late", "T", "LIVE"));
    assertThat(created.abstractChanged()).isFalse();
    Work withJats = new Work(null, null, "10.1/late", null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, "<abstract/>", null, null, null, null, null, null);

    // still DISCOVERED: the first RESOLVE_CONTENT has not run yet
    assertThat(legacyUpsert(upsert, withJats).abstractChanged()).isFalse();
    long id = created.id();
    jdbc.update("UPDATE work SET abstract_jats = NULL, state = 'CONTENT_RESOLVED' WHERE id = ?", id);

    WorkUpsert.UpsertResult added = legacyUpsert(upsert, withJats);
    assertThat(added.abstractChanged()).isTrue();
    assertThat(added.abstractSha()).hasSize(12);
    assertThat(works.get(id).abstractJats()).isEqualTo("<abstract/>");
    WorkUpsert.UpsertResult again = legacyUpsert(upsert, withJats);
    assertThat(again.abstractChanged()).isFalse();
    assertThat(again.abstractSha()).isNull();
  }

  @Test
  void aPlainAbstractArrivingLateIsAChange() {
    // a title-only work, named already; the OAI layer brings the plain-text abstract later
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long id = upsert.upsert(work(null, "10.1234/plain-late", "A new species of Aus from Chile", "LIVE"),
        Layer.CROSSREF, List.of()).id();
    jdbc.update("UPDATE work SET state = 'INDEXED', text_level = 'ABSTRACT_ONLY' WHERE id = ?", id);
    Work withText = new Work(null, null, "10.1234/plain-late", null, null, null, null, null, null, null, null, null,
        null, null, null, null, "Aus bus is described.", "OAI", null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null);

    WorkUpsert.UpsertResult r = upsert.upsert(withText, Layer.OAI, List.of());
    assertThat(r.abstractChanged()).isTrue();
    assertThat(works.get(id).abstractText()).isEqualTo("Aus bus is described.");
    assertThat(upsert.upsert(withText, Layer.OAI, List.of()).abstractChanged()).isFalse();

    // a better plain text replacing an inverted index changes it too, with another sha
    jdbc.update("UPDATE work SET abstract = 'aus bus', abstract_source = 'OPENALEX_INVERTED' WHERE id = ?", id);
    WorkUpsert.UpsertResult better = upsert.upsert(withText, Layer.OAI, List.of());
    assertThat(better.abstractChanged()).isTrue();
    assertThat(better.abstractSha()).isEqualTo(r.abstractSha());
  }

  @Test
  void upsertCleansTitlesOnInsertAndMerge() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long id = legacyUpsert(upsert, work("W-dirty", null, "A <i>Carabus</i>\n  of Mulsant &amp; Rey", "LIVE")).id();
    assertThat(works.get(id).title()).isEqualTo("A Carabus of Mulsant & Rey");
    legacyUpsert(upsert, work(null, "10.1/dirty", "x", "LIVE"));
    legacyUpsert(upsert, work("W-dirty", "10.1/dirty-2", "The <b>new</b>\ttitle", "LIVE"));
    assertThat(works.get(id).title()).isEqualTo("The new title");
  }

  // ---- identifiers, title match, sightings

  private long source() {
    return jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('J', 'MANUAL') RETURNING id", Long.class);
  }

  private static Work rec(String doi, Long sourceId, int year, String title, String firstAuthor) {
    String authors = firstAuthor == null ? null : "[{\"name\":\"" + firstAuthor + "\",\"orcid\":null}]";
    return new Work(null, null, doi, sourceId, title, null, (short) year, null, null, null, authors, null, null, null,
        null, null, null, null, "LIVE", null, null, null, null, null, null, null, null, null, null, null, null, null,
        null);
  }

  private static List<Identifier> ids(Identifier... ids) {
    return List.of(ids);
  }

  private static Identifier doi(String raw) {
    return IdScheme.id(IdScheme.DOI, raw);
  }

  private static Identifier oai(String raw) {
    return IdScheme.id(IdScheme.OAI, raw);
  }

  private int sightingCount(long workId, String layer) {
    return jdbc.queryForObject("SELECT count(*) FROM work_sighting WHERE work_id = ? AND layer = ?", Integer.class,
        workId, layer);
  }

  @Test
  void doiSpellingsLandOnOneWork() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    var a = upsert.upsert(rec("https://doi.org/10.1234/Spell", null, 2025, null, null), Layer.CROSSREF,
        ids(doi("https://doi.org/10.1234/Spell")));
    var b = upsert.upsert(rec("doi:10.1234/spell", null, 2025, null, null), Layer.OPENALEX,
        ids(doi("doi:10.1234/spell")));
    // the DOI only as an identifier: the column is filled from it
    var c = upsert.upsert(rec(null, null, 2025, null, null), Layer.OAI,
        ids(doi("10.1234/SPELL"), oai("oai:repo.example:spell")));
    assertThat(a.created()).isTrue();
    assertThat(b.id()).isEqualTo(a.id());
    assertThat(c.id()).isEqualTo(a.id());
    assertThat(b.created() || c.created()).isFalse();
    assertThat(works.get(a.id()).doi()).isEqualTo("10.1234/spell");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_identifier WHERE scheme = 'DOI' AND value LIKE "
        + "'10.1234/spell'", Integer.class)).isEqualTo(1);
    assertThat(identifiers.list(a.id())).containsExactlyInAnyOrder(new Identifier("DOI", "10.1234/spell"),
        new Identifier("OAI", "oai:repo.example:spell"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE doi = '10.1234/spell'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void oaiRecordWithoutDoiJoinsWorkByTitle() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    var cr = upsert.upsert(rec("10.1234/t-oai", src, 2025, "A new species of <i>Aus</i> from the Andes", "Jane Doe"),
        Layer.CROSSREF, ids(doi("10.1234/t-oai")));
    var oa = upsert.upsert(rec(null, src, 2025, "A NEW SPECIES OF AUS FROM THE ANDES.", "Doe, J."), Layer.OAI,
        ids(oai("oai:repo.example:t-oai")));
    assertThat(oa.id()).isEqualTo(cr.id());
    assertThat(oa.created()).isFalse();
    assertThat(oa.duplicateOf()).isNull();
    Work w = works.get(cr.id());
    assertThat(w.title()).isEqualTo("A new species of Aus from the Andes");
    assertThat(identifiers.list(cr.id())).contains(new Identifier("OAI", "oai:repo.example:t-oai"));
    assertThat(sightingCount(cr.id(), "OAI")).isEqualTo(1);
  }

  @Test
  void doiRecordJoinsDoiLessWorkByTitle() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    var oa = upsert.upsert(rec(null, src, 2024, "A NEW SPECIES OF AUS FROM PERU", "Smith, A."), Layer.OAI,
        ids(oai("oai:repo.example:t-doi")));
    assertThat(oa.created()).isTrue();
    var cr = upsert.upsert(rec("10.1234/t-doi", src, 2024, "A new species of Aus from Peru", "Anna Smith"),
        Layer.CROSSREF, ids(doi("10.1234/t-doi")));
    assertThat(cr.id()).isEqualTo(oa.id());
    assertThat(cr.created()).isFalse();
    Work w = works.get(oa.id());
    assertThat(w.doi()).isEqualTo("10.1234/t-doi");
    assertThat(w.title()).isEqualTo("A new species of Aus from Peru");
    assertThat(w.metaLayer()).isEqualTo("CROSSREF");
    assertThat(identifiers.list(oa.id())).containsExactlyInAnyOrder(new Identifier("DOI", "10.1234/t-doi"),
        new Identifier("OAI", "oai:repo.example:t-doi"));
  }

  @Test
  void titleMatchNeverJoinsDifferentDois() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    String title = "A second new species of Aus from Bolivia";
    var a = upsert.upsert(rec("10.1234/dd-a", src, 2023, title, null), Layer.CROSSREF, ids(doi("10.1234/dd-a")));
    var b = upsert.upsert(rec("10.1234/dd-b", src, 2023, title, null), Layer.CROSSREF, ids(doi("10.1234/dd-b")));
    assertThat(b.id()).isNotEqualTo(a.id());
    assertThat(b.created()).isTrue();
    assertThat(b.duplicateOf()).isNull();
  }

  @Test
  void titleMatchNeedsSameSourceYearAndFirstAuthor() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    long other = source();
    String title = "A third new species of Aus from Ecuador";
    long id = upsert.upsert(rec("10.1234/sya", src, 2022, title, "Jane Doe"), Layer.CROSSREF,
        ids(doi("10.1234/sya"))).id();
    assertThat(upsert.upsert(rec(null, other, 2022, title, "Jane Doe"), Layer.OAI, ids(oai("oai:r:sya1"))).id())
        .isNotEqualTo(id);
    assertThat(upsert.upsert(rec(null, src, 2021, title, "Jane Doe"), Layer.OAI, ids(oai("oai:r:sya2"))).id())
        .isNotEqualTo(id);
    assertThat(upsert.upsert(rec(null, src, 2022, title, "John Roe"), Layer.OAI, ids(oai("oai:r:sya3"))).id())
        .isNotEqualTo(id);
    // no authors on one side: the author check does not apply
    assertThat(upsert.upsert(rec(null, src, 2022, title, null), Layer.OAI, ids(oai("oai:r:sya4"))).id())
        .isEqualTo(id);
  }

  @Test
  void genericTitlesNeverMatch() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    String generic = "Corrigendum to: A new species of Aus from Chile";
    var a = upsert.upsert(rec(null, src, 2025, generic, null), Layer.OAI, ids(oai("oai:r:gen1")));
    var b = upsert.upsert(rec(null, src, 2025, generic, null), Layer.OAI, ids(oai("oai:r:gen2")));
    assertThat(b.id()).isNotEqualTo(a.id());
    var c = upsert.upsert(rec(null, src, 2025, "Aus bus", null), Layer.OAI, ids(oai("oai:r:gen3")));
    var d = upsert.upsert(rec(null, src, 2025, "Aus bus", null), Layer.OAI, ids(oai("oai:r:gen4")));
    assertThat(d.id()).isNotEqualTo(c.id());
  }

  @Test
  void pmcidMatchesWorkFirstSeenByDoi() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long id = upsert.upsert(rec("10.1234/pmc", null, 2025, "T", null), Layer.CROSSREF, ids(doi("10.1234/pmc")))
        .id();
    identifiers.insertAll(id, List.of(IdScheme.id(IdScheme.PMCID, "PMC424242")), "OPENALEX");
    // Europe PMC: no DOI, another title, other identifiers
    var r = upsert.upsert(rec(null, null, 2025, "Another spelling of the title", null), Layer.EUROPEPMC,
        ids(IdScheme.id(IdScheme.PMCID, "424242"), IdScheme.id(IdScheme.PMID, "4242")));
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.created()).isFalse();
    assertThat(works.get(id).pmcid()).isEqualTo("PMC424242");
    assertThat(identifiers.list(id)).contains(new Identifier("PMID", "4242"));
    assertThat(sightingCount(id, "EUROPEPMC")).isEqualTo(1);
  }

  @Test
  void sightingPerLayerOnce() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    var a = upsert.upsert(rec(null, null, 2025, "Sighted twice", null), Layer.OAI, ids(oai("oai:r:sight")));
    var b = upsert.upsert(rec(null, null, 2025, "Sighted twice", null), Layer.OAI, ids(oai("oai:r:sight")));
    assertThat(b.id()).isEqualTo(a.id());
    assertThat(sightingCount(a.id(), "OAI")).isEqualTo(1);
    upsert.upsert(rec("10.1234/sight", null, 2025, null, null), Layer.CROSSREF,
        ids(doi("10.1234/sight"), oai("oai:r:sight")));
    assertThat(jdbc.queryForList("SELECT layer FROM work_sighting WHERE work_id = ? ORDER BY layer", String.class,
        a.id())).containsExactly("CROSSREF", "OAI");
  }

  @Test
  void duplicateOfReportedWhenIdsPointToTwoWorks() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long older = upsert.upsert(rec(null, null, 2025, "Seen by OAI first", null), Layer.OAI,
        ids(oai("oai:r:dup"))).id();
    long byDoi = upsert.upsert(rec("10.1234/dup", null, 2025, "Seen by Crossref first", null), Layer.CROSSREF,
        ids(doi("10.1234/dup"))).id();
    assertThat(byDoi).isGreaterThan(older);

    var r = upsert.upsert(rec("10.1234/dup", null, 2025, null, null), Layer.OAI,
        ids(doi("10.1234/dup"), oai("oai:r:dup")));
    assertThat(r.id()).isEqualTo(byDoi); // the DOI comes first in the matching order
    assertThat(r.created()).isFalse();
    assertThat(r.duplicateOf()).isEqualTo(older);
    // the identifier stays with the other work until MERGE_WORKS moves it
    assertThat(identifiers.list(byDoi)).containsExactly(new Identifier("DOI", "10.1234/dup"));
    assertThat(identifiers.list(older)).containsExactly(new Identifier("OAI", "oai:r:dup"));
  }

  @Test
  void crossrefOverwritesOaiTitleButOaiDoesNotOverwriteCrossref() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long id = upsert.upsert(rec("10.1234/ow", null, 2025, "Oai title of a paper", null), Layer.OAI,
        ids(doi("10.1234/ow"), oai("oai:r:ow"))).id();
    assertThat(works.get(id).metaLayer()).isEqualTo("OAI");
    upsert.upsert(rec("10.1234/ow", null, 2025, "Crossref title of a paper", null), Layer.CROSSREF,
        ids(doi("10.1234/ow")));
    assertThat(works.get(id).title()).isEqualTo("Crossref title of a paper");
    upsert.upsert(rec(null, null, 2025, "Oai title of the paper again", null), Layer.OAI, ids(oai("oai:r:ow")));
    Work w = works.get(id);
    assertThat(w.title()).isEqualTo("Crossref title of a paper");
    assertThat(w.metaLayer()).isEqualTo("CROSSREF");
  }

  @Test
  void insertIfNewWritesIdentifiersAndSighting() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    var id = upsert.insertIfNew(rec("10.1234/ifn", src, 2025, "Inserted by the backfill", null), Layer.OPENALEX,
        ids(doi("10.1234/ifn"), IdScheme.id(IdScheme.OPENALEX, "W424242")));
    assertThat(id).isPresent();
    Work w = works.get(id.get());
    assertThat(w.openalexId()).isEqualTo("W424242");
    assertThat(w.metaLayer()).isEqualTo("OPENALEX");
    assertThat(identifiers.list(id.get())).hasSize(2);
    assertThat(sightingCount(id.get(), "OPENALEX")).isEqualTo(1);
    assertThat(upsert.insertIfNew(rec(null, src, 2025, "Inserted by the backfill", null), Layer.OAI,
        ids(oai("oai:r:ifn")))).isEmpty(); // a title match is an existing work
    assertThat(identifiers.list(id.get())).hasSize(2);
  }

  @Test
  void aWorkNeedsAnIdentifier() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WorkUpsert(works, identifiers, sightings).upsert(
        rec(null, null, 2025, "No identifiers at all here", null), Layer.OAI, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void doiLessRecordJoinsNoneOfTwoWorksWithDifferentDois() {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    String title = "A new species of Aus from Uruguay, with an erratum";
    long a = upsert.upsert(rec("10.1234/amb-a", src, 2023, title, null), Layer.CROSSREF, ids(doi("10.1234/amb-a")))
        .id();
    long b = upsert.upsert(rec("10.1234/amb-b", src, 2023, title, null), Layer.CROSSREF, ids(doi("10.1234/amb-b")))
        .id();
    // ambiguous: joining either would later ask MERGE_WORKS to merge two DOIs
    var r = upsert.upsert(rec(null, src, 2023, title, null), Layer.OAI, ids(oai("oai:r:amb")));
    assertThat(r.created()).isTrue();
    assertThat(r.id()).isNotIn(a, b);
    assertThat(r.duplicateOf()).isNull();
    // one DOI among the candidates is not ambiguous
    long c = upsert.upsert(rec("10.1234/amb-c", src, 2023, "Only one DOI work has this title", null), Layer.CROSSREF,
        ids(doi("10.1234/amb-c"))).id();
    upsert.upsert(rec(null, src, 2023, "Only one DOI work has this title", null), Layer.OAI, ids(oai("oai:r:amb-c1")));
    assertThat(identifiers.list(c)).contains(new Identifier("OAI", "oai:r:amb-c1"));
  }

  @Test
  void concurrentLayersWithoutSharedIdentifierMakeOneWork() throws Exception {
    WorkUpsert upsert = new WorkUpsert(works, identifiers, sightings);
    long src = source();
    String title = "A concurrent new species of Aus from Paraguay";
    var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
    var crossrefInside = new java.util.concurrent.CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<WorkUpsert.UpsertResult> cr = pool.submit(() -> tx.execute(s -> {
        var r = upsert.upsert(rec("10.1234/conc", src, 2025, title, null), Layer.CROSSREF, ids(doi("10.1234/conc")));
        crossrefInside.countDown();
        try {
          Thread.sleep(500); // still uncommitted while the OAI record arrives
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return r;
      }));
      assertThat(crossrefInside.await(30, TimeUnit.SECONDS)).isTrue();
      Future<WorkUpsert.UpsertResult> oa = pool.submit(() -> tx.execute(s ->
          upsert.upsert(rec(null, src, 2025, title, null), Layer.OAI, ids(oai("oai:r:conc")))));
      assertThat(oa.get(30, TimeUnit.SECONDS).id()).isEqualTo(cr.get(30, TimeUnit.SECONDS).id());
    } finally {
      pool.shutdownNow();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE source_id = ?", Integer.class, src)).isEqualTo(1);
  }
}
