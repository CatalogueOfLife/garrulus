# M6a: work identity and the layered harvest — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five harvest layers (OpenAlex, Crossref, OAI-PMH incl. Magtech RSS, Europe PMC, Plazi/BLR) run side by side,
each able to create works on its own; their records merge into one work through an identifier store with per-field
layer precedence; routes per journal with verification, onboarding passes and route backfill; a nightly coverage
monitor that rates every layer.

**Architecture:** New store tables (`work_identifier`, `work_sighting`, `source_route`, `layer_health`,
`source_layer_coverage`, `layer_unmatched`) and a pure `WorkMerger` behind a reworked `WorkUpsert`. The existing
OpenAlex and Crossref handlers keep their paging and only gain identifiers, sightings and independence from OpenAlex.
The new layers share one framework: a `HarvestLayer` SPI (`page(job, position)`) driven by a generic
`LayerHarvestHandler` that owns harvest runs, continuation tasks, outage deferral and the `LayerWriter` (age rule,
origin, upsert, follow-up tasks).

**Tech Stack:** JDK 25, Spring Boot 4.1, MyBatis, Flyway, Postgres 17, Testcontainers, WireMock, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md` (approved 2026-11-19). M6a covers its
sections "The layers", "Work identity and merging", "Coverage monitor and layer health" (without mails), "Route
backfill" and the Magtech route. M6b (admins, registry, discovery, registration, classification, sampling, ceased
journals, layer mails) and M6c (crosslinks, languages, mentionedIn, abstract fallbacks, KU route) follow in their own
plans.

## Global Constraints

- Work and commit directly on `main` (single session; `.claude/settings.local.json` has `worktree.bgIsolation: none`).
  Commit messages carry no Claude attribution of any kind (global CLAUDE.md).
- Flyway migrations are expand-only, starting at `V16__…`; only the indexer migrates, `web` validates.
- No paid service is called. OpenAlex stays within its free allowance (existing `OpenAlexBudget`).
- Every external call goes through `PoliteHttp` (user agent `Garrulus/<version> (+https://www.catalogueoflife.org)`)
  and its per-host `HostLimiter`; OAI hosts default to one request every 2 s.
- Every task handler is idempotent under at-least-once delivery; an outage defers (`HarvestOutage` / 
  `ServiceOutageException`) and never uses an attempt.
- All new crons are UTC, gated by `garrulus.harvest.enabled` like the existing ones; each layer also has
  `garrulus.layers.<code>.enabled` (default true).
- Priorities: daily layers `LIVE`, onboarding passes `REPROCESS`, route backfill `BACKFILL`.
- Alerts follow ADR-0006 unchanged (the 90-day window decides, not the origin); a record published before 2000 is
  skipped by every layer and counted.
- `web` makes no outbound calls; anything that needs one is a task for the indexer.
- Commands: `JAVA_HOME=~/.sdkman/candidates/java/25.0.3-librca`; `mvn -B -pl <module> -am verify -Dit.test=<IT>` for
  one IT; native gnfinder is not needed by this plan.

## Review Focus

1. **A "feed" URL that answers 200 with an HTML error page** (Acta Entomologica Sinica's Magtech feeds do exactly
   that): the route must go `BROKEN` with a clear error, not fail every night with a parse exception. → Task 9.
2. **OAI records marked `<header status="deleted">`** must never create or change a work. → Task 8.
3. **An expired or rejected resumption token** (`badResumptionToken`) must restart the job from its `from` date
   instead of ending `DEAD`. → Task 8.
4. **The same DOI in different spellings across layers** (`https://doi.org/10.1/X`, `doi:10.1/x`, `10.1/X`) must land
   on one work. → Task 2 and Task 4.
5. **Titles with markup or entities** (`<i>Aus bus</i> sp. nov.`, `&amp;`, MathML in OAI and Magtech) must be cleaned
   before the title match, so the same paper from two layers matches. → Task 4.

---

### Task 1: V16 schema for identity, sightings, routes and layer health

**Files:**
- Create: `store/src/main/resources/db/migration/V16__layered_harvest.sql`
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/ident/{Identifier,WorkIdentifierMapper,WorkSightingMapper}.java`,
  `store/src/main/resources/org/catalogueoflife/garrulus/store/ident/{WorkIdentifierMapper,WorkSightingMapper}.xml`
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/route/{SourceRoute,SourceRouteMapper}.java` + XML
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/layer/{LayerHealth,LayerHealthMapper,LayerUnmatchedMapper}.java` + XML
- Modify: `store/src/main/java/org/catalogueoflife/garrulus/store/work/Work.java` (add `metaLayer`, `abstractJatsSource`),
  `store/src/main/resources/org/catalogueoflife/garrulus/store/work/WorkMapper.xml` (result map and insert)
- Test: `store/src/test/java/org/catalogueoflife/garrulus/store/ident/IdentityMappersIT.java`,
  `store/src/test/java/org/catalogueoflife/garrulus/store/route/SourceRouteMapperIT.java`

**Interfaces:**
- Produces:
  - `record Identifier(String scheme, String value)`
  - `WorkIdentifierMapper`: `int insertAll(long workId, List<Identifier> ids, String layer)` (ON CONFLICT DO NOTHING),
    `List<Long> findWorkIds(List<Identifier> ids)` (distinct, ordered), `List<Identifier> list(long workId)`,
    `int moveAll(long fromWork, long toWork)`
  - `WorkSightingMapper`: `int sight(long workId, String layer, @Nullable Long sourceId)` (ON CONFLICT DO NOTHING),
    `int moveAll(long fromWork, long toWork)`
  - `record SourceRoute(long id, long sourceId, String layer, String config /*json*/, String status, String origin,
    @Nullable Instant verifiedAt, @Nullable Instant lastOk, @Nullable String lastError, @Nullable Instant onboardedAt,
    @Nullable Instant backfilledAt, Instant created)`; status `PENDING|VERIFIED|ACTIVE|PAUSED|BROKEN`, origin
    `SEED|DISCOVERED|ADMIN|REGISTERED`
  - `SourceRouteMapper`: `long insert(SourceRoute)` (unique on source, layer, config), `SourceRoute get(long)`,
    `List<SourceRoute> forSource(long sourceId)`, `List<SourceRoute> listByStatus(@Nullable String layer, String status, int limit)`,
    `int setStatus(long id, String status, @Nullable String error)`, `int setVerified(long id, Instant)`,
    `int markOk(long id, Instant)`, `int setOnboarded(long id, Instant)`, `int setBackfilled(long id, Instant)`
  - `record LayerHealth(String layer, LocalDate day, String state, int failedRuns, int yield, double median,
    double missShare)`; `LayerHealthMapper.upsert(LayerHealth)`, `latest(int days)`, `upsertCoverage(long sourceId,
    String layer, int sightings28d, int misses)`
  - `LayerUnmatchedMapper.record(String layer, String doi, @Nullable String issn, @Nullable String containerTitle)`
  - `Work` gains `metaLayer` (String, nullable) and `abstractJatsSource` (String, nullable) at the end of the record.

- [ ] **Step 1: Write the migration** `V16__layered_harvest.sql`:
  - `work_identifier(work_id bigint FK ON DELETE CASCADE, scheme text, value text, first_layer text, first_seen
    timestamptz default now(), UNIQUE(scheme, value))`, index on `work_id`.
  - `work_sighting(work_id FK ON DELETE CASCADE, layer text, source_id bigint, first_seen timestamptz default now(),
    PRIMARY KEY(work_id, layer))`, index on `(layer, first_seen)`.
  - `source_route(…)` as in Interfaces, `config jsonb not null`, CHECKs on `status` and `origin`, `UNIQUE(source_id,
    layer, config)`.
  - `layer_health(layer, day, state CHECK IN ('OK','DEGRADED','FAILING'), failed_runs, yield, median, miss_share,
    PRIMARY KEY(layer, day))`; `source_layer_coverage(source_id, layer, sightings_28d, misses, updated, PRIMARY
    KEY(source_id, layer))`; `layer_unmatched(layer, doi PRIMARY KEY, issn, container_title, first_seen)`.
  - `ALTER TABLE work ADD meta_layer text, ADD abstract_jats_source text`; `UPDATE work SET abstract_jats_source =
    'CROSSREF' WHERE abstract_jats IS NOT NULL`.
  - Drop the V1 check `work_check` (`openalex_id IS NOT NULL OR doi IS NOT NULL`; look up its generated name with
    `\d work` first) and extend the `abstract_source` CHECK with `'EUROPEPMC','OAI','MAGTECH'`.
  - Back-fill identifiers: `INSERT INTO work_identifier SELECT id,'DOI',doi,'CROSSREF' FROM work WHERE doi IS NOT
    NULL`, the same for `OPENALEX` (layer `OPENALEX`) and `PMCID` (layer `EUROPEPMC`); sightings: `OPENALEX` for works
    with an OpenAlex id, `CROSSREF` for works with a DOI and no OpenAlex id.

- [ ] **Step 2: Write `IdentityMappersIT`**: `insertAllIsIdempotent` (inserting the same two ids twice yields 2 rows),
  `findWorkIdsReturnsEveryHolder` (ids of two works → both ids, sorted), `moveAllMovesIdentifiersAndSightings`,
  `migrationBackfilledExistingWorks` (a work inserted before via plain SQL with doi and openalex_id has 2 identifier rows
  after `flyway.migrate()` in the test base — use the store IT base which migrates).
  And `SourceRouteMapperIT`: `insertAndListByStatus`, `setStatusOverwritesError`, `uniquePerSourceLayerConfig`.

- [ ] **Step 3: Run** `mvn -B -pl store -am verify -Dit.test='IdentityMappersIT,SourceRouteMapperIT'` → FAIL (no tables).

- [ ] **Step 4: Implement the mappers and records** (MyBatis XML in the style of `WorkMapper.xml`; arrays of ids as
  `= ANY(?)` via the existing list type handlers).

- [ ] **Step 5: Run the two ITs, then `mvn -B -pl store -am verify`** → all PASS (existing `WorkUpsertIT`,
  `MapperIT` still pass with the two new `Work` fields null).

- [ ] **Step 6: Commit** `feat(store): V16 identity, sightings, routes and layer health tables`

---

### Task 2: identifier schemes and the `Layer` enum

**Files:**
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/ident/IdScheme.java`
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/work/Layer.java`
- Test: `store/src/test/java/org/catalogueoflife/garrulus/store/ident/IdSchemeTest.java`, `.../work/LayerTest.java`

**Interfaces:**
- Produces:
  - `final class IdScheme` with constants `DOI, OPENALEX, OAI, PMID, PMCID, BLR, MAGTECH` (record-like: `code()`,
    `normalise(String raw) -> @Nullable String`), `static IdScheme of(String code)`, `static @Nullable Identifier
    id(IdScheme scheme, @Nullable String raw)` (null when it does not normalise). M6c adds label, URL template and
    entity to this same class.
  - `enum Layer { CROSSREF, OPENALEX, EUROPEPMC, OAI, MAGTECH, BLR }` with `int biblioRank()` (0 best: CROSSREF 0,
    OPENALEX 1, EUROPEPMC 2, OAI 3, MAGTECH 3, BLR 4), `int accessRank()` (OPENALEX 0, CROSSREF 1, OAI 2, MAGTECH 2,
    EUROPEPMC 3, BLR 4), `String adapter()` (harvest_run value: `OPENALEX_LIVE` is kept for L1, others = name),
    `String enabledProperty()` (`garrulus.layers.<lower-case name>.enabled`).

- [ ] **Step 1: Write `IdSchemeTest`**:
```java
assertThat(IdScheme.DOI.normalise("https://doi.org/10.11646/ZOOTAXA.5432.1.1")).isEqualTo("10.11646/zootaxa.5432.1.1");
assertThat(IdScheme.DOI.normalise("doi:10.1/X")).isEqualTo("10.1/x");
assertThat(IdScheme.DOI.normalise("not a doi")).isNull();
assertThat(IdScheme.OPENALEX.normalise("https://openalex.org/W123")).isEqualTo("W123");
assertThat(IdScheme.PMCID.normalise("9836452")).isEqualTo("PMC9836452");
assertThat(IdScheme.PMID.normalise("PMID:12345")).isEqualTo("12345");
assertThat(IdScheme.MAGTECH.normalise("https://www.biodiversity-science.net/EN/abstract/abstract93333.shtml"))
    .isEqualTo("www.biodiversity-science.net:93333");
assertThat(IdScheme.BLR.normalise("https://zenodo.org/records/1234567")).isEqualTo("1234567");
assertThat(IdScheme.OAI.normalise(" oai:zookeys.pensoft.net:12345 ")).isEqualTo("oai:zookeys.pensoft.net:12345");
```
  `LayerTest`: `crossrefOwnsBibliographicFieldsOverOpenalex` (`CROSSREF.biblioRank() < OPENALEX.biblioRank()`),
  `openalexOwnsAccessFields`, `adapterNames` (`OPENALEX.adapter()` = `OPENALEX_LIVE`).

- [ ] **Step 2: Run** `mvn -B -pl store test -Dtest='IdSchemeTest,LayerTest'` → FAIL.
- [ ] **Step 3: Implement.** `DOI` delegates to `WorkUpsert.normalizeDoi` and then requires `^10\.\d{4,9}/\S+$`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(store): identifier schemes and harvest layers with precedence ranks`

---

### Task 3: `WorkMerger`, the per-field precedence

**Files:**
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/work/WorkMerger.java`
- Test: `store/src/test/java/org/catalogueoflife/garrulus/store/work/WorkMergerTest.java`

**Interfaces:**
- Consumes: `Layer` (Task 2), `Work` with `metaLayer`, `abstractJatsSource` (Task 1).
- Produces: `static Merged merge(Work stored, Work incoming, Layer incomingLayer)`;
  `record Merged(Work work, boolean changed)`.

Rules (spec "Merge rule, per field by layer precedence"):
- bibliographic fields (`title, authors, volume, issue, pages, publicationDate, year, type`): incoming overwrites when
  `incomingLayer.biblioRank() <= rank(stored.metaLayer)` (a null `metaLayer` ranks worst); otherwise only fills nulls.
  The winning layer becomes `metaLayer`.
- access fields (`oaStatus, license, bestOaUrl`): the same with `accessRank()`.
- `title` never changes once `stored.state()` is `NAMES_FOUND`, `MATCHED`, `INDEXED` or `NOTIFIED` (the finder has
  named it; `CLEAN_TITLES` keeps its own path).
- abstract: `abstractJats` kinds rank `CROSSREF 0 < EUROPEPMC 1 = MAGTECH 1 = OAI 1`; plain `abstractText` ranks below
  any JATS; an inverted-index abstract (`abstractSource = OPENALEX_INVERTED`) below a plain one. Replace only by a
  better or equal kind, never by an empty value.
- `openalexId`, `doi`, `pmcid`, `sourceId`, `language`: fill nulls only. `origin, firstSeenAt, state, textLevel` never.

- [ ] **Step 1: Write `WorkMergerTest`** with one test per rule: `crossrefTitleBeatsOpenalex`,
  `oaiOnlyFillsNullsUnderCrossref`, `openalexLicenceBeatsCrossref`, `titleFrozenAfterNaming`,
  `jatsAbstractNotReplacedByPlain`, `emptyNeverReplaces`, `originAndStateNeverChange`, `unchangedWhenNothingNew`
  (`changed` false).
- [ ] **Step 2: Run** `mvn -B -pl store test -Dtest=WorkMergerTest` → FAIL.
- [ ] **Step 3: Implement** as a pure function (no I/O).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(store): per-field layer precedence for merging work records`

---

### Task 4: `WorkUpsert` on identifiers, title match and sightings

**Files:**
- Modify: `store/src/main/java/org/catalogueoflife/garrulus/store/work/WorkUpsert.java`
- Modify: `store/src/main/resources/org/catalogueoflife/garrulus/store/work/WorkMapper.xml` (add `findForUpdate`,
  `updateMerged`, `findByTitleMatch`)
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/work/TitleKey.java`
- Test: `store/src/test/java/org/catalogueoflife/garrulus/store/work/WorkUpsertIT.java` (extend),
  `.../TitleKeyTest.java`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `UpsertResult upsert(Work in, Layer layer, List<Identifier> ids)` replacing `upsert(Work)`;
    `record UpsertResult(long id, boolean created, boolean abstractJatsAdded, @Nullable Long duplicateOf)` —
    `duplicateOf` is a second work that holds one of the incoming identifiers (the caller enqueues `MERGE_WORKS`).
  - `Optional<Long> insertIfNew(Work in, Layer layer, List<Identifier> ids)` (backfill).
  - `TitleKey.of(String title) -> @Nullable String` (cleaned by `TitleText.clean`, NFKC, case-folded, punctuation and
    whitespace collapsed; null for fewer than 4 words or a generic title: erratum, corrigendum, correction, editorial,
    book review, obituary, index, contents, in memoriam, and their German, French, Spanish, Portuguese, Russian forms).
- Matching order: `DOI` → `OPENALEX` → any other incoming identifier → title match (same `source_id`, same `year`,
  same `TitleKey`, same first-author family name when both have authors; never between two different DOIs).
  Every upsert then writes the incoming identifiers (`insertAll`) and one sighting for `layer`.
- The upsert runs in the caller's transaction and locks the found row (`findForUpdate`) before `WorkMerger.merge`;
  `updateMerged` writes all mergeable columns and `modified = now()`.

- [ ] **Step 1: Write the tests.** `TitleKeyTest`: `cleansMarkupAndEntities` (`"<i>Aus bus</i> sp. nov. &amp; allies"`
  and `"Aus bus sp. nov. & allies"` give the same key), `shortAndGenericTitlesHaveNoKey` (`"Erratum"`, `"Corrigendum to
  …"` start, three-word titles → null). `WorkUpsertIT` new cases:
  - `doiSpellingsLandOnOneWork` (Review Focus 4: three records with the DOI spelt three ways → one work, one DOI row);
  - `oaiRecordWithoutDoiJoinsWorkByTitle` and `doiRecordJoinsDoiLessWorkByTitle` (both directions);
  - `titleMatchNeverJoinsDifferentDois`; `genericTitlesNeverMatch`;
  - `pmcidMatchesWorkFirstSeenByDoi`;
  - `sightingPerLayerOnce` (two OAI upserts → one OAI sighting);
  - `duplicateOfReportedWhenIdsPointToTwoWorks`;
  - `crossrefOverwritesOaiTitleButOaiDoesNotOverwriteCrossref`.
- [ ] **Step 2: Run** `mvn -B -pl store -am verify -Dit.test=WorkUpsertIT` and the unit test → FAIL.
- [ ] **Step 3: Implement**; keep the 5-try race loop; `insertIfAbsent` still guards the unique columns.
- [ ] **Step 4: Run** → PASS; then `mvn -B -pl store -am verify` → PASS.
- [ ] **Step 5: Commit** `feat(store): work upsert matches on identifiers and titles, records sightings`

---

### Task 5: existing layers write identity, Crossref stops waiting on OpenAlex

**Files:**
- Modify: `indexer/.../harvest/WorksPager.java` (`store(Work, TaskPriority)` → `store(Work, Layer, List<Identifier>,
  TaskPriority)`; enqueue `MERGE_WORKS` on `duplicateOf`),
  `.../harvest/CrossrefHarvestHandler.java`, `.../harvest/OpenAlexRecheckHandler.java`,
  `.../backfill/BackfillFileHandler.java` (or the class calling `insertIfNew`), `.../openalex/WorkRecord.java`
  (`List<Identifier> identifiers()`), `.../crossref/CrossrefWorks.java`
- Test: `indexer/src/test/java/.../harvest/CrossrefHarvestHandlerIT.java`, `.../OpenAlexHarvestHandlerIT.java` (extend)

**Interfaces:**
- Consumes: `WorkUpsert.upsert(Work, Layer, List<Identifier>)`, `IdScheme.id(...)`.
- Produces: `WorkRecord.identifiers() -> List<Identifier>` (DOI, OPENALEX, PMCID from the record);
  `WorksPager.store(Work work, Layer layer, List<Identifier> ids, TaskPriority contentPriority) -> boolean created`;
  constant `WorksPager.MERGE_WORKS = "MERGE_WORKS"` (the handler comes in Task 6 and uses it as its type), payload
  `{"survivor":a,"other":b}` with `survivor = min(a,b)`, dedup key `merge:<min>:<max>`.

Changes:
- OpenAlex pages store with `Layer.OPENALEX`; Crossref with `Layer.CROSSREF`; recheck with `Layer.OPENALEX`; the
  snapshot backfill with `Layer.OPENALEX`.
- Crossref unknown DOI: the OpenAlex lookup stays an enrichment, but an OpenAlex **outage** (`HarvestOutage.retryable`
  true for `api.openalex.org`) or a budget stop no longer defers the Crossref page: the work is stored from Crossref
  metadata and `OPENALEX_RECHECK` enriches it later.
- Age rule in Crossref: an item published before 2000 is skipped and counted on the run (`runs.noteError` is not
  used; add `skipped` to the progress line in the log).

- [ ] **Step 1: Extend the ITs**: `crossrefStoresWorkWhenOpenalexIsDown` (WireMock returns 503 for `/works/doi:*`,
  the Crossref page completes `DONE`, the work exists with origin `LIVE`, identifiers DOI only, sighting CROSSREF);
  `crossrefSkipsPre2000Items` (fixture item published 1998 → no work); `openalexHarvestWritesIdentifiersAndSighting`
  (W id and DOI rows, sighting OPENALEX).
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='CrossrefHarvestHandlerIT,OpenAlexHarvestHandlerIT'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** those ITs, then `mvn -B -pl indexer -am verify` (all existing harvest, content and backfill ITs
  must stay green) → PASS.
- [ ] **Step 5: Commit** `feat(indexer): OpenAlex and Crossref write identifiers and sightings; Crossref no longer waits on OpenAlex`

---

### Task 6: `MERGE_WORKS` for late duplicates

**Files:**
- Create: `indexer/.../work/MergeWorksHandler.java`
- Modify: `store/.../work/WorkMapper.xml` (`moveContents`, `deleteWork`), hit mapper XML (`moveHits` with
  duplicate drop), `store/.../ident/*Mapper` (`moveAll` from Task 1)
- Test: `indexer/src/test/java/.../work/MergeWorksHandlerIT.java`

**Interfaces:**
- Consumes: `WorkIdentifierMapper.moveAll`, `WorkSightingMapper.moveAll`, `WorkMerger.merge`, `TaskQueue.enqueue`.
- Produces: `MergeWorksHandler implements TaskHandler`, `type()` = `WorksPager.MERGE_WORKS`.

Behaviour (spec "Late duplicates"): in one transaction, lock both rows; merge the younger into the older with
`WorkMerger` (younger's `metaLayer` as its layer); move identifiers, sightings, `content` rows (skip a route the
survivor already has), and `hit` rows whose (subscription, kind) the survivor lacks; delete the younger work (mentions
cascade); if the survivor's document source changed (it had no content row of the moved route), enqueue `FIND_NAMES
{"workId":survivor,"force":true}` at `REPROCESS`. A missing work (already merged) is a no-op. Log one INFO line per merge.

- [ ] **Step 1: Write `MergeWorksHandlerIT`**: `mergesIntoOlderAndMovesIdentity`, `keepsSurvivorHitsAndDropsDuplicates`
  (both have an ARTICLE hit for the same subscription → one remains), `secondDeliveryIsNoOp`,
  `enqueuesFindNamesWhenContentMoved`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test=MergeWorksHandlerIT` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): merge late duplicate works`

---

### Task 7: the layer framework

**Files:**
- Create in `indexer/.../layer/`: `HarvestedWork.java`, `LayerMode.java`, `LayerJob.java`, `LayerPage.java`,
  `HarvestLayer.java`, `LayerWriter.java`, `LayerHarvestHandler.java`
- Modify: `indexer/.../harvest/HarvestOutage.java` (`API_HOSTS` += `www.ebi.ac.uk`, `zenodo.org`),
  `indexer/.../http/PoliteHttp.java` (`void setMinInterval(String host, Duration)` for runtime overrides)
- Test: `indexer/src/test/java/.../layer/LayerWriterIT.java`, `.../layer/LayerHarvestHandlerIT.java` (with a fake layer)

**Interfaces:**
- Consumes: `WorksPager.store` semantics (Task 5) — `LayerWriter` takes over that role for new layers.
- Produces:
  - `record HarvestedWork(Work work, Layer layer, List<Identifier> ids)` (`work.sourceId()` set by the layer)
  - `enum LayerMode { LIVE, ONBOARDING, BACKFILL }`
  - `record LayerJob(Layer layer, String scope, @Nullable Long routeId, List<Long> sourceIds, LocalDate from,
    @Nullable LocalDate until, LayerMode mode, long runId)` — `scope` is `r<routeId>` or `s<sourceId>` and goes into
    the dedup key
  - `record LayerPage(List<HarvestedWork> works, @Nullable String nextPosition, int calls, int skipped)`
  - `interface HarvestLayer { Layer layer(); LayerPage page(LayerJob job, @Nullable String position) throws
    IOException; }` (`position == null` is the first page; a thrown `HttpProblem` is classified by `HarvestOutage`)
  - `LayerWriter.store(HarvestedWork hw, LayerMode mode, TaskPriority contentPriority) -> Stored`;
    `enum Stored { CREATED, MERGED, SKIPPED_PRE_2000 }`. Origin: `ONBOARDING` mode → `ONBOARDING`; `BACKFILL` →
    `BACKFILL`; `LIVE` → `LIVE` when published within 120 days (or no date), else `BACKFILL`. Enqueues
    `RESOLVE_CONTENT` on create (key `content:<id>`), `MERGE_WORKS` on `duplicateOf`.
  - `abstract class LayerHarvestHandler implements TaskHandler` with `protected abstract HarvestLayer layer()`;
    payload `{scope, routeId, sourceIds[], from, until, mode, position, runId}`; dedup key
    `harvest:<layer>:<scope>:<mode>:<from>:<pos>` (pos = `start` or 12 hex of SHA-1 of the position); writes
    `harvest_run` (`adapter = layer.adapter()`, plus `_ONBOARDING`/`_BACKFILL` suffix for those modes); outage →
    run `DEFERRED`, same page re-enqueued with `HarvestOutage.key`; on the last page calls `protected void
    lastPage(LayerJob job)` (default: `SourceRouteMapper.markOk`; Task 12 overrides it for onboarding and backfill).
    `enabled()` reads `layer.enabledProperty()`.

- [ ] **Step 1: Write the ITs** with a fake `HarvestLayer` returning two pages: `walksPagesAndFinishesRunDone`,
  `outageDefersSamePageWithoutAttempt`, `redeliveredPageCreatesNoDuplicates`, `liveModeOldPaperGetsBackfillOrigin`,
  `pre2000Skipped`, `disabledLayerLeavesTasksQueued`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='LayerWriterIT,LayerHarvestHandlerIT'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): harvest layer framework with runs, continuations and the age rule`

---

### Task 8: OAI-PMH layer

**Files:**
- Create in `indexer/.../layer/oai/`: `OaiPmhClient.java`, `OaiRecord.java`, `OaiRecordMapper.java`, `OaiLayer.java`,
  `HarvestOaiHandler.java`
- Create fixtures `indexer/src/test/resources/oai/`: `pensoft-listrecords-oai_dc.xml`, `jott-listrecords-jats.xml`,
  `magnolia-listrecords-oai_dc.xml`, `identify.xml`, `deleted-record.xml`, `bad-resumption-token.xml` — record the first
  three with curl from the live endpoints of the spec (`https://zookeys.pensoft.net/oai.php`,
  `https://threatenedtaxa.org/index.php/JoTT/oai`, `https://www.mapress.com/zt/oai`), trimmed to 3 records each.
- Test: `indexer/src/test/java/.../layer/oai/{OaiPmhClientTest,OaiRecordMapperTest,OaiLayerIT}.java`

**Interfaces:**
- Consumes: Task 7.
- Produces:
  - `OaiPmhClient(PoliteHttp)`: `OaiPage listRecords(URI base, String prefix, @Nullable String set, @Nullable
    LocalDate from, @Nullable LocalDate until, @Nullable String token)`; `record OaiPage(List<OaiRecord> records,
    @Nullable String token)`; `Identify identify(URI base)`; `List<String> listSets(URI base)`;
    `class BadResumptionTokenException extends RuntimeException`
  - `record OaiRecord(String identifier, boolean deleted, Instant datestamp, List<String> sets, @Nullable Element
    metadata, String prefix)`
  - `OaiRecordMapper.toHarvested(OaiRecord r, long sourceId) -> Optional<HarvestedWork>` for `oai_dc` and `jats`
    (empty for deleted records and for records without a title)
  - route config JSON for `OAI`: `{"base":"…","set":"…|null","prefix":"oai_dc|jats","fullReadWeekly":false,
    "issns":["…"]}`; `OaiLayer` serves one route per job (`scope = r<routeId>`).
- Mapping: `dc:title` (first; others with `xml:lang` are kept for M6c in the record's raw), `dc:creator` → authors
  JSON, `dc:date` → publication date/year, `dc:identifier` → the first DOI-shaped value (`10.…` or doi.org URL) as
  `DOI`, the article URL ignored, `dc:description` → abstract (plain, kind OAI), `dc:rights` → licence when it is a
  URL; `dc:language` kept in `work.language`. JATS: `article-title`, `contrib`, `pub-date`, `article-id
  pub-id-type=doi`, `abstract` (as JATS, `abstractJatsSource = OAI`), `license/@xlink:href`. OAI identifier always as
  `OAI` id.
- `fullReadWeekly` routes (unreliable datestamps: MNHN, AJOL, DergiPark) ignore `from` and run only on Sundays.
- Politeness: `OaiLayer` registers 2 s for the route's host via `PoliteHttp.setMinInterval` unless a property
  `garrulus.http.min-interval.<host>` sets another value.

- [ ] **Step 1: Write the tests.** `OaiPmhClientTest` (WireMock): `followsResumptionToken`,
  `badResumptionTokenThrowsTyped` (Review Focus 3), `identifyParses`. `OaiRecordMapperTest`: `pensoftOaiDcHasDoiAbstractAuthors`,
  `jottJatsKeepsItalicAbstractAndLicence`, `magnoliaWithoutRightsHasNoLicence`, `deletedRecordMapsToEmpty` (Review Focus
  2), `firstDoiShapedIdentifierWins` (a record with handle, URL and doi.org identifiers). `OaiLayerIT`:
  `harvestsRouteIntoWorksWithOaiSightings`, `badResumptionTokenRestartsFromFrom` (the handler re-enqueues position
  `null` for the same `from`; the task ends DONE, not DEAD), `fullReadRouteSkipsWeekdays`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test=OaiLayerIT` and `-Dtest='OaiPmhClientTest,OaiRecordMapperTest'` → FAIL.
- [ ] **Step 3: Implement** (`javax.xml` DOM with namespace awareness; no new dependency).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): OAI-PMH harvest layer`

---

### Task 9: Magtech RSS route

**Files:**
- Create in `indexer/.../layer/magtech/`: `MagtechClient.java`, `MagtechLayer.java`, `HarvestMagtechHandler.java`,
  `MagtechAbstracts.java`
- Fixtures `indexer/src/test/resources/magtech/`: `biodiv-sci-dqml.xml` (record from
  `https://www.biodiversity-science.net/EN/rss_dqml_1005-0094.xml`, trimmed to 3 items), `article-93333.html` (the head
  of one article page), `html-404-page.html` (the 200-status error page seen on insect.org.cn)
- Test: `indexer/src/test/java/.../layer/magtech/{MagtechClientTest,MagtechAbstractsTest,MagtechLayerIT}.java`

**Interfaces:**
- Consumes: Task 7, `IdScheme.MAGTECH`.
- Produces:
  - `MagtechClient(PoliteHttp)`: `List<MagtechItem> feed(URI feed)` (throws `NotAFeedException` when the body is not
    RSS), `ArticleMeta articleMeta(URI page)`; `record MagtechItem(String title, URI link, String descriptionHtml,
    String authors, @Nullable String pubDate)`; `record ArticleMeta(@Nullable String doi, @Nullable LocalDate date,
    @Nullable String language)` from `citation_doi`, `citation_publication_date` (or `citation_date`),
    `citation_language`
  - `MagtechAbstracts.toJats(String html) -> String` (`<p>` → `<jats:p>`, `<i>/<em>` → `<jats:italic>`, `<sub>/<sup>`
    kept as JATS, `<strong>` text kept, other tags stripped, entities decoded)
  - route config `{"site":"https://www.biodiversity-science.net","issn":"1005-0094"}`; feeds polled: `rss_dqml` and
    `rss_first`
- Per new item (no `MAGTECH` identifier stored yet): one `articleMeta` request; DOI → `DOI` id, `MAGTECH` id from the
  link, date from the page when the feed's `pubDate` is missing or older than the page date. Known items cost no page
  request.

- [ ] **Step 1: Write the tests.** `MagtechClientTest`: `parsesTwentyItemFeed` (3 items in the trimmed fixture, title
  keeps `<i>`), `htmlErrorPageIsNotAFeed` (Review Focus 1), `readsCitationMeta`. `MagtechAbstractsTest`:
  `italicsBecomeJatsItalic`, `strongLabelKeptAsText`. `MagtechLayerIT`: `newItemsBecomeWorksWithDoiAndMagtechId`,
  `knownItemsCauseNoPageRequest` (WireMock verifies zero page requests on the second run), `notAFeedMarksRouteBroken`
  (route status `BROKEN`, error text names the URL, task DONE).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): Magtech RSS route for Chinese journals`

---

### Task 10: Europe PMC layer and its probe

**Files:**
- Create in `indexer/.../layer/europepmc/`: `EuropePmcSearchClient.java`, `EuropePmcLayer.java`,
  `HarvestEuropePmcHandler.java`, `ProbeEuropePmcHandler.java`
- Fixtures `indexer/src/test/resources/europepmc/`: `search-core-issn.json` (record a `resultType=core` search for
  ZooKeys `ISSN:"1313-2989"`, trimmed to 3 results with `nextCursorMark`), `search-core-empty.json`
- Test: `indexer/src/test/java/.../layer/europepmc/{EuropePmcSearchClientTest,EuropePmcLayerIT}.java`

**Interfaces:**
- Produces: `EuropePmcSearchClient(PoliteHttp, String baseUrl)`: `EpmcPage search(String query, String cursorMark)`
  (`resultType=core`, `pageSize=100`, `format=json`), `record EpmcPage(List<JsonNode> results, @Nullable String
  nextCursor, int hitCount)`; `boolean hasJournal(List<String> issns)`; `EuropePmcLayer` query
  `(ISSN:"a" OR ISSN:"b") AND FIRST_IDATE:[from TO until]`; mapping: `title`, `authorList`, `journalInfo`
  (volume/issue/ISSN), `pageInfo`, `firstPublicationDate`, `abstractText` (as JATS kind `EUROPEPMC`), `language`,
  `doi`/`pmid`/`pmcid` → identifiers.
- `ProbeEuropePmcHandler` (`TYPE = "PROBE_EUROPEPMC"`, payload `{"sourceId":n}` or `{}` for all harvestable sources
  without a `EUROPEPMC` route): adds an `ACTIVE` route `{"issns":[…]}` with origin `DISCOVERED` when `hasJournal`.
  It is enqueued monthly and after a source is onboarded.

- [ ] **Step 1: Tests**: `searchFollowsCursorUntilRepeat`, `mapsAbstractAndIdentifiers`, `probeAddsRouteOnlyOnHits`,
  `harvestCreatesWorksWithPmidPmcidAndSighting`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): Europe PMC harvest layer and journal probe`

---

### Task 11: BLR layer (Plazi on Zenodo)

**Files:**
- Create in `indexer/.../layer/blr/`: `ZenodoClient.java`, `BlrLayer.java`, `HarvestBlrHandler.java`
- Modify: `indexer/.../crossref/CrossrefClient.java` (`Optional<JsonNode> work(String doi)`, free single lookup)
- Fixtures `indexer/src/test/resources/zenodo/`: `biosyslit-page.json` (record
  `https://zenodo.org/api/records?communities=biosyslit&q=created:[2026-11-18 TO *]&sort=newest&size=25`, trimmed
  to 3 records: one treatment with `isPartOf` DOI, one article, one without DOI), `crossref/work-single.json`
- Test: `indexer/src/test/java/.../layer/blr/BlrLayerIT.java`

**Interfaces:**
- Produces: `ZenodoClient(PoliteHttp, String baseUrl)`: `ZenodoPage records(String community, Instant since, int
  page)` (size 25); `BlrLayer` (no route; scope `all`): collects distinct article DOIs (`isPartOf` of treatments, the
  DOI of article records); for a DOI with no work: `CrossrefClient.work(doi)` → ISSN → onboarded source → stored as
  `Layer.BLR` work with `DOI` and `BLR` ids; unknown journal → `LayerUnmatchedMapper.record`; a DOI with a work gets
  only its `BLR` id and a BLR sighting.

- [ ] **Step 1: Tests**: `treatmentDoiOfOnboardedJournalCreatesWork`, `unknownJournalRecordedAsUnmatched`,
  `knownDoiGetsBlrSightingOnly`, `recordsWithoutDoiIgnored`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): Plazi BLR layer from Zenodo`

---

### Task 12: routes — seeding, verification, rollout, onboarding passes and route backfill

**Files:**
- Create in `indexer/.../route/`: `VerifyRouteHandler.java`, `SeedRoutesHandler.java`, `RouteRolloutScheduler.java`,
  `RouteLifecycle.java`, `RouteBackfill.java`; resource `indexer/src/main/resources/routes-seed.tsv`
- Create: `indexer/.../layer/crossref/CrossrefHistoryLayer.java`, `HarvestCrossrefHistoryHandler.java`
- Create: `web/src/main/java/.../web/api/AdminRouteController.java`
- Modify: the four layer handlers (`lastPage` override via a shared `RouteLifecycle` bean)
- Test: `indexer/src/test/java/.../route/{VerifyRouteHandlerIT,RouteLifecycleIT}.java`,
  `web/src/test/java/.../web/api/AdminRouteControllerIT.java`

**Interfaces:**
- Produces:
  - `VERIFY_ROUTE` (`{"routeId":n}`): OAI → `identify` plus the set present in `listSets`; Magtech → feed parses with
    ≥ 1 item; Europe PMC → `hasJournal`. Pass → `VERIFIED` (or `ACTIVE` when the source is not yet onboarded: its
    onboarding will cover it), fail → `BROKEN` with the reason.
  - `SEED_ROUTES` (admin task, `{}`): reads `routes-seed.tsv` (`issn\tlayer\tconfig-json`), matches sources by ISSN,
    inserts routes `PENDING` with origin `SEED`, enqueues `VERIFY_ROUTE` each. The TSV lists: the Pensoft sets
    (`zookeys`, `phytokeys`, `mycokeys`, `bdj`, `arthropod-systematics`, `checklist`, `dez`, `jhr`, `nl` and the rest of
    the 103 sets that match registered ISSNs), Zootaxa, Phytotaxa, EJT, JoTT (`jats`), MNHN Zoosystema/Adansonia/
    Geodiversitas (`fullReadWeekly`), Biodiversity Science (Magtech).
  - `RouteRolloutScheduler` (`${garrulus.layers.rollout-cron:0 30 0 * * *}`, gated): takes up to
    `garrulus.layers.rollout-per-night` (20) `VERIFIED` routes and enqueues their layer task with mode
    `ONBOARDING`, `from = today − 120 d`, priority `REPROCESS`, key `onboard-route:<routeId>`.
  - `RouteLifecycle.lastPage(job)`: `ONBOARDING` → route `ACTIVE`, `onboarded_at`, and, when the source has no
    OpenAlex id, `RouteBackfill.enqueue(sourceId)`; `BACKFILL` → `backfilled_at`.
  - `RouteBackfill.enqueue(long sourceId)`: for each `ACTIVE` route of the source a layer task with mode `BACKFILL`,
    `from = 2000-01-01`, `until = today − 120 d`, priority `BACKFILL`, key `route-backfill:<routeId>`; plus
    `HARVEST_CROSSREF_HISTORY` for the source's ISSNs (`from-pub-date:2000-01-01`, `until-pub-date`) with key
    `route-backfill:crossref:<sourceId>`. Admin entry: `ROUTE_BACKFILL {"sourceId":n}` task type calling the same.
  - `AdminRouteController` (`/api/admin/**`, token as today): `GET /api/admin/sources/{id}/routes`,
    `POST /api/admin/sources/{id}/routes` (`{layer, config}` → `PENDING`, origin `ADMIN`, enqueues `VERIFY_ROUTE`,
    202), `PATCH /api/admin/sources/{id}/routes/{routeId}` (`{"status":"PAUSED"|"ACTIVE"}`).

- [ ] **Step 1: Tests**: `verifyMarksBrokenWithReason`, `seedRoutesMatchesByIssnAndIsIdempotent`,
  `rolloutTakesTwentyPerNight`, `onboardingPassEndsActiveAndBackfillsJournalsWithoutOpenalexId`,
  `backfillWorksGetBackfillOriginAndNoHits` (an `EVALUATE_HITS` run over them inserts no hit: their dates are outside
  the window), `adminPostQueuesVerification` (web IT: 202, route `PENDING`, a `VERIFY_ROUTE` task).
- [ ] **Step 2: Run** the ITs → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: source routes with verification, nightly rollout, onboarding passes and route backfill`

---

### Task 13: schedules and configuration

**Files:**
- Modify: `indexer/.../harvest/HarvestScheduler.java` (or a new `LayerScheduler.java` in `layer/`),
  `indexer/src/main/resources/application.yml`, `.env.example`, `CLAUDE.md`
- Test: `indexer/src/test/java/.../layer/LayerSchedulerTest.java`

**Interfaces:**
- Produces: daily crons, all gated by `garrulus.harvest.enabled` and the layer switch:
  - `garrulus.layers.oai-cron` `0 30 4 * * *` → one `HARVEST_OAI` (mode LIVE) per `ACTIVE` OAI route,
    `from = route.lastOk − 3 d` (or today − 120 d), key `harvest:OAI:r<id>:LIVE:<from>:start`;
  - `garrulus.layers.magtech-cron` `0 45 4 * * *` → `HARVEST_MAGTECH` per `ACTIVE` Magtech route;
  - `garrulus.layers.europepmc-cron` `0 0 5 * * *` → `HARVEST_EUROPEPMC` per route, 14-day window;
  - `garrulus.layers.blr-cron` `0 15 5 * * *` → one `HARVEST_BLR` since the last DONE run (or 2 days);
  - `garrulus.layers.coverage-cron` `0 30 6 * * *` → `COVERAGE_CHECK` (Task 14);
  - monthly `PROBE_EUROPEPMC {}` with the seeding (`0 0 1 1 * *`).
  All keys carry the date, so a re-fire adds nothing.

- [ ] **Step 1: Test** with `Clock.fixed` and `MockEnvironment` like `HarvestSchedulerTest`: `enqueuesOnePerActiveRoute`,
  `skipsWhenHarvestDisabled`, `skipsDisabledLayer`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; document every property in `CLAUDE.md` (Configuration, indexer
  harvest) and `.env.example` (`GARRULUS_LAYERS_*`). **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): nightly schedules for the OAI, Magtech, Europe PMC and BLR layers`

---

### Task 14: coverage monitor and layer health

**Files:**
- Create: `indexer/.../layer/coverage/{LayerHealthCalculator,CoverageCheckHandler}.java`
- Modify: `store/.../layer/LayerHealthMapper.xml` (the per-layer inputs), `web/.../api/AdminStatusController.java`
  (`layers`, `routes` in `Status`)
- Test: `indexer/src/test/java/.../layer/coverage/{LayerHealthCalculatorTest,CoverageCheckHandlerIT}.java`,
  `web/.../api/AdminStatusControllerIT.java` (extend)

**Interfaces:**
- Produces:
  - `LayerHealthCalculator.state(Inputs in) -> String` with `record Inputs(int consecutiveFailedDays, int
    consecutiveZeroYieldDays, int yield7d, double weeklyMedian8w, double missShare, double missShareBaseline,
    boolean deferredRecovered)`; rules: `FAILING` when failed or zero-yield days ≥ 2 (zero yield only counts when the
    median > 0); `DEGRADED` when either ≥ 1, `yield7d < 0.5 × median`, or `missShare > 0.30` with baseline `< 0.10`;
    a `DEFERRED` run completed the next day is not a failure; else `OK`. Thresholds from
    `garrulus.coverage.{fail-days=2, yield-ratio=0.5, miss-share=0.30, miss-baseline=0.10}`.
  - `COVERAGE_CHECK {"day":"yyyy-mm-dd"}`: computes the inputs from `harvest_run`, `work_sighting` (misses: works first
    sighted 3–17 days ago by another layer, for sources the layer serves, never sighted by this layer) and writes
    `layer_health` and `source_layer_coverage`; logs a WARN when a layer turns `FAILING` (mails come with M6b).
  - `Status` gains `List<LayerHealth> layers` (last 7 days) and `Map<String,Map<String,Long>> routes` (layer → status →
    count); the contribution numbers (first-found, only-found per layer, 28 days) as `Map<String,Map<String,Long>>
    contribution`, and the same broken down by the journal's country as `Map<String,Map<String,Map<String,Long>>>
    contributionByCountry` (country → layer → first/only), so Chinese and Indian coverage is visible.

- [ ] **Step 1: Tests**: calculator table test (`oneFailedDayIsDegraded`, `twoFailedDaysFailing`,
  `budgetDeferralRecoveredIsOk`, `missShareJumpDegraded`, `zeroYieldWithZeroMedianIsOk`); `CoverageCheckHandlerIT`
  (`openalexBlockedTwoDaysTurnsFailing` with seeded runs and sightings; `contributionCountsFirstAndOnly`;
  `contributionByCountry`);
  `AdminStatusControllerIT.statusListsLayersAndRoutes`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: nightly coverage check with layer health and contribution in the admin status`

---

### Task 15: dev check on the dev DB

**Files:**
- Create: `docs/m6a-dev-check.md`
- Modify: `CLAUDE.md` (status line, task types of M6a), `docs/adr/0008-layered-harvest.md` (new ADR: layers,
  identity, precedence, routes, coverage; the decisions of the spec that M6a built)

- [ ] **Step 1:** Rebuild `data/run/indexer.jar` from `main`, restart the dev-run indexer with `-Xmx6g` (memory
  `nametracker-m2-open-items`), run `SEED_ROUTES` and wait for `VERIFY_ROUTE` and the first rollout night.
- [ ] **Step 2:** After two nights record per layer: runs, works sighted, works first found, routes by status, merges,
  pre-2000 skips; check that no work was duplicated (`SELECT scheme, value, count(*) FROM work_identifier GROUP BY
  1,2 HAVING count(*) > 1` is empty by construction; also check title-key duplicates per source and year).
- [ ] **Step 3:** Block OpenAlex for two nights (`GARRULUS_OPENALEX_BASE_URL=http://127.0.0.1:9` on the dev-run
  indexer), confirm new works still arrive through the other layers, `layer_health` shows OPENALEX `DEGRADED` then
  `FAILING`, then unblock and confirm `OK` and no new hits for works others brought.
- [ ] **Step 4:** Write `docs/m6a-dev-check.md` with the numbers and the ADR; update `CLAUDE.md`.
- [ ] **Step 5: Commit** `docs: M6a dev check and ADR-0008`
