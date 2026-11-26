# M3 Match & Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every resolved work runs through the name finder, the CLB matcher on the pinned COL XR, placement, code and `TaxGroup`, and salience into `work_name`. The public REST API and the React pages show it. Done when: the Carabidae taxon page shows this week's papers with correct placements.

**Architecture:**
- The indexer gains three task stages after `RESOLVE_CONTENT`:
  - `FIND_NAMES` (finder in-process or remote) writes `mention`, `name_string` and `classification_context`.
  - `MATCH_NAMES` (CLB API, cached in `name_match`) places each name, adds its code and group, and writes `work_name` with salience. The work ends `INDEXED`.
  - A nightly `REBUILD_STATS` writes `name_ubiquity`, `taxon_stats` and the journal categorisation.
- `web` gets the read-only public API with filters and facets, and serves a hand-written OpenAPI file. It never calls external services.
- The frontend gets a router and five pages: search, taxon, name, article and journal.

**Tech Stack:**
- Backend: Spring Boot 4.1, JDK 25, MyBatis, Flyway, Postgres 17 (`pg_trgm`, `tsvector`), ShedLock, Jackson 3 (`tools.jackson`).
- CLB modules from `clb-bom`: `vocab` (`TaxGroup`) and `nomenclature` (`TaxGroupAnalyzer`).
- Tests: WireMock 3.13.2 and Testcontainers.
- Frontend: React 18, Mantine 7.17.8, TanStack Query 5, `react-router-dom` 6.28.0, vitest and MSW.

**Spec:** `docs/design.md`: §1 (journal categorisation), §4 (finder outputs), §5 (matching), §6 (index tables), §7 (salience only), §9 (API and pages), §10, Workflows 4–5, and the Roadmap row for M3. Also the M2 items carried forward, from Markus' memory notes, which are listed in Tasks 1–2.

**Branching:** work directly on `main`, with no branches. This is what Markus asked for.

**Out of M3** (Markus decided on 2026-11-15 that the finder is tuned at the very end): curated tuning and baselines, tuning the salience weights and threshold on the curated main-subject labels, and the `TeiConverter` tokenisation clean-up. They form a final "finder tuning" stage after M3. M3 ships the salience defaults below as configuration. Hits, subscriptions and accounts are M4. XR switching and re-matching are M5.

## Global Constraints

- **Packages and ids:**
  - Packages are `org.catalogueoflife.garrulus.{store,indexer,web}.*`.
  - Outgoing requests carry `User-Agent: Garrulus/<version> (+https://www.catalogueoflife.org)`, through `PoliteHttp`.
- **Who calls what:**
  - Only the `indexer` calls external services (CLB, gnfinder, OpenAlex, Crossref).
  - `web` only reads index tables, the blob store and `taxon_stats`. It writes nothing new in M3.
  - The browser calls the CLB API directly for the taxon picker and the taxon header.
- **CLB:**
  - Base `https://api.checklistbank.org` (`GARRULUS_CLB_BASE_URL`).
  - At most 10 requests a second (`HostLimiter`).
  - Matching uses `GET /dataset/{key}/match/nameusage` with `mode=RELAXED`.
  - The XR key comes from `GET /dataset/3LXR`. On 2026-11-15 that was key 316691, alias "COL26.10 XR".
- **Free by default:** M3 adds no metered service. A CLB outage and a gnfinder outage defer a task by 30 minutes without using up an attempt. Nothing becomes `DEAD` because of an outage.
- **Stored names:** every `mention` points at an exact code-point span of a block of the document the finder saw. There are no invented names, and rejected candidates are not stored.
- **Retention:**
  - Full text that is not open-licensed is never written to the blob store.
  - It may wait in the transient spool (Task 4) until `FIND_NAMES` has run, for at most 14 days.
  - Snippets are at most 200 code points.
  - The API returns the text of a work only when its content is `retained=true`.
- **Schema:** new migrations from `V3__…` onward. They only add, so a running `web` keeps working.
- **Tasks:** delivery is at least once, so every handler is idempotent. Follow-up tasks inherit the priority of the task that enqueued them. The dedup keys are named in each task.
- **Configuration defaults are in `application.yml`:**
  - Names are `nametracker.<area>.<name>`, and the environment variables are listed in `CLAUDE.md`.
  - The salience weights and threshold are configuration, not constants.
- **Tests:**
  - All external HTTP goes through WireMock fixtures under `src/test/resources/<service>/`.
  - CLB fixtures are captured once, by hand, from the live API with the commands given in Task 5.
  - `mvn -B verify` makes no live calls.
- **Never touch local ports 8080/8081.** Manual runs use `SERVER_PORT=18080` and `GARRULUS_INDEXER_PORT=18081`.
- **Commits:** conventional style, files staged by explicit path, and no tool attribution lines (Markus' global rule).

## Review Focus

1. **A work whose full text is not open-licensed.** `FIND_NAMES` reads it from the spool and deletes the spool file afterwards; no blob is written. If the spool file is gone, it falls back to the abstract and records `fallback=abstract` in `finder_run`'s diagnostics. Pinned in Task 4 (`closedFullTextIsReadFromSpoolThenDeleted`, `missingSpoolFallsBackToAbstract`).
2. **CLB or gnfinder is down.** The task is deferred by 30 minutes, keeps its attempt count and never becomes `DEAD`. Pinned in Task 4 (`gnfinderOutageDefersWithoutAttempt`) and Task 6 (`clbOutageDefersWithoutAttempt`).
3. **A work runs through the pipeline twice.** This happens through at-least-once delivery or a content re-check that finds full text. Afterwards there are no duplicate mentions, contexts or `work_name` rows, and names that the new run no longer finds are gone. Pinned in Task 4 (`findTwiceReplacesMentions`) and Task 7 (`indexTwiceReplacesWorkNames`).
4. **A name that appears only in the reference list.** It is never a main subject, and it never makes its work appear on a taxon page, in a facet count or in a `taxon_stats` row. Pinned in Task 7 (`referenceOnlyNameIsNeverMainSubject`), Task 8 (`referenceOnlyNamesDoNotCount`) and Task 10 (`taxonFilterIgnoresReferenceOnlyNames`).
5. **A work that mentions only a synonym** (*Felis leo*). It appears on the page of the accepted taxon and of its ancestors (*Panthera leo*, Felidae). Pinned in Task 5 (`synonymPathStartsWithAcceptedLineage`) and Task 10 (`taxonFilterFindsWorksNamingASynonym`).

---

## File Structure

**store** (`store/src/main/.../store/`):
- `db/migration/V3__maintenance.sql` (Task 2)
- `db/migration/V4__index.sql` (Task 3)
- `blob/BlobStore.java` and `blob/FileBlobStore.java`, moved from the indexer in Task 3
- `index/`: `FinderRun`, `NameString`, `MentionRow`, `ContextRow`, `WorkName`, `NameMatch`, `XrRelease` (records), and `IndexMapper.java` with its `.xml`
- `stats/StatsMapper.java` and `.xml` (Task 8)
- `search/WorkFilter.java`, `search/SearchMapper.java` and `.xml` (Task 10)

**indexer** (`indexer/src/main/java/.../indexer/`):
- `finder/`: `FinderConfig.java`, `DocumentLoader.java`, `Spool.java`, `FindNamesHandler.java`, `FindPendingHandler.java`
- `match/`: `XrReleaseService.java`, `XrCheckHandler.java`, `ClbClient.java`, `ClbMatch.java`, `MatchCache.java`, `Placer.java`, `TaxGroups.java`, `MatchNamesHandler.java`
- `index/`: `Salience.java`, `SalienceProperties.java`, `WorkIndexer.java`
- `stats/`: `StatsScheduler.java`, `RebuildStatsHandler.java`, `SourceCategoriser.java`
- `task/ServiceOutageException.java`

**web** (`web/src/main/java/.../web/`):
- `api/public_/`: `WorkController`, `NameController`, `TaxonController`, `SourceController`, `XrController`, `VocabController`
- `api/public_/dto/` (response records)
- `CorsConfig.java`
- `src/main/resources/static/api/openapi.yaml`

**frontend** (`frontend/src/`):
- `api.ts`, `routes.tsx`
- `pages/`: `SearchPage`, `TaxonPage`, `NamePage`, `WorkPage`, `SourcePage`
- `components/`: `FilterSidebar`, `WorkList`, `YearBars`, `TaxGroupIcon`, `HighlightedText`, `TaxonPicker`

**docs:** `docs/adr/0005-index-pipeline.md`, `CLAUDE.md`, `docs/design.md` (Open items), `docs/m3-dev-check.md`

---

### Task 1: Carried M2 fixes, part 1 — harvest robustness

**Files:**
- Modify (indexer):
  - `harvest/CrossrefHarvestHandler.java`
  - `harvest/OpenAlexHarvestHandler.java`
  - `source/OnboardSourceHandler.java`
  - `harvest/HarvestOutage.java`
  - `http/PoliteHttp.java`
- Modify: `docs/adr/0004-onboarding-and-harvest-windows.md`
- Test: the matching `*Test`/`*IT` classes next to them (`CrossrefHarvestHandlerIT`, `OpenAlexHarvestHandlerIT`, `HarvestOutageTest`)

**Interfaces:**
- Produces:
  - `HarvestOutage.retryable(Throwable)` also returns a problem for `UnknownHostException` when the host is one of the fixed API hosts: `api.openalex.org`, `content.openalex.org`, `api.crossref.org`, `api.checklistbank.org`.
  - Continuation payloads gain `"ids"`: an array of OpenAlex source ids (OpenAlex) or of ISSNs (Crossref).

- [ ] **Step 1: Write failing tests**
  - `crossrefOutageRestartsBatchFromStart`: an outage on a page with cursor `c2` of batch 3 enqueues batch 3 with `cursor=null`, under the dedup key `harvest:crossref:<date>:3:start:r<epochMinute>`. Crossref cursors expire after about 5 minutes.
  - `unknownApiHostIsAnOutage`: `UnknownHostException` for `api.crossref.org` makes `retryable` not null. For `example.org` it stays null, so the failure is non-retryable (commit 58fb3d9).
  - `continuationCarriesItsIds`: the payload of the next page holds the exact ISSNs (or source ids) of the current batch. A run that resumes after a source was added in between asks for the same set (`resumedBatchIgnoresNewSources`).
  - `continuationsKeepLivePriority`: a page of a `LIVE` harvest enqueues its continuation at `LIVE`. An onboarding page enqueues its continuation at `REPROCESS` (code 1, above the `BACKFILL` works it creates), not at `BACKFILL`.
- [ ] **Step 2: Run them and see them fail**
  `mvn -B -pl indexer -am verify -Dit.test='CrossrefHarvestHandlerIT,OpenAlexHarvestHandlerIT' -Dtest=HarvestOutageTest`
- [ ] **Step 3: Implement**
  - When a payload has `ids`, the handler slices by them instead of re-reading the source list. A payload without `ids` (from M2) keeps the old slicing, so tasks already queued in the dev DB still run.
  - Onboarding continuations use `TaskPriority.REPROCESS`. The first onboarding task keeps the priority its caller set.
- [ ] **Step 4: Correct ADR-0004.** A dead Crossref day is retried only while no later day has succeeded.
- [ ] **Step 5: Run the indexer module's tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "fix(indexer): harvest continuations carry their ids, keep priority, and restart Crossref batches after an outage"`

### Task 2: Carried M2 fixes, part 2 — budget, late abstracts, task purge, response cap

**Files:**
- Create: `store/src/main/resources/db/migration/V3__maintenance.sql`
- Modify:
  - `indexer/.../cost/OpenAlexBudget.java`
  - `store/.../usage/ApiUsageMapper.java` and `.xml`
  - `store/.../work/WorkUpsert.java`
  - `indexer/.../http/PoliteHttp.java`
  - `store/.../task/TaskMapper.java` and `.xml`
  - `store/.../task/TaskQueue.java`
  - `indexer/.../harvest/HarvestScheduler.java`
- Test: `OpenAlexBudgetTest`, `WorkUpsertIT`, `PoliteHttpTest`, `TaskQueueIT`

**Interfaces:**
- Produces:
  - `OpenAlexBudget(ApiUsageMapper, BigDecimal maxDailyUsd, BigDecimal reserveUsd, Clock, @Nullable String apiKey)`
  - `TaskQueue.purgeDone(Duration olderThan): int`
  - `PoliteHttp` property `garrulus.http.max-response-bytes` (default `52428800`)
  - `WorkUpsert` reports `abstractJatsAdded`

- [ ] **Step 1: Write V3:**
  ```sql
  ALTER TABLE api_usage ADD COLUMN key_fingerprint text;
  CREATE INDEX task_type_state_idx ON task (type, state);
  CREATE INDEX task_done_finished_idx ON task (finished) WHERE state = 'DONE';
  ```
- [ ] **Step 2: Write failing tests**
  - `remainingFromAnotherKeyIsIgnored`: `remaining_usd=0.0665` stored with fingerprint `none`, and a budget built with a key. `check(CONTENT)` must not throw.
  - `remainingFromSameKeyCounts`: the same stored value under the same key's fingerprint throws.
  - `lateJatsAbstractReResolves`: a work in `CONTENT_RESOLVED` with no `abstract_jats` receives one from Crossref. One `RESOLVE_CONTENT` is enqueued with dedup key `content:<id>:jats`. A second upsert with the same JATS enqueues nothing.
  - `oversizedResponseIsNotRetried`: WireMock serves 60 MB with the cap at 50 MiB. The result is a non-retryable `HttpProblem` whose message contains `"response larger than"`.
  - `purgeDoneKeepsDeadAndRecent`: purging with 14 days deletes only `DONE` rows finished more than 14 days ago.
- [ ] **Step 3: Implement**
  - The key fingerprint is the first 12 hex digits of SHA-256(key), or `none` without a key.
  - `record` stores the fingerprint. `check` ignores a `remaining_usd` whose fingerprint differs from its own.
  - The purge runs inside the existing `HarvestScheduler` at `0 0 5 * * *` UTC, with ShedLock name `task-purge` and 14 days. It runs even when the harvest master switch is off.
- [ ] **Step 4: Run the store and indexer tests, then commit**
  `mvn -B -pl store,indexer -am verify` → green.
  `git commit -m "fix: budget ignores a remaining amount stored under another key, late JATS abstracts re-resolve, task purge and response cap"`

### Task 3: Schema V4 for the index, and the blob store moved to `store`

**Files:**
- Create:
  - `store/src/main/resources/db/migration/V4__index.sql`
  - `store/.../index/*.java`
  - `store/.../index/IndexMapper.xml`
- Move: `indexer/.../content/BlobStore.java` and `FileBlobStore.java` to `store/.../blob/`, updating their imports
- Test: `store/src/test/.../index/IndexMapperIT.java`

**Interfaces:**
- Produces:
  - Records: `FinderRun(Long id, String name, String version, String configHash, @Nullable String dictionaryKey)`, `NameString(Long id, String name, String kind, @Nullable String rank)`, `MentionRow`, `ContextRow`, `WorkName`, `NameMatch`, `XrRelease(int key, String alias, Instant firstSeen, boolean current)`. Their fields are the columns below, in camelCase.
  - `IndexMapper` methods:
    - `long finderRunId(FinderRun)`: upsert, then return the id
    - `long nameStringId(String name, String kind, @Nullable String rank)`: upsert, never changes kind or rank once set
    - `void deleteWorkIndex(long workId)`: deletes mentions, contexts and `work_name`
    - `void insertContext(ContextRow)` (sets its id) and `void insertMentions(List<MentionRow>)`
    - `List<MentionRow> mentions(long workId)` and `List<ContextRow> contexts(long workId)`
    - `@Nullable NameMatch match(long nameStringId, String hintKey, int releaseKey)` and `void putMatch(NameMatch)`
    - `void insertWorkNames(List<WorkName>)` and `List<WorkName> workNames(long workId)`
    - `@Nullable XrRelease currentRelease()` and `void addRelease(int key, String alias, boolean current)`
    - `void setNamesFound(long workId, long finderRunId, String docSha)` and `void setIndexed(long workId, String[] codes, String[] taxgroups, boolean hasActs)`

- [ ] **Step 1: Write V4:**
  ```sql
  CREATE TABLE finder_run (id bigserial PRIMARY KEY, name text NOT NULL, version text NOT NULL, config_hash text NOT NULL,
    dictionary_key text, created timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (name, version, config_hash, dictionary_key));
  ALTER TABLE work ADD COLUMN current_finder_run bigint REFERENCES finder_run, ADD COLUMN names_doc_sha text,
    ADD COLUMN finder_diagnostics jsonb NOT NULL DEFAULT '{}', ADD COLUMN indexed_at timestamptz,
    ADD COLUMN fts tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(abstract,''))) STORED;
  CREATE INDEX work_fts_idx ON work USING gin (fts);
  CREATE TABLE name_string (id bigserial PRIMARY KEY, name text NOT NULL UNIQUE, kind text NOT NULL, rank text,
    created timestamptz NOT NULL DEFAULT now());
  CREATE INDEX name_string_trgm_idx ON name_string USING gin (name gin_trgm_ops);
  CREATE TABLE classification_context (id bigserial PRIMARY KEY, work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
    local_id text NOT NULL, verbatim text NOT NULL, ranks jsonb NOT NULL, scope text NOT NULL, block_id text NOT NULL,
    until_block_id text, UNIQUE (work_id, local_id));
  CREATE TABLE mention (id bigserial PRIMARY KEY, work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
    name_string_id bigint NOT NULL REFERENCES name_string, block_id text NOT NULL, block_type text NOT NULL,
    start_cp integer NOT NULL, end_cp integer NOT NULL, verbatim text NOT NULL, authorship text, qualifier text,
    confidence real NOT NULL, act_type text, context_id bigint REFERENCES classification_context ON DELETE SET NULL,
    snippet text NOT NULL, evidence jsonb NOT NULL DEFAULT '{}');
  CREATE INDEX mention_work_idx ON mention (work_id);
  CREATE INDEX mention_name_idx ON mention (name_string_id);
  CREATE TABLE xr_release (key integer PRIMARY KEY, alias text NOT NULL, first_seen timestamptz NOT NULL DEFAULT now(),
    current boolean NOT NULL DEFAULT false);
  CREATE UNIQUE INDEX xr_release_current_idx ON xr_release (current) WHERE current;
  CREATE TABLE name_match (name_string_id bigint NOT NULL REFERENCES name_string, hint_key text NOT NULL,
    release_key integer NOT NULL REFERENCES xr_release, match_type text NOT NULL, usage_id text, usage_name text,
    usage_rank text, usage_status text, accepted_id text, code text, taxgroup text, taxon_path text[] NOT NULL DEFAULT '{}',
    classification jsonb NOT NULL DEFAULT '[]', matched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (name_string_id, hint_key, release_key));
  CREATE TABLE work_name (work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
    name_string_id bigint NOT NULL REFERENCES name_string, mention_count integer NOT NULL, body_count integer NOT NULL,
    in_title boolean NOT NULL, in_abstract boolean NOT NULL, in_heading boolean NOT NULL, only_reference boolean NOT NULL,
    act_types text[] NOT NULL DEFAULT '{}', best_snippet text NOT NULL,
    placement_method text NOT NULL CHECK (placement_method IN ('MATCH','GENUS','VERBATIM_CONTEXT','NONE')),
    usage_id text, taxon_path text[] NOT NULL DEFAULT '{}', code text, taxgroup text,
    salience real NOT NULL, main_subject boolean NOT NULL, PRIMARY KEY (work_id, name_string_id));
  CREATE INDEX work_name_path_idx ON work_name USING gin (taxon_path);
  CREATE INDEX work_name_name_idx ON work_name (name_string_id);
  CREATE TABLE name_ubiquity (name_string_id bigint PRIMARY KEY REFERENCES name_string, works integer NOT NULL,
    share real NOT NULL, computed_at timestamptz NOT NULL DEFAULT now());
  CREATE TABLE taxon_stats (taxon_id text NOT NULL, year smallint NOT NULL, works integer NOT NULL,
    main_subject_works integer NOT NULL, PRIMARY KEY (taxon_id, year));
  ```
  `mention` is not partitioned in M3; ADR-0005 records why.
- [ ] **Step 2: Write `IndexMapperIT` and see it fail.** It covers:
  - `finderRunUpsertIsStable`: the same info gives the same id, also with a null dictionary key.
  - `nameStringUpsertKeepsFirstKind`
  - `deleteWorkIndexRemovesOnlyThatWork`
  - `onlyOneCurrentRelease`: a second `current=true` fails.
  - `matchRoundTrip`: a `text[]` path and a jsonb classification survive the round trip.
- [ ] **Step 3: Implement the records and the mapper. Move the blob store, with the indexer importing `store.blob`.** `web` gets a `FileBlobStore` bean for reading, on `garrulus.blobs.dir` (`GARRULUS_BLOBS_DIR`, default `./data/blobs`), as in the indexer.
- [ ] **Step 4: Run `mvn -B -pl store,indexer,web -am verify`, then commit**
  The web `SchemaCheckIT` still passes.
  `git commit -m "feat(store): schema V4 for mentions, names, matches and work names; blob store in store"`

### Task 4: The finder in the indexer — `FIND_NAMES`

**Files:**
- Create (in `indexer/.../finder/`):
  - `FinderConfig.java`
  - `DocumentLoader.java`
  - `Spool.java`
  - `FindNamesHandler.java`
  - `FindPendingHandler.java`
  - `indexer/.../task/ServiceOutageException.java`
- Modify:
  - `content/ResolveContentHandler.java` (spool and enqueue)
  - `task/TaskDispatcher.java` (handles `ServiceOutageException` like `BudgetExhaustedException`)
- Test: `FindNamesHandlerIT` and `DocumentLoaderTest`. Use a stub `NameFinder` bean that returns a fixed `FinderResult`; gnfinder is not needed.

**Interfaces:**
- Consumes: `IndexMapper` (Task 3), `BlobStore`, `ContentMapper`, `WorkMapper`, and from finder-core `PipelineFactory.create(Path, String, String)`, `RemoteNameFinder(URI)`, `AbstractConverter.fromParts`.
- Produces:
  - Task type `FIND_NAMES`, payload `{"workId":n}`, dedup key `names:<workId>:<docSha first 12>`.
  - On success it enqueues `MATCH_NAMES` with payload `{"workId":n}` and dedup key `match:<workId>:<finderRunId>:<docSha first 12>`.
  - Task type `FIND_PENDING`, payload `{"limit":n}`, an admin task: it enqueues `FIND_NAMES` for up to n works in `CONTENT_RESOLVED` with `names_doc_sha IS NULL`, newest first.
  - `ServiceOutageException(String service, Instant retryAt)`.
  - `Spool.put(String sha, byte[] docJson)`, `Optional<byte[]> get(String sha)`, `delete(String sha)`, `purgeOlderThan(Duration): int`. The directory is `garrulus.content.spool-dir` (`GARRULUS_SPOOL_DIR`, default `./data/spool`), and files are named `<sha>.json.gz`.
  - `DocumentLoader.load(Work): Loaded(Document doc, String docSha, Map<String,String> diagnostics)`.

- [ ] **Step 1: Write failing tests**
  - `openFullTextIsReadFromBlob`
  - `closedFullTextIsReadFromSpoolThenDeleted`
  - `missingSpoolFallsBackToAbstract`: diagnostics `{"fallback":"abstract"}`.
  - `abstractOnlyWorkUsesRebuiltAbstract`: the abstract is built by `AbstractConverter.fromParts("work:"+id, title, abstract, abstractJats)`.
  - `noTextWorkIsIndexedEmpty`: `NO_TEXT` goes straight to `MATCH_NAMES` with no mentions.
  - `findStoresMentionsWithExactSpans`: every stored `verbatim` equals the code-point substring of its block, the snippet has at most 200 code points, and `block_type` comes from the block.
  - `otuMentionsAreStoredAsKindOtu`
  - `findTwiceReplacesMentions`
  - `gnfinderOutageDefersWithoutAttempt`: the stub throws `GnfinderUnavailableException`. The task is deferred by 30 minutes and its `attempts` does not change.
  - `resolveEnqueuesFindOnlyWhenTextChanged`: a re-check that yields the same doc sha enqueues nothing.
- [ ] **Step 2: Run them and see them fail**
  `mvn -B -pl indexer -am verify -Dit.test=FindNamesHandlerIT -Dtest=DocumentLoaderTest`
- [ ] **Step 3: Implement**
  - **Choosing the finder in `FinderConfig`:**
    - If `garrulus.finder.url` (`GARRULUS_FINDER_URL`) is set, use `RemoteNameFinder`.
    - Otherwise, if `garrulus.finder.dictionary` (`FINDER_DICTIONARY`) is set, use `PipelineFactory.create(dictionary, GNFINDER_URL, FINDER_CONFIG)`.
    - Otherwise register no `FIND_NAMES` handler and log one WARN. The tasks then wait in the queue.
  - **Choosing the document:**
    - For `FULLTEXT`, use the doc of the non-`ABSTRACT` content row: the blob if retained, else the spool.
    - Otherwise use the rebuilt abstract.
  - **`ResolveContentHandler`:**
    - It writes the spool for every non-retained full-text doc.
    - When the chosen doc's sha differs from `work.names_doc_sha`, it enqueues `FIND_NAMES` after its transaction.
  - **`FIND_NAMES`:**
    - It calls `find(doc, FinderOptions.defaults())`.
    - In one transaction it upserts the `finder_run` and the name strings (`Mention.name`, `kind`, `rank`), runs `deleteWorkIndex`, then inserts the contexts and the mentions. A mention's `context_id` maps from `Mention.contextId` via the context's `local_id`. A mention's `act_type` comes from the `Act` whose `mentionId` matches.
    - It then calls `setNamesFound` and stores the diagnostics.
    - After the commit it deletes the spool file and enqueues `MATCH_NAMES`.
  - **Snippet:** 80 code points either side of the mention, cut at the block's edges, with the ends trimmed to whole words.
- [ ] **Step 4: Run the indexer module's tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "feat(indexer): FIND_NAMES runs the finder on each resolved work and stores mentions, names and contexts"`

### Task 5: XR release and the CLB matcher with its cache

**Files:**
- Create (in `indexer/.../match/`):
  - `XrReleaseService.java`
  - `XrCheckHandler.java`
  - `ClbClient.java`
  - `ClbMatch.java`
  - `MatchCache.java`
- Test fixtures: `indexer/src/test/resources/clb/` holds `dataset-3LXR.json`, `match-carabus-nemoralis.json`, `match-felis-leo.json`, `match-carabus-xyzzyus.json`, `match-none.json` and `match-carabidae.json`.
- Test: `ClbClientTest` (WireMock) and `MatchCacheIT`

**Interfaces:**
- Produces:
  - `ClbClient.currentXr(): XrInfo(int key, String alias)`
  - `ClbClient.match(int releaseKey, MatchQuery q): ClbMatch`, where `MatchQuery(String name, @Nullable String rank, @Nullable String authorship, SortedMap<String,String> hints)`. The hint keys come only from `kingdom`, `phylum`, `class`, `order`, `family` and `genus`.
  - `ClbMatch(String type, @Nullable String usageId, @Nullable String usageName, @Nullable String usageRank, @Nullable String usageStatus, @Nullable String acceptedId, @Nullable String code, List<String> taxonPath, List<Taxon> classification)`, with `Taxon(String id, String name, String rank)`.
  - `MatchQuery.hintKey()`: the hints sorted as `k=v` joined by `|`, plus `|authorship=<a>` when there is an authorship. It is empty with neither.
  - `MatchCache.match(long nameStringId, MatchQuery q): NameMatch`: a cache lookup for (id, hintKey, current release), otherwise the client, then `putMatch`.
  - `XrReleaseService.current(): int`. It throws `ServiceOutageException` when there is no release yet and CLB cannot be reached.
  - Task type `XR_CHECK`, scheduled daily at `0 0 6 * * *` UTC (ShedLock name `xr-check`), dedup key `xr-check:<date>`.

- [ ] **Step 1: Capture the fixtures by hand, once.** The URLs, with `UA='Garrulus/dev (+https://www.catalogueoflife.org)'`:
  - `curl -A "$UA" https://api.checklistbank.org/dataset/3LXR`
  - `.../dataset/316691/match/nameusage?scientificName=Carabus%20nemoralis&rank=species&mode=RELAXED`
  - `?scientificName=Felis%20leo&mode=RELAXED`
  - `?scientificName=Carabus%20xyzzyus&mode=RELAXED` (gives `higherrank` to genus Carabus 8KVGQ)
  - `?scientificName=Xyzzy%20foo&mode=RELAXED` (gives `match:false`, `type:none`)
  - `?scientificName=Carabidae&mode=RELAXED`

  Strip `labelHtml` and `label` to keep the files small.
- [ ] **Step 2: Write failing tests**
  - `exactMatchPathIsUsageThenClassification`: Carabus nemoralis gives a path that starts with `5WYRN`, `8LTQG`, `8KVGQ` and contains `8KTL7` (Carabidae).
  - `synonymPathStartsWithAcceptedLineage`: Felis leo gives `usageStatus=synonym`, `acceptedId=4CGXP`, and a path of `CFSCR`, `4CGXP`, `6DBT`, …, `623RM`, ….
  - `noneHasEmptyPath`
  - `codeIsMappedToNomCodeName`: `"zoological"` becomes `ZOOLOGICAL`.
  - `hintKeyIsSortedAndStable`
  - `cacheHitMakesNoCall`: WireMock verifies a single request for two lookups.
  - `firstXrCheckPinsRelease` and `laterNewKeyIsRecordedButNotCurrent`: XR switching is M5.
  - `clbDownThrowsServiceOutage`: a 503 after `PoliteHttp`'s retries, or `UnknownHostException`, throws `ServiceOutageException`.
- [ ] **Step 3: Implement** on `PoliteHttp`, with a host limit of 10 requests a second for `api.checklistbank.org` and the base URL from `garrulus.clb.base-url`. Parse with Jackson 3. `type` is stored lower-case as CLB sends it.
- [ ] **Step 4: Run the tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "feat(indexer): pinned COL XR release and cached CLB name matching"`

### Task 6: `MATCH_NAMES` — placement, code and `TaxGroup`

**Files:**
- Modify: `indexer/pom.xml` adds `org.catalogueoflife:vocab` and `org.catalogueoflife:nomenclature` (versions from `clb-bom`), with the `name-parser-rust` exclusion that Blixa's pom uses for `nomenclature`.
- Create: `match/Placer.java`, `match/TaxGroups.java`, `match/MatchNamesHandler.java`
- Test: `PlacerTest`, `TaxGroupsTest`, `MatchNamesHandlerIT` (WireMock CLB, using the Task 5 fixtures)

**Interfaces:**
- Consumes: `MatchCache.match`, `IndexMapper.mentions/contexts`.
- Produces:
  - `interface WorkIndexing { void index(long workId, Map<Long, Placement> byNameStringId); }` in `match/`. Task 6 ships a no-op bean that only sets `state=MATCHED`, and Task 7's `WorkIndexer` replaces it.
  - `Placement(String method, @Nullable String usageId, List<String> taxonPath, @Nullable String code, @Nullable String taxgroup)`
  - `Placer.place(NameString n, ClbMatch own, @Nullable List<ClbMatch> contextMatches): Placement`
  - `TaxGroups.of(ClbMatch m): @Nullable TaxGroup` and `TaxGroups.of(String name, @Nullable String rank, List<RankedName> context): @Nullable TaxGroup`
  - Task type `MATCH_NAMES`

- [ ] **Step 1: Check that the CLB modules resolve:** `mvn -B -pl indexer -am dependency:tree -Dincludes=org.catalogueoflife`. If `nomenclature:${clb.version}` cannot be resolved, run `mvn -q -DskipTests -pl nomenclature -am install` in `~/code/col/backend`. That only reads the backend checkout; change nothing there. Record what you did in the ledger.
- [ ] **Step 2: Write failing tests (`PlacerTest`)**
  - `exactOrVariantOrCanonicalIsMatch`
  - `higherrankToGenusIsGenus`: Carabus xyzzyus gives `GENUS` with path `8KVGQ`, ….
  - `higherrankAboveGenusUsesContext`
  - `noneWithContextUsesLowestMatchedContextTaxon`: context Coleoptera: Carabidae with an unmatched name gives `VERBATIM_CONTEXT` and the path of Carabidae.
  - `noneWithoutContextIsNone`
  - `ambiguousIsNoneUnlessContext`
  - `otuIsNeverMatched`: kind `OTU` makes no CLB call and gives `NONE`.
- [ ] **Step 3: Write failing tests (`TaxGroupsTest`)**
  - `matchedCarabusIsColeoptera`, via `TaxGroupAnalyzer.analyze(SimpleName, classification)` built from the match.
  - `unmatchedUsesVerbatimContext`: (Coleoptera, Carabidae) gives a group contained in Insects.
  - `codeFromGroupOnlyWhenUnambiguous`
- [ ] **Step 4: Write failing tests (`MatchNamesHandlerIT`)**
  - `hintsComeFromTheMentionsContextElseDocumentContext`
  - `contextNamesAreMatchedWithoutHints`
  - `clbOutageDefersWithoutAttempt`
- [ ] **Step 5: Implement the hints and context matches**
  - **Hints:** take the context of the name's first mention that has one; otherwise the document-scope context; otherwise none. Only the ranks in the hint-key list are used.
  - **Context matches:** each context element is matched alone, with no hints, as its own `name_string` (kind `UNINOMIAL`, its rank). The lowest-ranked element with an exact, variant or canonical match is the context fallback.
- [ ] **Step 6: Run the tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "feat(indexer): MATCH_NAMES places names on the XR with code and TaxGroup"`

### Task 7: `work_name`, salience and `INDEXED`

**Files:**
- Create: `index/Salience.java`, `index/SalienceProperties.java`, `index/WorkIndexer.java` (implements `WorkIndexing`)
- Modify: `match/MatchNamesHandler.java` (uses the real `WorkIndexer`; the no-op bean goes)
- Test: `SalienceTest` and `WorkIndexerIT`

**Interfaces:**
- Produces:
  - `SalienceProperties` (`garrulus.salience.*`), with defaults `title=0.40`, `abstract=0.20`, `act=0.35`, `heading=0.15`, `share=0.25`, `repeat=0.15`, `ubiquity=0.50`, `ubiquity-full-share=0.02` and `threshold=0.35`.
  - `Salience.score(Inputs i): double`, where `Inputs(boolean inTitle, boolean inAbstract, boolean hasAct, boolean inHeading, int bodyCount, int maxBodyCountInWork, double ubiquityShare)`.
  - `WorkIndexer.index(long workId, Map<Long, Placement> byNameStringId)`: in one transaction it replaces `work_name`, calls `setIndexed` and sets `state=INDEXED` and `indexed_at=now()`.
- [ ] **Step 1: Write the salience formula as given:**
  ```
  s = title*inTitle + abstract*inAbstract + act*hasAct + heading*inHeading
      + share*(bodyCount / maxBodyCountInWork) + repeat*min(1, (bodyCount-1)/4)
      - ubiquity*min(1, ubiquityShare / ubiquityFullShare)
  salience = clamp(s, 0, 1); main_subject = salience >= threshold
  ```
  - Counts exclude `REFERENCE` blocks.
  - `inTitle` means a `TITLE` block. `inAbstract` means an `ABSTRACT` block, or the whole document for an abstract-only work. `inHeading` means a `HEADING` block.
  - A name that occurs only in references gets salience 0 and is not a main subject.
  - `ubiquityShare` comes from `name_ubiquity` and is 0 when the name has no row.
- [ ] **Step 2: Write failing tests**
  - `titleBinomialWithActIsMainSubject`
  - `labOrganismInMethodsOnlyIsNot`: E. coli, body count 2, ubiquity 0.05.
  - `referenceOnlyNameIsNeverMainSubject`
  - `weightsComeFromProperties`
  - `indexTwiceReplacesWorkNames`
  - `workColumnsAggregateMainSubjectsOnly`: `codes` and `taxgroups` come from main-subject names. `taxgroups` holds each group with its ancestors (`TaxGroup.classification()`, the group itself included). `has_acts` is true if any mention has an act.
  - `bestSnippetPrefersTitleThenAbstractThenFirstBody`
- [ ] **Step 3: Implement, run the tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "feat(indexer): work_name aggregates with salience, works end INDEXED"`

### Task 8: Nightly `name_ubiquity`, `taxon_stats` and spool purge

**Files:**
- Create:
  - `stats/StatsScheduler.java`
  - `stats/RebuildStatsHandler.java`
  - `store/.../stats/StatsMapper.java` and `.xml`
- Test: `RebuildStatsIT`

**Interfaces:**
- Produces:
  - Task type `REBUILD_STATS` (dedup key `stats:<date>`), enqueued at `0 30 4 * * *` UTC (`garrulus.stats.cron`, ShedLock name `stats`), regardless of the harvest switch.
  - `StatsMapper.rebuildUbiquity(int fromYear): int`
  - `StatsMapper.rebuildTaxonStats(): int`

- [ ] **Step 1: Write failing tests**
  - `ubiquityIsShareOfRecentIndexedWorks`: of 10 `INDEXED` works from the last 5 calendar years, 4 name *E. coli* outside references. The share is 0.4.
  - `referenceOnlyNamesDoNotCount`: this covers ubiquity and `taxon_stats`.
  - `taxonStatsCountsEachWorkOncePerAncestor`: two names of one work under Carabidae count 1 for `8KTL7` in that year. `main_subject_works` counts only works with a main-subject name under the taxon.
  - `rebuildIsAtomic`: readers see either the old rows or the new ones.
  - `spoolOlderThan14DaysIsPurged`
- [ ] **Step 2: Implement.**
  - Each rebuild is one transaction: `DELETE` then `INSERT … SELECT`. The taxon rebuild unnests `taxon_path` from `work_name` where `NOT only_reference`, joined to `INDEXED`/`NOTIFIED` works.
  - Then `Spool.purgeOlderThan(14 days)`.
  - The handler ends by enqueuing `CATEGORISE_SOURCES` (Task 9) with dedup key `categorise:<date>`.
- [ ] **Step 3: Run the tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "feat(indexer): nightly name ubiquity and taxon stats"`

### Task 9: Journal categorisation

**Files:**
- Create: `stats/SourceCategoriser.java`, plus a `CATEGORISE_SOURCES` handler in the same package
- Modify: `store/.../source/SourceMapper.java` and `.xml` (`updateCategorisation`)
- Test: `SourceCategoriserIT`

**Interfaces:**
- Produces: `SourceMapper.updateCategorisation(long id, float nameDensity, float actRate, String[] taxgroups, String taxgroupSharesJson, String[] codes, String journalType)`

- [ ] **Step 1: Write failing tests**
  - `sourcesWithFewerThan20IndexedWorksKeepSeededValues`: the window is the last 3 years.
  - `groupsAt20PercentOrMoreAreKept`: shares are main-subject `work_name` rows per lowest group, and the JSON is `{"Coleoptera":0.62,"Hymenoptera":0.21}` rounded to 2 decimals.
  - `codesAt20PercentOrMore`
  - `actRate10PercentMakesTaxonomic`: `act_rate` is the share of works with `has_acts`. A source is `TAXONOMIC` if `act_rate >= 0.10` or its tier is `CORE`, else `GENERAL`.
  - `nameDensityIsMeanDistinctNonReferenceNamesPerWork`
- [ ] **Step 2: Implement.**
  - `subfields`, `primary_subfield`, `oa_model`, `country` and `works_per_year` stay as seeded.
  - There is no topic-based group seeding; ADR-0005 notes that groups appear once 20 works are indexed.
- [ ] **Step 3: Run the tests, then commit**
  `mvn -B -pl indexer -am verify` → green.
  `git commit -m "feat(indexer): nightly journal categorisation from the index"`

### Task 10: Public API — work search with filters and facets, and the article

**Files:**
- Create:
  - `store/.../search/WorkFilter.java`
  - `store/.../search/SearchMapper.java` and `.xml`
  - `web/.../api/public_/WorkController.java`
  - `web/.../api/public_/dto/*`
- Test: `SearchMapperIT` (store) and `WorkControllerIT` (web)

**Interfaces:**
- Produces:
  - `WorkFilter(@Nullable String taxon, @Nullable Long name, @Nullable String code, @Nullable String taxgroup, @Nullable String subfield, @Nullable String journalType, @Nullable String tier, @Nullable String oa, @Nullable String country, @Nullable Long source, @Nullable Integer yearFrom, @Nullable Integer yearTo, @Nullable Boolean hasActs, @Nullable String actType, boolean mainSubjectOnly, @Nullable String q)`
  - `SearchMapper.search(WorkFilter, int limit, int offset)`, `count(WorkFilter)` and `facet(WorkFilter, String facet): List<FacetCount(String value, long count)>`
  - `GET /api/works`: the filter params with the same names, plus `limit` (default 20, max 100), `offset` (max 10000) and a repeatable `facet` from `code`, `taxgroup`, `journalType`, `year`, `source` and `hasActs`. Responds with `{total, limit, offset, results:[WorkSummary], facets:{name:[{value,count}]}}`.
  - `WorkSummary(long id, String doi, String title, Integer year, LocalDate publicationDate, SourceRef source, List<String> authors, int authorCount, String textLevel, boolean hasActs, List<NameRef> mainNames)`, where `authors` holds at most 3, `mainNames` at most 5, and `NameRef(long id, String name, String placementMethod, String usageId)`.
  - `GET /api/works/{id}` gives `WorkDetail`: metadata, abstract, `licence`, `retained`, `names` (every `work_name` with its placement and classification names from `name_match.classification`), `contexts`, and `text`. `text` holds the blocks with their mention spans, only when the retained full-text doc exists; otherwise it is null.

- [ ] **Step 1: Write failing tests**
  - `onlyIndexedWorksAreListed`
  - `taxonFilterIncludesDescendants`
  - `taxonFilterFindsWorksNamingASynonym`
  - `taxonFilterIgnoresReferenceOnlyNames`
  - `mainSubjectOnlyNarrows`
  - `taxgroupFilterIsHierarchical`: a filter on Insects finds a Coleoptera work.
  - `qSearchesTitleAndAbstract`, using `websearch_to_tsquery('simple', q)`.
  - `facetsCountTheFilteredSet`
  - `sortIsNewestFirst`: `publication_date DESC NULLS LAST, id DESC`.
  - `unknownCodeOrTaxgroupIs400`: values are checked against `NomCode` and `TaxGroup`.
  - `limitOver100Is400`
  - `closedWorkHasNoText`
  - `openWorkTextSpansMatchMentions`
- [ ] **Step 2: Implement.** The filter SQL is one dynamic `<sql id="where">` fragment reused by `search`, `count` and `facet`. The taxon filter is `EXISTS (SELECT 1 FROM work_name wn WHERE wn.work_id = w.id AND wn.taxon_path @> ARRAY[#{taxon}] AND NOT wn.only_reference [AND wn.main_subject])`.
- [ ] **Step 3: Run the tests, then commit**
  `mvn -B -pl store,web -am verify` → green.
  `git commit -m "feat(web): public work search with filters and facets, article detail"`

### Task 11: Public API — names, taxa, sources, XR and vocab, with CORS and OpenAPI

**Files:**
- Create:
  - `web/.../api/public_/{NameController,TaxonController,SourceController,XrController,VocabController}.java`
  - `web/.../CorsConfig.java`
  - `web/src/main/resources/static/api/openapi.yaml`
- Modify: `web/pom.xml` adds `org.catalogueoflife:vocab`
- Test: one `*ControllerIT` per controller, plus `OpenApiCoverageTest`

**Interfaces:**
- Produces:
  - `GET /api/names?q=&limit=` returns `[{id,name,kind,rank,works}]`. It uses `name ILIKE q||'%'` first, then `similarity()` above 0.3, ordered by works descending. `q` needs at least 2 characters, else 400.
  - `GET /api/names/{id}` returns `{id,name,kind,rank,match:{usageId,usageName,rank,status,acceptedId,classification}|null,timeline:[{year,works}],firstAppearance:{workId,title,publicationDate}|null}`. The match is the `name_match` row with `hint_key=''` on the current release, if there is one.
  - `GET /api/taxa/{colId}/works` is `/api/works` with `taxon=colId` and `mainSubjectOnly` defaulting to `true`, the same response shape, compact.
  - `GET /api/taxa/{colId}/stats` returns `{taxonId, total, mainSubjectTotal, years:[{year,works,mainSubjectWorks}]}`.
  - `GET /api/sources?q=&tier=&journalType=&limit=&offset=` lists only active CORE and BROAD sources.
  - `GET /api/sources/{id}` returns the categorisation fields plus `recentWorks` (10).
  - `GET /api/xr` returns `{key, alias}`, or 404 before the first `XR_CHECK`.
  - `GET /api/vocab/taxgroup` returns `[{name, parents:[…], codes:[…], icon}]`, with `icon` = `https://api.checklistbank.org/vocab/taxgroup/{name}/icon.svg`.
  - CORS applies to `/api/taxa/**` only. The origins come from `garrulus.web.cors-origins` (default `https://www.catalogueoflife.org,https://dev.catalogueoflife.org,https://preview.catalogueoflife.org`), and only `GET` is allowed.
  - The OpenAPI file is served at `/api/openapi.yaml`.
- [ ] **Step 1: Write failing tests**
  - `nameSearchNeedsTwoChars`
  - `nameTimelineCountsWorksPerYear`
  - `taxonStatsFromTaxonStatsTable`
  - `taxaCorsAllowsPortalOriginOnly`: an `Origin: https://www.catalogueoflife.org` request gets the header; `https://evil.example` does not; `/api/works` gets no CORS headers.
  - `sourcesHidesCandidatesAndExcluded`
  - `xr404BeforeFirstCheck`
  - `OpenApiCoverageTest.everyPublicGetMappingIsDocumented`: it reads the `RequestMappingHandlerMapping` and asserts that every `GET /api/**` path that is not under `/api/admin` is a key in the yaml's `paths`.
- [ ] **Step 2: Implement, run the tests, then commit**
  `mvn -B -pl web -am verify` → green.
  `git commit -m "feat(web): public names, taxa, sources, XR and vocab endpoints with CORS and OpenAPI"`

### Task 12: Frontend — router, API client and search page

**Files:**
- Modify: `frontend/package.json` adds `react-router-dom@6.28.0` (an exact pin); `frontend/src/App.tsx` and `main.tsx` use the routes
- Create:
  - `frontend/src/api.ts`
  - `frontend/src/routes.tsx`
  - `frontend/src/pages/SearchPage.tsx`
  - `frontend/src/components/{FilterSidebar,WorkList,TaxGroupIcon,TaxonPicker}.tsx`
  - Tests next to each (`*.test.tsx`) with MSW handlers in `src/test/server.ts`

**Interfaces:**
- Consumes: Tasks 10–11 endpoints, and the browser call to the CLB suggest API `https://api.checklistbank.org/dataset/{xrKey}/nameusage/suggest?q=` for the taxon picker.
- Produces:
  - Routes `/` (search), `/taxon/:id`, `/name/:id`, `/work/:id` and `/source/:id`.
  - The URL query string is the single state of the search filters, with the same names as the API params.
  - `api.ts` exports typed `searchWorks(filter)`, `getWork(id)`, `getName(id)`, `searchNames(q)`, `getTaxonStats(id)`, `getSource(id)`, `listSources()`, `getXr()` and `getTaxGroups()`.
- [ ] **Step 1: Write failing tests**
  - `filtersRoundTripThroughUrl`
  - `facetCountsShowNextToOptions`
  - `taxgroupOptionsShowIcons`
  - `taxonPickerUsesPinnedXrKey`
  - `emptyResultShowsHint`
  - `apiErrorShowsMessageNotCrash`
- [ ] **Step 2: Implement.**
  - Mantine components only. Colours come from `theme.ts` and `docs/brand/`; invent none.
  - The sidebar has code, taxonomic group (hierarchical, with icons), journal type, subfield, tier, OA, year range and acts.
  - Page titles are "Garrulus — …".
- [ ] **Step 3: Run `cd frontend && npm test && npm run build`, then commit**
  Both pass.
  `git commit -m "feat(frontend): router, API client and search page with filters"`

### Task 13: Frontend — taxon and article pages

**Files:**
- Create:
  - `frontend/src/pages/{TaxonPage,WorkPage}.tsx`
  - `frontend/src/components/{YearBars,HighlightedText}.tsx`
  - Tests next to each

**Interfaces:**
- Consumes: `getTaxonStats`, `searchWorks({taxon})`, `getWork`. The taxon header (name, rank, classification) comes from the browser's call to `https://api.checklistbank.org/dataset/{xrKey}/taxon/{id}`.
- [ ] **Step 1: Write failing tests**
  - Taxon page: `taxonPageShowsYearsRecentWorksAndNewNames`. "New names" means works with `hasActs` among the taxon's works. It also lists the verbatim classifications used, taken from the recent works' contexts.
  - Article page:
    - `workPageGroupsNamesByPlacement`: the `MATCH`/`GENUS`, `VERBATIM_CONTEXT` and `NONE` sections, each name linking to `/name/:id` and its placed taxon to `/taxon/:usageId`.
    - `verbatimClassificationNextToColOne`
    - `closedWorkShowsNoTextOnlySnippets`
    - `openWorkHighlightsMentions`: `HighlightedText` slices by code points, not UTF-16 units. The test uses a block with an astral-plane character before the mention.
  - `yearBarsRenderOneBarPerYear`
- [ ] **Step 2: Implement, run `npm test && npm run build`, then commit**
  `git commit -m "feat(frontend): taxon and article pages"`

### Task 14: Frontend — name and journal pages

**Files:**
- Create: `frontend/src/pages/{NamePage,SourcePage}.tsx` with tests

- [ ] **Step 1: Write failing tests**
  - `namePageShowsMatchTimelineAndFirstAppearance`
  - `unmatchedNameSaysSo`
  - `sourcePageShowsCategorisationAndRecentWorks`: groups with shares and icons, codes, journal type, subfields, OA model and country.
- [ ] **Step 2: Implement, run `npm test && npm run build`, then commit**
  `git commit -m "feat(frontend): name and journal pages"`

### Task 15: Dev check against the live dev run, and documentation

**Files:**
- Create: `docs/adr/0005-index-pipeline.md`, `docs/m3-dev-check.md`
- Modify:
  - `CLAUDE.md`: the new tasks, environment variables, endpoints, and the `FIND_PENDING` admin task
  - `docs/design.md`: Open items. The synonym `classification[]` holds the accepted lineage, verified on 2026-11-15. Record the M3 calls made in this plan.

- [ ] **Step 1: ADR-0005.** It records:
  - the transient spool for closed full text, with the 14-day purge;
  - no `mention` partitioning yet;
  - the XR pinned at the first key, with switching left to M5;
  - the placement rules (Task 6), including that `higherrank` above genus falls back to context or `NONE`;
  - the salience formula with its untuned defaults, to be tuned in the final finder-tuning stage;
  - categorisation only after 20 indexed works;
  - the hand-written OpenAPI file;
  - the browser calling CLB directly for the picker and the taxon header.
- [ ] **Step 2: Run the full build.** `mvn -B verify` and `cd frontend && npm test && npm run build` both pass.
- [ ] **Step 3: Restart the dev indexer with the M3 code.**
  - Follow `docs/m2-dev-run.md`, with the same ports (18080/18081), pid files and `.env`.
  - Add `FINDER_DICTIONARY=$PWD/eval/work/dictionary-<key>.bin` and run `docker compose up -d gnfinder`.
  - Restart `web` too, so that it serves the API.
  - Then enqueue `XR_CHECK` (dedup key `xr-check:manual`) and `FIND_PENDING` with `{"limit":100000}` through `POST /api/admin/tasks`.
- [ ] **Step 4: Wait until the queue drains.** Use `GET /api/admin/status` every few minutes. Then record in `docs/m3-dev-check.md`:
  - works by state;
  - mentions and `work_name` rows;
  - `name_match` rows by `match_type`;
  - placements by method;
  - DEAD tasks, which should be 0 or each explained.
- [ ] **Step 5: Check the "done when" condition.**
  - Run `curl 'localhost:18080/api/taxa/8KTL7/works?limit=50'` (Carabidae in XR 316691) and `GET /api/taxa/8KTL7/stats`.
  - Check the placements of up to 10 of this week's works by hand against CLB: each main-subject Carabidae name is placed under 8KTL7.
  - Open the taxon page in the Vite dev server against `web` on 18080, take a screenshot, and save it to the scratchpad.
  - Record the result, and any wrong placement with its cause, in `docs/m3-dev-check.md`.
- [ ] **Step 6: Commit**
  `git add docs/adr/0005-index-pipeline.md docs/m3-dev-check.md CLAUDE.md docs/design.md`
  `git commit -m "docs: ADR-0005 index pipeline, M3 dev check"`
