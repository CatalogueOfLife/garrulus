# M5 Lifecycle and Backfill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Garrulus switches to each new COL XR on its own, rolls a new finder over its papers, never hides a paper while
re-indexing it, matches without a throttle, and can backfill 2000→now from the free OpenAlex snapshot.

**Architecture:** Everything runs in the `indexer` as tasks in the existing Postgres queue (resumable, idempotent,
batches that enqueue their own continuation like `RESCORE_SALIENCE`). A re-index writes the finder's result into
pending tables and swaps it in at indexing time. The XR switch is a chain of tasks
(`XR_SWITCH` → `XR_PREMATCH` → `XR_GATE` → flip → `BUILD_DICTIONARY` + `XR_REPLACE` → `XR_FINISH`) over the
release-keyed `name_match` cache. The backfill streams snapshot files over HTTPS, one task per file. `web` only shows
state (admin status, follow notes, digest footer). Admin commands are tasks sent through `POST /api/admin/tasks`.

**Tech Stack:** Java 25, Spring Boot 4.1, MyBatis, Flyway, Postgres 17, Testcontainers, WireMock; React + Mantine.

**Spec:** `docs/superpowers/specs/2026-11-17-m5-lifecycle-and-backfill-design.md` (approved 2026-11-17, commit 91b6ddf).
Also read `CLAUDE.md`, `docs/adr/0005-index-pipeline.md`, `docs/adr/0006-accounts-and-alerts.md`.

## Global Constraints

- Work on `main`; no branches, no push. Commit messages carry no Claude/Claude Code mention and no trailer.
- Never use local ports 8080/8081. Never commit `eval/work/`, `data/`, `.env`, PDFs, `eval/reports/OVERLAP/`.
- Never print `.env` secrets (admin token, OpenAlex key, ORCID).
- No paid OpenAlex API in the backfill or in reprocessing: no OpenAlex list calls, no OpenAlex content (`OpenAlexTeiRoute`)
  downloads. The snapshot base URL default is `https://openalex.s3.amazonaws.com` (`NAMETRACKER_SNAPSHOT_BASE_URL`).
- Production matching uses the public CLB API, unthrottled. A local bundle (`NAMETRACKER_CLB_MATCH_BASE_URL`) is a
  development option: with one, `XR_CHECK` never starts a switch on its own.
- An XR switch creates zero hits: `XR_REPLACE` never enqueues `EVALUATE_HITS` and never writes `indexed_at` or `state`.
- A work that was `INDEXED` stays `INDEXED` and listed through every re-index (finder, full text arriving, title
  clean-up, reprocessing) until its new index replaces the old one in one transaction.
- Re-index hit rule: a re-indexed work gives a follow an `ARTICLE` hit only if the follow did not match the work's
  previous index; window, `since_year`, one `ARTICLE` per (follow, work) and `NEW_NAMES` stay as in ADR-0006.
- Gate default `nametracker.xr.max-lost-share` = 0.05. Pre-match parallelism `NAMETRACKER_CLB_MATCH_PARALLELISM` = 8.
- Migrations are expand-only (`V8`…`V12`, one per task as listed); `web` keeps validating the schema.
- Priorities: switch and reprocessing at `REPROCESS`, backfill at `BACKFILL`; the live harvest is untouched.
- Every new task type is idempotent under redelivery and listed in `CLAUDE.md`.
- Public-facing words say "Garrulus"; colours only from `docs/brand/`.

## Review Focus

1. **A switch interrupted by an indexer restart** (any step) resumes where it stopped and ends with every `INDEXED`
   work on the new release; nothing runs twice in a way that changes results. Pinned by
   `XrReplaceIT.resumesAfterRedeliveryAndCatchesStragglers` (Task 6) and `XrSwitchIT.redeliveredStepsAreNoOps` (Task 5).
2. **A live `MATCH_NAMES` racing the flip** (it read the old key, writes after the flip) leaves a straggler on the old
   release; the replace pass must catch it, because it selects by `placed_release`. Pinned by the straggler test above.
3. **A re-index of a work whose new finder result is empty** (all names gone) must still swap (the work ends `INDEXED`
   with no names), not keep the old names forever. Pinned by `ReindexSwapIT.emptyResultReplacesOldNames` (Task 2).
4. **A snapshot file cut off mid-stream** (connection reset, truncated gzip) fails the task for a retry and records
   nothing as done for that file; the works it already inserted are not duplicated on the retry. Pinned by
   `BackfillFileIT.truncatedFileFailsAndRetryIsIdempotent` (Task 8).
5. **A follow on a taxon id that CLB answers with an error other than 404** (outage) during the follow check must defer
   the finish, not flag the follow as missing. Pinned by `XrFinishIT.clbOutageDefersFollowCheck` (Task 6).

---

### Task 1: Unthrottled matching, explicit release, reusable placer

**Files:**
- Modify: `indexer/.../http/PoliteHttp.java` (`defaultIntervals`: `api.checklistbank.org` → `Duration.ZERO`)
- Modify: `indexer/.../match/MatchCache.java`, `MatchQuery.java`, `MatchNamesHandler.java`, `XrReleaseService.java`
- Create: `indexer/.../match/WorkPlacer.java`
- Modify: `indexer/src/main/resources/application.yml` (comment "at most 10 requests a second" removed;
  `nametracker.clb.match-parallelism: ${NAMETRACKER_CLB_MATCH_PARALLELISM:8}`)
- Test: `indexer/src/test/.../http/PoliteHttpTest` (or the existing limiter test), `match/MatchQueryTest.java`,
  `match/MatchCacheIT.java`, `match/MatchNamesHandlerIT.java`, `match/BundleMatchingIT.java`

**Interfaces:**
- Produces:
  - `NameMatch MatchCache.match(long nameStringId, MatchQuery q, int releaseKey)`; the old two-argument method stays and
    delegates with `xr.current()`.
  - `static MatchQuery MatchQuery.fromHintKey(String name, @Nullable String rank, String hintKey)`: inverse of
    `hintKey()`; `authorship=` is always last and takes the rest of the string (it may contain `|`).
  - `Map<Long, Placement> WorkPlacer.place(long workId, int releaseKey, boolean pending)`: the placement logic now in
    `MatchNamesHandler.handle` (contexts, hints, faults breaker, context group), reading mentions and contexts from the
    pending tables when `pending` (Task 2 adds those reads; until then `pending=false` is the only caller).
  - `MatchNamesHandler` calls `xr.currentVerified()` then `WorkPlacer.place(workId, key, …)`; behaviour unchanged.
  - Parallel CLB calls happen in `XR_PREMATCH` (Task 5), where CLB calls dominate; `MATCH_NAMES` stays sequential per
    work and gets its parallelism from the indexer workers (ruling: the cache is warm for most names, and a thread pool
    inside a task would complicate the faults breaker for little gain).
  - A local bundle serving another release than the pinned one now **defers** `MATCH_NAMES` by the outage delay without
    using an attempt (a `ServiceOutageException`), instead of failing it `DEAD`. `XR_CHECK` still logs the mismatch as
    an ERROR.

- [ ] **Step 1: Write the failing tests.**
  - `PoliteHttpTest.clbHasNoInterval`: `PoliteHttp.defaultIntervals().get("api.checklistbank.org")` is `Duration.ZERO`.
  - `MatchQueryTest.hintKeyRoundTrip`: for hints `{family=Carabidae, genus=Carabus}` and authorship `L. | Fabr.`,
    `MatchQuery.fromHintKey(n, r, q.hintKey())` equals `q` (also for empty hints with and without authorship, and
    for an empty hint key).
  - `MatchCacheIT.explicitReleaseIsCachedSeparately`: two releases recorded; matching the same name and hints with
    key A and with key B asks CLB (WireMock) twice and leaves two `name_match` rows; a repeat asks nothing.
  - `BundleMatchingIT.mismatchDefersInsteadOfDead`: bundle reports another key than the pinned one → the task is
    deferred (state `QUEUED`, `run_after` ≈ now + outage delay, attempts unchanged), not `DEAD`.
  - The existing `MatchNamesHandlerIT` stays green unchanged.
- [ ] **Step 2: Run and see them fail.**
  `mvn -B -pl indexer -am verify -Dit.test='MatchCacheIT,BundleMatchingIT,MatchNamesHandlerIT' -Dtest='PoliteHttpTest,MatchQueryTest'`
- [ ] **Step 3: Implement** as in Interfaces. `WorkPlacer` is a `@Component`; the `Faults` breaker moves with it.
- [ ] **Step 4: Run them and see them pass,** then `mvn -B -pl indexer -am verify`.
- [ ] **Step 5: Commit.** `feat(indexer): unthrottled CLB matching, release-keyed match cache, WorkPlacer`

### Task 2: No gaps while re-indexing (pending index, swap at indexing time)

**Files:**
- Create: `store/src/main/resources/db/migration/V8__reindex_pending.sql`
- Modify: `store/.../index/IndexMapper.java` + `IndexMapper.xml`, `store/.../work/Work.java` if it maps the new columns
- Modify: `indexer/.../finder/FindNamesHandler.java`, `indexer/.../match/WorkPlacer.java`,
  `indexer/.../index/WorkIndexer.java`, `indexer/.../content/ResolveContentHandler.java`
- Test: `indexer/src/test/.../index/ReindexSwapIT.java`, existing `FindNamesHandlerIT`, `ResolveContentHandlerIT`

**V8:**
- `mention_pending` and `classification_context_pending`: same columns as `mention` / `classification_context`; their
  `id` defaults draw from the **live tables' sequences**, so ids survive the move; `mention_pending.context_id`
  references `classification_context_pending ON DELETE SET NULL`; both `work_id … ON DELETE CASCADE`, indexed by work.
- `work`: `pending_finder_run bigint REFERENCES finder_run`, `pending_doc_sha text`, `pending_diagnostics jsonb`,
  `placed_release integer` (set to the current release for every `INDEXED` work in the migration),
  `reindex_prev_subscriptions bigint[]`.

**Interfaces:**
- Consumes: `WorkPlacer.place(workId, releaseKey, pending)` (Task 1).
- Produces:
  - `FIND_NAMES` on a work in state `INDEXED` writes contexts and mentions into the pending tables (replacing any
    earlier pending rows of that work) and sets `pending_finder_run`, `pending_doc_sha`, `pending_diagnostics`; the work
    keeps state `INDEXED` and its live rows. On any other state it writes the live tables as today. The no-op check
    ("already named") compares against the pending doc sha when one is set.
  - `WorkPlacer.place(…, pending=true)` reads `IndexMapper.pendingMentions(workId)` / `pendingContexts(workId)`.
    `MATCH_NAMES` passes `pending = (work.pending_finder_run != null)`.
  - `WorkIndexer.index(workId, placements)`: when the work has a pending run, in its one transaction (work locked):
    1. calls `PreviousMatches.capture(workId)` (an interface in `indexer/.../index/`, a no-op bean until Task 3) and
       stores the result in `work.reindex_prev_subscriptions` (null for a first indexing);
    2. deletes the live mentions and contexts, moves the pending rows to the live tables keeping ids (contexts first),
       deletes the pending rows, sets `current_finder_run`, `names_doc_sha`, `finder_diagnostics` from the pending
       columns and clears them;
    3. aggregates and writes `work_name` as today; `updateIndexed` also sets `placed_release = #{releaseKey}`.
    `WorkIndexing.index` gains the release key: `void index(long workId, Map<Long, Placement> p, int releaseKey)`.
  - `RESOLVE_CONTENT` on an `INDEXED` work never moves its state (it changes content and text level only).

- [ ] **Step 1: Write the failing tests** (`ReindexSwapIT`, indexer Testcontainers base `AbstractPostgresIT`, finder
  stubbed as in `FindNamesHandlerIT`):
  - `reindexKeepsWorkListedUntilSwap`: an `INDEXED` work with names {A}; run `FIND_NAMES` with a finder returning {B}:
    the work is still `INDEXED`, `mentions(workId)` still {A}, `pendingMentions` {B}, `/api/works`-equivalent
    `SearchMapper` query still lists it. Run `MATCH_NAMES`: live mentions {B}, `work_name` {B}, pending tables empty,
    `pending_*` columns null, `current_finder_run` the new run, `indexed_at` advanced.
  - `emptyResultReplacesOldNames`: finder returns nothing on re-index → after `MATCH_NAMES` the work is `INDEXED` with
    no mentions and no `work_name` rows.
  - `contextIdsSurviveTheMove`: a pending mention pointing at a pending context still points at the same context id
    after the swap.
  - `secondReindexReplacesPending`: two `FIND_NAMES` runs before `MATCH_NAMES` leave only the second result pending.
  - `firstIndexingUnchanged`: a `NAMES_FOUND` path work writes live rows directly (as today) and gets
    `placed_release` set.
  - `ResolveContentHandlerIT.indexedWorkStaysIndexedWhenFullTextArrives`.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl indexer -am verify -Dit.test='ReindexSwapIT,ResolveContentHandlerIT,FindNamesHandlerIT'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then `mvn -B -pl store,indexer,web -am verify`** (web validates the schema).
- [ ] **Step 5: Commit.** `feat: re-index into pending tables and swap at indexing time, no gap in listings`

### Task 3: Re-index hit rule (only follows that did not match before)

**Files:**
- Create: `indexer/.../hits/HitMatcher.java`, `indexer/.../hits/SubscriptionsMatchingWork.java` (implements
  `PreviousMatches` from Task 2)
- Modify: `indexer/.../hits/EvaluateHitsHandler.java`, `store` mapper for `work.reindex_prev_subscriptions` reads
- Test: `indexer/src/test/.../hits/ReindexHitsIT.java`, existing `EvaluateHitsHandlerIT`

**Interfaces:**
- Consumes: `PreviousMatches` (Task 2): `long[] capture(long workId)`, called inside the swap transaction before the
  live index is replaced.
- Produces:
  - `List<HitMatcher.Matched> HitMatcher.matching(long workId)`: candidates + subscription filters +
    `SubscriptionMatcher`, with **no** `created`/window check; `record Matched(Subscription s, SubscriptionMatcher.Match m)`.
    `EvaluateHitsHandler` uses it and then applies created/window as today.
  - `SubscriptionsMatchingWork.capture` = ids of `HitMatcher.matching(workId)` on the current (old) index; paused
    follows included.
  - `EvaluateHitsHandler`: a subscription whose id is in `work.reindex_prev_subscriptions` gets no new `ARTICLE` hit;
    `NEW_NAMES` for an existing `ARTICLE` hit is unchanged.

- [ ] **Step 1: Write the failing tests** (`ReindexHitsIT`, onboarding of follows as in `EvaluateHitsHandlerIT`):
  - `unchangedReindexGivesNoHitToLaterFollow`: work indexed; follow F created after; re-index with the same names →
    F has no hit.
  - `reindexThatNewlyMatchesGivesHit`: work indexed without name N; follow F on N created after; re-index finds N →
    F gets one `ARTICLE` hit.
  - `newNamesStillWorks`: F had an `ARTICLE` hit; re-index adds an act → one `NEW_NAMES` hit.
  - `firstIndexingUnchanged`: existing `EvaluateHitsHandlerIT` cases green.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl indexer -am verify -Dit.test='ReindexHitsIT,EvaluateHitsHandlerIT'`
- [ ] **Step 3: Implement.** Replace the Task 2 no-op `PreviousMatches` bean.
- [ ] **Step 4: Run them, then `mvn -B -pl indexer -am verify`.**
- [ ] **Step 5: Commit.** `feat(indexer): a re-index alerts only follows it newly matches`

### Task 4: Finder reprocessing (force, free re-fetch, automatic rollout)

**Files:**
- Create: `store/src/main/resources/db/migration/V9__finder_rollout.sql`
- Create: `indexer/.../finder/ReprocessNamesHandler.java`, `indexer/.../finder/FinderRollout.java`
- Modify: `FindNamesHandler.java`, `ResolveContentHandler.java`, `ContentRoute.java` (+ `OpenAlexTeiRoute`),
  `finder-core/.../pipeline/PipelineConfig` (or wherever `configHash` is computed) and `pipeline-defaults.json`
- Test: `indexer/src/test/.../finder/ReprocessNamesIT.java`, `FinderRolloutIT.java`, `finder-core` config hash test

**V9:** `finder_rollout (config_hash text PRIMARY KEY, started timestamptz NOT NULL DEFAULT now(), enqueued_at
timestamptz, baseline boolean NOT NULL DEFAULT false)`.

**Interfaces:**
- Produces:
  - `pipeline-defaults.json` gains `"epoch": 1`, part of `configHash`. Bump it by hand when finder code changes should
    reprocess.
  - `FIND_NAMES` payload `{"workId":n,"force":true}`: skips the "already named" no-op. If the work is `FULLTEXT` and the
    loader falls back to the abstract (full text gone), it enqueues `RESOLVE_CONTENT {"workId":n,"freeOnly":true,
    "reprocess":true}` (dedup `content:<id>:reprocess:<configHash12>`) and returns without writing.
  - `ContentRoute.paid()` default `false`; `OpenAlexTeiRoute.paid()` = `true`. `RESOLVE_CONTENT` with `freeOnly`
    skips paid routes. With `reprocess`: if a full text is found it is stored as usual and `FIND_NAMES` with
    `force:true` follows even when the document sha is unchanged; if none is found, the work is left exactly as it is
    (content, text level, names) and an INFO line counts it as kept.
  - `REPROCESS_NAMES` payload `{"afterId":0,"batch":1000,"all":false,"source":null,"workIds":null,"yearFrom":null,
    "yearTo":null,"force":true,"rollout":null}`: walks works the finder has seen (`current_finder_run IS NOT NULL`) by
    id, filtered, enqueues `FIND_NAMES {"workId":n,"force":true}` at `REPROCESS` (dedup
    `find:<id>:force:<rollout or "manual">`), continues per batch (`reprocess:<rollout>:<lastId>`); the last batch sets
    `finder_rollout.enqueued_at` when `rollout` is given.
  - `FinderRollout` (on `ApplicationReadyEvent`, only when a finder is configured): reads `finder.info().configHash()`
    (a failing remote finder → WARN, skip); no row at all → insert it as `baseline=true` and do nothing; hash not in
    the table → insert and enqueue `REPROCESS_NAMES {"all":true,"rollout":hash}` (dedup `rollout:<hash>`). The
    dictionary key never takes part.

- [ ] **Step 1: Write the failing tests.**
  - `PipelineConfigTest.epochChangesHash`: two configs differing only in `epoch` have different `configHash`.
  - `ReprocessNamesIT.forceRerunsSameDocument`: a named work; `FIND_NAMES` without force is a no-op; with force a new
    run is written (pending, Task 2) and `MATCH_NAMES` follows.
  - `ReprocessNamesIT.lostFullTextRefetchedFreeOnly`: `FULLTEXT` work whose spool file is gone, forced → a
    `RESOLVE_CONTENT` with `freeOnly`; the OpenAlex TEI route (WireMock) is never called; Europe PMC (WireMock) returns
    text → `FIND_NAMES` force follows.
  - `ReprocessNamesIT.noFreeTextKeepsNames`: same, every free route empty → work content, text level, mentions and
    `work_name` unchanged, no `FIND_NAMES`.
  - `ReprocessNamesIT.batchesAndFilters`: 5 works, `batch:2`, `source` filter → only that source's works get
    `FIND_NAMES`; continuations chain; a redelivered batch enqueues nothing twice.
  - `FinderRolloutIT.firstStartIsBaseline`, `FinderRolloutIT.newHashEnqueuesRollout`, `FinderRolloutIT.sameHashNothing`.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl finder-core,indexer -am verify -Dit.test='ReprocessNamesIT,FinderRolloutIT' -Dtest=PipelineConfigTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then `mvn -B -pl finder-core,indexer -am verify`.** The eval module may print a new
  `configHash`; no committed eval report is regenerated.
- [ ] **Step 5: Commit.** `feat(indexer): finder reprocessing with force, free re-fetch and automatic rollout`

### Task 5: XR switch — detect, pre-match, gate, flip, admin commands

**Files:**
- Create: `store/src/main/resources/db/migration/V10__xr_switch.sql`
- Create: `indexer/.../xr/XrSwitchHandler.java`, `XrPrematchHandler.java`, `XrGateHandler.java`,
  `XrCancelHandler.java`, `XrSwitchProperties.java` (package `org.catalogueoflife.nametracker.indexer.xr`)
- Modify: `indexer/.../match/XrReleaseService.java`, `XrCheckHandler.java`, `IndexMapper` (+ XML), `XrRelease.java`
- Test: `indexer/src/test/.../xr/XrSwitchIT.java`

**V10:** `xr_release` gains `state text NOT NULL DEFAULT 'SEEN' CHECK (state IN ('SEEN','PREPARING','PREPARED',
'SWITCHING','CURRENT','RETIRED','REFUSED'))` (migration: the `current` row → `CURRENT`), `switch_started timestamptz`,
`switched_at timestamptz`, `keys_total integer`, `keys_lost integer`, `note text`. `current` stays and is flipped
together with `state`.

**Interfaces:**
- Consumes: `MatchCache.match(id, q, releaseKey)`, `MatchQuery.fromHintKey` (Task 1).
- Produces:
  - `XR_CHECK`: after recording the `3LXR` key, if it differs from the current key, is not `REFUSED`, no release is in
    `PREPARING`/`PREPARED`/`SWITCHING`, and no local bundle is configured → enqueue `XR_SWITCH {"key":k}` (dedup
    `xr-switch:<k>`). With a bundle: log INFO "new release, switch by hand" only.
  - `XR_SWITCH {"key":k,"refuse":false,"force":false}` (admin or `XR_CHECK`): refuses (ERROR, no-op) when another switch
    runs; records the key if unknown (alias from CLB `GET /dataset/{k}`), sets state `PREPARING`, `switch_started`,
    stores `refuse`/`force` in `note` as JSON, enqueues `XR_PREMATCH {"key":k}`. A switch to the current key is a no-op.
    An admin switch to a `REFUSED` key clears the refusal.
  - `XR_PREMATCH {"key":k,"afterName":0,"afterHint":"","batch":2000}`: next batch of distinct
    (`name_string_id`, `hint_key`) of the current release's `name_match` ordered by both; matches each in key `k`
    with `nametracker.clb.match-parallelism` threads (`MatchCache.match`, cache hits are free); an
    `UnmatchableNameException` stores nothing for that key (counted); continues; the last batch enqueues
    `XR_GATE {"key":k}`. Stops silently when the release is no longer `PREPARING` (cancelled).
  - `XR_GATE {"key":k}`: `keys_total` = matched (not `none`) keys on the current release; `keys_lost` = of those, keys
    whose row in `k` is `none` or missing. If `keys_lost / keys_total > max-lost-share` and not `force` → state
    `REFUSED`, ERROR log with both numbers. Else **flip** in one transaction: old release `current=false`, state
    `RETIRED`; `k` `current=true`, state `SWITCHING`, `switched_at`; if the switch was started with `refuse:true`, the
    old release becomes `REFUSED` instead of `RETIRED`. After the flip enqueue `BUILD_DICTIONARY {"key":k}` (Task 7;
    the handler may not exist yet: the task waits) and `XR_REPLACE {"key":k}` (Task 6).
  - `XR_CANCEL {"key":k}`: a release in `PREPARING`/`PREPARED`/`REFUSED`-by-gate goes back to `SEEN` (or `REFUSED` with
    `{"refuse":true}`); after the flip it is an ERROR no-op.
  - `XrReleaseService.current()` keeps answering the `current=true` row.

- [ ] **Step 1: Write the failing tests** (`XrSwitchIT`, CLB stubbed with WireMock, two releases 100 and 200):
  - `checkStartsSwitchForNewKey`: current 100, `3LXR` answers 200 → `XR_SWITCH {"key":200}` queued; a second check
    queues nothing more.
  - `checkIgnoresRefusedAndRunningAndBundle`: 200 `REFUSED` → nothing; a switch to 300 running → nothing; with a match
    base URL set → nothing.
  - `prematchFillsCacheAndChains`: 5 keys on 100, `batch:2` → three `XR_PREMATCH` batches, 5 rows on 200 with the same
    hint keys, then one `XR_GATE`; public reads (`currentRelease`) still 100 throughout.
  - `gateRefusesBrokenRelease`: 1 of 10 keys lost (0.1 > 0.05) → `REFUSED`, `keys_total=10`, `keys_lost=1`, current
    still 100.
  - `gateFlipsHealthyRelease`: 0 lost → 200 current `SWITCHING`, 100 `RETIRED`; `XR_REPLACE` and `BUILD_DICTIONARY`
    queued.
  - `forceSkipsGate`, `refuseMarksOldRelease`, `cancelBeforeFlip`, `cancelAfterFlipIsNoop`.
  - `redeliveredStepsAreNoOps`: every handler run twice with the same payload ends in the same state with the same
    queued follow-ups.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl store,indexer -am verify -Dit.test=XrSwitchIT`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then `mvn -B -pl store,indexer,web -am verify`.**
- [ ] **Step 5: Commit.** `feat(indexer): automatic XR switch: detect, pre-match, gate, flip`

### Task 6: XR switch — re-place, finish, follows on removed taxa

**Files:**
- Create: `store/src/main/resources/db/migration/V11__follow_xr_notes.sql`
- Create: `indexer/.../xr/XrReplaceHandler.java`, `XrFinishHandler.java`, `FollowCheck.java`
- Modify: `indexer/.../index/WorkIndexer.java` (`replace`), `indexer/.../match/ClbClient.java` (`taxon` lookup),
  `IndexMapper`/`SubscriptionMapper` (+ XML)
- Test: `indexer/src/test/.../xr/XrReplaceIT.java`, `XrFinishIT.java`, `ClbClientTest`

**V11:** `subscription` gains `target_status text NOT NULL DEFAULT 'OK' CHECK (target_status IN ('OK','MOVED',
'MISSING'))`, `xr_note text`, `xr_note_at timestamptz`, `previous_target text`. The indexer writes these four columns
and, for a moved follow, `target` (ruling: the XR switch is the one writer of these; recorded in ADR-0007).

**Interfaces:**
- Consumes: `WorkPlacer.place(workId, key, false)` (Task 1), `placed_release` (Task 2), states (Task 5).
- Produces:
  - `void WorkIndexer.replace(long workId, Map<Long, Placement> p, int releaseKey)`: same aggregate and `work_name`
    rewrite as `index`, then `updatePlacement(workId, codes, taxgroups, hasActs, releaseKey)` which sets
    `placed_release` and never `state`, `indexed_at`, `first_indexed_at`. Skips a work that is not `INDEXED` or has a
    pending run (its `MATCH_NAMES` places it on the current release anyway).
  - `XR_REPLACE {"key":k,"batch":500}`: next batch of `INDEXED` works with `placed_release IS DISTINCT FROM k` ordered by
    id (no `afterId`: the predicate is the cursor, so stragglers from a racing `MATCH_NAMES` are picked up); one
    transaction per work (work locked), `WorkPlacer.place` + `WorkIndexer.replace`; enqueues itself while the batch was
    full; an empty batch enqueues `XR_FINISH {"key":k}`. Never enqueues `EVALUATE_HITS`.
  - `ClbClient.TaxonLookup taxon(int releaseKey, String id)`: `record TaxonLookup(Status status, @Nullable String
    supersededBy)`, `Status` = `FOUND`, `GONE` (404, `supersededBy` from the 404 body when present). Other errors throw
    as outages (`ServiceOutageException` per ADR-0005).
  - `XR_FINISH {"key":k}`: (1) `FollowCheck.run(k)`: for every `TAXON` subscription whose `target` is not among the
    `usage_id`s of `k`'s `name_match` rows, ask `taxon(k, target)`: `FOUND` → nothing; `GONE` with `supersededBy` →
    `previous_target`=old, `target`=new, `target_status`=`MOVED`, `xr_note`="COL <alias> replaced taxon <old> by
    <new>", `xr_note_at`=now; `GONE` without → `MISSING`, note "COL <alias> no longer has taxon <old>". (2) enqueue
    `REBUILD_STATS` at `REPROCESS`. (3) state `CURRENT`. (4) delete `name_match` rows of releases other than the current
    and the one before it. Idempotent: a follow already noted for this release is skipped.
  - No hit is created anywhere in this task.

- [ ] **Step 1: Write the failing tests.**
  - `XrReplaceIT.replacesAllIndexedWorksWithoutHits`: three indexed works, a follow on a taxon that only the new
    release places them under; after the chain every work has `placed_release=200`, new `taxon_path`, unchanged
    `indexed_at` and `state`; the `hit` table is empty; `XR_FINISH` queued.
  - `XrReplaceIT.resumesAfterRedeliveryAndCatchesStragglers`: redeliver a batch → same result; set one work back to
    `placed_release=100` (a racing `MATCH_NAMES`) before the last batch → it is replaced too before `XR_FINISH`.
  - `XrReplaceIT.skipsPendingWorks`: a work with a pending run is left for its `MATCH_NAMES`.
  - `XrFinishIT.movesSupersededFollow`, `XrFinishIT.flagsMissingFollow`, `XrFinishIT.leavesFoundFollow`,
    `XrFinishIT.clbOutageDefersFollowCheck` (CLB 503 → task deferred, follow untouched, state still `SWITCHING`),
    `XrFinishIT.finishMarksCurrentAndPrunesOldMatches` (rows of a third, older release deleted; the previous kept).
  - `ClbClientTest.taxonLookupReadsSupersededBy`: 404 body `{"supersededBy":"VJTPP", …}` → `GONE`, `VJTPP`.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl store,indexer -am verify -Dit.test='XrReplaceIT,XrFinishIT' -Dtest=ClbClientTest`
- [ ] **Step 3: Implement.** Check the exact 404 body of `GET /dataset/{key}/taxon/{id}` for an archived id against
  `~/code/col/backend` (`TaxonDao.enrich404`) and stub that shape.
- [ ] **Step 4: Run them, then `mvn -B -pl store,indexer,web -am verify`.**
- [ ] **Step 5: Commit.** `feat(indexer): XR switch re-places every work and checks follows, without hits`

### Task 7: Dictionary per release, reloaded after the flip

**Files:**
- Create: `indexer/.../finder/ReloadableNameFinder.java`, `indexer/.../finder/BuildDictionaryHandler.java`
- Modify: `indexer/.../finder/FinderConfig.java`, `application.yml`
  (`nametracker.finder.dictionary-dir: ${NAMETRACKER_DICTIONARY_DIR:}`,
  `nametracker.finder.xr-zip-url: ${NAMETRACKER_XR_ZIP_URL:https://download.checklistbank.org/col/xr_latest_coldp.zip}`)
- Test: `indexer/src/test/.../finder/BuildDictionaryIT.java`, `ReloadableNameFinderTest.java`

**Interfaces:**
- Consumes: `ColDictionaryBuilder.fromXrZip(InputStream, CommonWords)` and `ColDictionaryIO` (finder-core);
  `BUILD_DICTIONARY` is enqueued by the flip (Task 5).
- Produces:
  - `ReloadableNameFinder implements NameFinder`: delegates to an `AtomicReference<NameFinder>`; `void swap(NameFinder)`.
    `FinderConfig` wraps the in-process pipeline in it. Choice at start: with `dictionary-dir` set, the file
    `dictionary-<currentKey>.bin` if present, else `FINDER_DICTIONARY`, else the newest `dictionary-*.bin` in the dir;
    a remote finder (`NAMETRACKER_FINDER_URL`) is never wrapped.
  - `BUILD_DICTIONARY {"key":k}`: with no `dictionary-dir` or a remote finder → INFO and done. Else streams the zip from
    `xr-zip-url` (no local copy of the zip; HTTP client streaming, not `PoliteHttp`'s buffered body), builds; if the
    built release key ≠ `k` → WARN ("latest XR zip is <x>, not <k>; keeping the current dictionary") and done. Else
    writes `dictionary-<k>.bin` (temp file + atomic rename) and, if `k` is current, swaps the finder (new pipeline from
    that file, same gnfinder URL and overrides). A dictionary change is not a finder upgrade (`FinderRollout` ignores it).

- [ ] **Step 1: Write the failing tests.**
  - `ReloadableNameFinderTest.swapChangesDelegate`.
  - `BuildDictionaryIT.buildsAndSwaps`: WireMock serves a tiny XR zip (build it in the test from a few `NameUsage.tsv`
    rows with release key 200, as the finder-core dictionary tests do) → `dictionary-200.bin` exists and the finder's
    `info().dictionaryKey()` is 200.
  - `BuildDictionaryIT.wrongReleaseKeepsCurrent`: zip of 150 for key 200 → no file, finder unchanged, WARN.
  - `BuildDictionaryIT.noDirIsNoop`.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl indexer -am verify -Dit.test=BuildDictionaryIT -Dtest=ReloadableNameFinderTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then `mvn -B -pl indexer -am verify`.**
- [ ] **Step 5: Commit.** `feat(indexer): build the finder dictionary per XR release and reload it after a switch`

### Task 8: Backfill from the OpenAlex snapshot

**Files:**
- Create: `store/src/main/resources/db/migration/V12__backfill.sql`, `store/.../backfill/BackfillMapper.java` (+ XML)
- Create: `indexer/.../backfill/SnapshotClient.java`, `BackfillHandler.java`, `BackfillFileHandler.java`
- Modify: `store/.../work/WorkUpsert.java` (`insertIfNew`), `application.yml`
  (`nametracker.snapshot.base-url: ${NAMETRACKER_SNAPSHOT_BASE_URL:https://openalex.s3.amazonaws.com}`)
- Test: `indexer/src/test/.../backfill/BackfillIT.java`, `BackfillFileIT.java`, `SnapshotClientTest.java`

**V12:** `backfill_run (id bigserial PK, from_year smallint NOT NULL, to_year smallint NOT NULL, snapshot_date date NOT
NULL, files_total integer NOT NULL, state text NOT NULL CHECK (state IN ('RUNNING','DONE')), created timestamptz NOT
NULL DEFAULT now(), finished timestamptz)` and `backfill_file (run_id bigint REFERENCES backfill_run ON DELETE CASCADE,
idx integer, url text NOT NULL, records_read bigint, records_kept bigint, works_created bigint, done_at timestamptz,
PRIMARY KEY (run_id, idx))`.

**Interfaces:**
- Produces:
  - `SnapshotClient`: `Manifest manifest()` reads `<base>/data/jsonl/manifest.json` (entity `works`: file urls
    `s3://openalex/…` mapped to `<base>/…`, sizes, record counts, `date`); `void stream(String httpsUrl,
    Consumer<JsonNode> each)` streams gzip JSON lines with constant memory (JDK `HttpClient` `ofInputStream`,
    `GZIPInputStream`, one Jackson parser per line). A truncated stream or reset throws (retryable).
  - `Optional<Long> WorkUpsert.insertIfNew(Work w)`: inserts only when neither `openalex_id` nor the normalised DOI
    exists; returns the new id. Ruling: existing works are never updated by the backfill (the stored copy came from
    the live API, which is newer than or as new as the quarterly snapshot; the weekly sweep refreshes them).
  - `BACKFILL {"fromYear":2000,"toYear":<this year>,"files":null,"fromFile":0}` (admin): reads the manifest, inserts
    `backfill_run` and its `backfill_file` rows (the slice `fromFile`…`fromFile+files-1` when `files` is given), enqueues
    `BACKFILL_FILE {"runId":r,"idx":i}` per file at `BACKFILL` (dedup `backfill:<r>:<i>`).
  - `BACKFILL_FILE`: skips a file with `done_at`; loads the map `openalex source id → source id` of active `CORE`/`BROAD`
    sources once; keeps a record when its `primary_location.source.id` is in the map, `publication_year` in range and
    `type` is `article` or `review`; `OpenAlexWorks.parse(record).toWork(sourceId, "BACKFILL")`, `insertIfNew`, and for a
    new work `RESOLVE_CONTENT {"workId":n}` at `BACKFILL` (dedup `content:<id>`). Inserts happen in transactions of 500
    works. At the end, one transaction sets the counters and `done_at`; the last file marks the run `DONE`.
  - Content for `BACKFILL` works stays on free routes (`OpenAlexTeiRoute` already excludes `BACKFILL`; add a test). The
    spec's "local GROBID on open PDFs" has no indexer route yet; none is added in M5.

- [ ] **Step 1: Write the failing tests** (WireMock serves a manifest and two small gzip files built in the test; sources
  registered: S1 CORE active, S2 BROAD active, S3 CANDIDATE):
  - `SnapshotClientTest.manifestMapsS3UrlsToBase`, `SnapshotClientTest.streamsLines`.
  - `BackfillIT.importsOnlyRegisteredJournalsYearsAndTypes`: records of S1 2005 article, S2 2001 review, S3 2005
    article, S1 1999 article, S1 2005 `dataset`, unknown source → exactly 2 works with `origin=BACKFILL`, each with a
    queued `RESOLVE_CONTENT` at `BACKFILL`; `backfill_file` counters 6 read / 2 kept / 2 created; the run `DONE`.
  - `BackfillIT.existingWorkUntouched`: a live work with the same DOI keeps its origin, title and `first_seen_at`.
  - `BackfillIT.rerunImportsNothingNew`: a second `BACKFILL` → 0 created.
  - `BackfillIT.sliceTakesOnlyGivenFiles`.
  - `BackfillFileIT.truncatedFileFailsAndRetryIsIdempotent`: a file cut mid-gzip → task fails (retry), `done_at` null;
    after serving it whole, the retry creates each work once.
  - `BackfillIT.backfillWorkNeverUsesOpenAlexContent`: a `BACKFILL` work of a CORE source with TEI → the TEI WireMock
    stub gets no request.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl store,indexer -am verify -Dit.test='BackfillIT,BackfillFileIT' -Dtest=SnapshotClientTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then `mvn -B -pl store,indexer,web -am verify`.**
- [ ] **Step 5: Commit.** `feat(indexer): backfill from the free OpenAlex snapshot`

### Task 9: Web and frontend: status, follow notes, digest footer

**Files:**
- Modify: `web/.../api/AdminStatusController.java` (+ the store queries it uses)
- Modify: `web/.../me/SubscriptionController.java` (DTO), `web/.../digest/DigestComposer.java` (+ templates)
- Modify: `frontend/src/pages/FollowingPage.tsx` (+ its API type), and its test
- Test: `web/src/test/.../AdminStatusIT`, `SubscriptionControllerIT`, `DigestComposerTest`, frontend test

**Interfaces:**
- Consumes: V10–V12 columns (Tasks 5, 6, 8), `finder_rollout` (Task 4).
- Produces:
  - `GET /api/admin/status` adds `xrReleases` (key, alias, state, current, keysTotal, keysLost, switchStarted,
    switchedAt), `backfillRuns` (id, years, snapshot date, files done/total, records read/kept, works created, state)
    and `finderRollouts` (hash, started, enqueuedAt, baseline).
  - Subscription DTO (`/api/me/subscriptions`) adds `targetStatus`, `xrNote`, `previousTarget`.
  - Following page: a `MOVED` follow shows its note in a neutral alert; a `MISSING` one a warning alert with the note
    and the existing remove action. Colours from the Mantine theme (`docs/brand/`).
  - Digest: a follow with `xr_note_at` after the user's previous sent digest (or after `digest_since` when none) adds one
    footer line with its label and note, in text and HTML.

- [ ] **Step 1: Write the failing tests.**
  - `AdminStatusIT.reportsXrBackfillAndRollouts`.
  - `SubscriptionControllerIT.listShowsXrNote`.
  - `DigestComposerTest.footerListsNewXrNotesOnce`: a note after the last digest → one line; a note older than it →
    none.
  - Frontend `FollowingPage.test.tsx`: `MOVED` and `MISSING` follows render their notes.
- [ ] **Step 2: Run and see them fail.** `mvn -B -pl web -am verify -Dit.test='AdminStatusIT,SubscriptionControllerIT' -Dtest=DigestComposerTest`; `cd frontend && npm test`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then `mvn -B -pl web -am verify` and `cd frontend && npm test && npm run build`.**
- [ ] **Step 5: Commit.** `feat(web): XR, backfill and rollout status; follow notes on the Following page and in digests`

### Task 10: Documentation

**Files:**
- Create: `docs/adr/0007-lifecycle-and-backfill.md`
- Modify: `docs/adr/0005-index-pipeline.md` (no throttle; pin section points at ADR-0007; bundle mismatch defers),
  `docs/adr/0006-accounts-and-alerts.md` (re-index hit rule amendment), `CLAUDE.md` (status, new env variables
  `NAMETRACKER_CLB_MATCH_PARALLELISM`, `NAMETRACKER_DICTIONARY_DIR`, `NAMETRACKER_XR_ZIP_URL`,
  `NAMETRACKER_SNAPSHOT_BASE_URL`, `nametracker.xr.max-lost-share`; task types `XR_SWITCH`, `XR_PREMATCH`, `XR_GATE`,
  `XR_CANCEL`, `XR_REPLACE`, `XR_FINISH`, `BUILD_DICTIONARY`, `REPROCESS_NAMES`, `BACKFILL`, `BACKFILL_FILE` with
  payloads and admin curl examples; remove "10 requests a second"), `docs/design.md` (roadmap: M5 split, status)

- [ ] **Step 1:** ADR-0007 records the decisions of the spec as built, with every ruling made during the tasks (read
  the ledger), in the style of ADR-0006.
- [ ] **Step 2:** Check every env variable and task type named in `CLAUDE.md` exists in code (`grep`).
- [ ] **Step 3: Commit.** `docs: ADR-0007 lifecycle and backfill, CLAUDE.md`

### Task 11: Dev check (controller)

Not dispatched; the controller runs it against the dev run (web 18080, indexer 18081, public CLB API, dictionary in
`eval/work/`) after rebuilding and restarting both apps (`docs/m4-dev-check.md` shows the routine). Record the result in
`docs/m5-dev-check.md` and commit it.

- [ ] **M5a switch:** count hits; enqueue `XR_SWITCH {"key":315557}` by admin; poll `GET /api/admin/status` and, every
  few minutes, the `/api/works` total (it must never drop); at the end every `INDEXED` work has `placed_release=315557`,
  `GET /api/xr` answers 315557, the Carabidae taxon page works, the hit count is unchanged. Then enqueue `XR_CHECK`
  by hand: it switches back to 316691 the same way, again with no new hit.
- [ ] **Reprocess:** `REPROCESS_NAMES {"source":<a journal id>,"force":true}`; its works stay listed throughout and end
  re-indexed (new `finder_run` ids or the same run with new `indexed_at`).
- [ ] **M5b slice:** `BACKFILL {"fromYear":2000,"toYear":2026,"files":3,"fromFile":<recent partition>}`; works of
  registered journals arrive with `origin=BACKFILL` and reach `INDEXED`; `api_usage` shows no OpenAlex spend from them;
  a second identical `BACKFILL` creates 0 works.
- [ ] **Verdict** and commit `docs: M5 dev check`.
