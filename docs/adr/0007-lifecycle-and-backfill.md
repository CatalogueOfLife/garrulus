# ADR-0007: Lifecycle and backfill — XR switch, finder reprocessing, snapshot backfill

Date: 2026-11-17. Status: accepted.

## Context

M5 makes the index live through the monthly COL XR release and the finder's own versions, and fills it from the free
OpenAlex snapshot. The design is `docs/superpowers/specs/2026-11-17-m5-lifecycle-and-backfill-design.md`; this ADR
records the decisions as built, including the rulings made while building. Constraints that bind everything below:

- A re-index, a finder rollout or an XR switch never takes a work out of the listings, and an XR switch creates zero hits.
- No paid service: the backfill and all reprocessing use free routes only.
- Every handler is idempotent under redelivery (the queue is at-least-once). Migrations are expand-only (V8 to V13).

M5a (lifecycle: matching, pending index, reprocessing, XR switch) and M5b (backfill) are built and tested locally. The
full backfill runs later on the GBIF VM.

## Decisions

### Matching is unthrottled; a bundle of another release defers

- `api.checklistbank.org` has no request interval any more (ADR-0005 said at most 10 requests a second). Retries and the
  Garrulus User-Agent stay. `garrulus.clb.match-parallelism` (env `GARRULUS_CLB_MATCH_PARALLELISM`, 8) is the
  number of parallel match calls inside one `XR_PREMATCH` task.
- `MatchCache.match(id, query, releaseKey)` takes the release key; `WorkPlacer.place(workId, releaseKey)` is the one
  placement code for MATCH_NAMES, the pre-match and XR_REPLACE. `MatchQuery.fromHintKey` rebuilds a query from a stored
  `name_match` key.
- A local bundle (`match-base-url`) is for development only. When the bundle serves another release than the pinned one,
  `XrMismatchException` is turned into a `ServiceOutageException`: `MATCH_NAMES` is deferred 30 minutes without using an
  attempt and no longer ends `DEAD`. `XR_CHECK` logs the new release at INFO ("switch by hand with XR_SWITCH") and
  never starts an automatic switch with a bundle configured.
- Ruling R2: `MATCH_NAMES` asks `currentVerified()` for nameless works too (it needs a release key for `placed_release`);
  they therefore wait out a CLB outage like the others.

### Re-indexing: pending index and one-transaction swap

- A work that is `INDEXED` (or `NOTIFIED`, unused) and gets a new `FIND_NAMES` result keeps its state, its live rows and
  its listing. The new mentions and contexts go to `mention_pending` and `classification_context_pending` (V8, ids from
  the live sequences), and `work.pending_finder_run`, `pending_doc_sha` and `pending_diagnostics` record what they are.
  Any other state writes the live tables as before.
- "Already named" compares with the pending run and document sha when a pending index exists, so a repeat is a no-op and
  `RESOLVE_CONTENT` does not name a document twice.
- `MATCH_NAMES` places the pending rows. `WorkIndexer`, under the work lock, swaps in one transaction: delete live
  mentions and contexts, move the pending rows keeping their ids, adopt run, sha and diagnostics, rewrite `work_name`,
  set `indexed_at` and `placed_release`.
- **Row-set token (R3).** `WorkPlacer` reads `IndexRows(pending, finderRun, docSha)` before placing and returns a
  `PlacedWork`. The indexer compares it with `indexRows()` under the lock; on a difference it writes nothing, enqueues no
  `EVALUATE_HITS` and returns false (the newer `FIND_NAMES` has queued its own `MATCH_NAMES`). Placements are only ever
  written against the row set they were computed from.
- **Release check.** `WorkIndexer.index` and `replace` refuse a release that is no longer current, under a share lock
  on the current release row, so no work is placed in the old release once the flip has committed. XR_FINISH also
  re-enqueues `XR_REPLACE` if a listed work is still unplaced. Lock order is work, then release, everywhere.
- A re-indexed document without text still replaces the old names (`NO_TEXT_SHA`, empty swap).

### Re-index hit rule (amends ADR-0006)

A re-indexed work gives a follow an `ARTICLE` hit only if the follow did not match the previous index. At swap time the
indexer stores `work.reindex_prev_subscriptions` (ids of every subscription matching the old rows, paused ones
included, by `SubscriptionsMatchingWork`). `EVALUATE_HITS` skips a new `ARTICLE` for those ids. Window, `since_year`,
one `ARTICLE` per (follow, work) and `NEW_NAMES` are as in ADR-0006. A follow made after the work was first indexed gets
nothing from an unchanged re-index; it gets a hit only when the re-index newly matches it.

- **Every `MATCH_NAMES` of a listed work** captures the previous matches, with or without a pending index: a
  `MATCH_NAMES` that re-indexes the live rows (a plain `FIND_NAMES` of a named document, a redelivery, the sweep below)
  sets `indexed_at` again and must not make later follows look new.
- **Text level of the old index (V13).** `RESOLVE_CONTENT` writes the new text level of a listed work at once, while its
  live index stays the old (abstract) one until the swap. The capture therefore judges the old index at the level it was
  built from: `work.names_text_level` (written with the live index by `FIND_NAMES`; for a pending index into
  `pending_text_level`, which the swap adopts). V13 fills `names_text_level` from `text_level` for named works; a null
  falls back to `text_level`. Salience's abstract-only flag (`WorkIndexer.aggregate`) uses the same level. So a follow
  with `include_abstract_only=false` gets its `ARTICLE` when the full text index replaces the abstract one.
- **Placed in the release being indexed (R10).** The capture reads the live rows placed in the release the new index is
  matched in. When the live index is still placed in an older release (a re-index placed across the flip),
  `WorkPlacer.place` also places the live rows in the new release (`PlacedWork.live`, mostly pre-match cache hits,
  before the transaction); under the work lock the indexer checks the live row set (`liveIndexRows`) is unchanged,
  writes those placements into `work_name`, captures, then swaps. A changed live row set writes nothing
  (`LIVE_CHANGED`) and `MATCH_NAMES` is enqueued again under a fresh key. Without a pending index the rows to index
  are the live rows, placed in the new release, and serve directly. So the switch itself makes no follow look new.

### Finder reprocessing

- **`FIND_NAMES` `force`** skips the "already named" no-op, so the same document runs through the current finder. For a
  listed work the result goes to the pending index. If the work is `FULLTEXT` named but its text is gone (the loader
  falls back to the abstract), nothing is written and `RESOLVE_CONTENT` is enqueued instead.
- **`RESOLVE_CONTENT` `freeOnly` / `reprocess`.** `freeOnly` skips paid routes (`ContentRoute.paid()`; the OpenAlex TEI
  route is paid). `reprocess` that finds no free full text writes nothing and keeps the names (INFO). A route that is
  down (a retryable problem, `HarvestOutage.retryable`) is an outage (30 minutes, no attempt), not "no text"; a route
  that failed for good (a 4xx, an unreadable answer: the same failure on every try) counts as "no text", so it is never
  deferred for ever. With a text found, it stores as usual and enqueues `FIND_NAMES {"force":true,"refetched":true}`.
- **`refetched` guard.** A `FIND_NAMES` marked `refetched` whose text is gone again keeps the names with a WARN instead
  of asking for another re-fetch (no `FIND_NAMES` / `RESOLVE_CONTENT` loop). Only named works are re-fetched.
- **`REPROCESS_NAMES`** walks works the finder has seen, by id, in batches (default 1000, at most 10000), with filters
  (`source`, `workIds`, `yearFrom`, `yearTo`). Without a filter and without `"all":true` it does nothing (WARN).
  It enqueues forced `FIND_NAMES` at `REPROCESS`; a full batch enqueues the continuation.
- **Rollout.** `PipelineConfig.epoch` is part of the canonical config JSON and so of `configHash`. At start
  (`ApplicationReadyEvent`) `FinderRollout` locks `finder_rollout` (V9) and: no row at all means this hash is recorded as
  the baseline; a new hash is recorded and enqueues `REPROCESS_NAMES {"all":true,"rollout":hash}` (dedup `rollout:<hash>`);
  a known hash does nothing. The dictionary key is not part of the comparison. Switch:
  `garrulus.finder.rollout-on-start` (true; the ITs set it to false). A finder not yet ready (empty dictionary
  directory) skips the check.

### XR switch

One monthly release becomes current without a gap and without a hit. States of `xr_release.state` (V10): `SEEN`,
`PREPARING`, `PREPARED`, `SWITCHING`, `CURRENT`, `RETIRED`, `REFUSED`; `current` stays the readers' flag. Every step that
decides locks all release rows (`FOR NO KEY UPDATE`), so steps are serial; that lock conflicts with the indexer's
share lock (the flip waits for in-flight indexing) but not with the key share lock of a `name_match` insert, so the
pre-match and `MATCH_NAMES` are not held up by a step.

- **Detect.** `XR_CHECK` enqueues `XR_SWITCH {"key":k,"auto":true}` (dedup `xr-switch:<k>`) for a key other than the
  current one (a lower key too, a switch back) unless it is REFUSED, another switch runs, or a bundle is configured.
  An `auto` switch skips a REFUSED key; an admin switch (no `auto`) clears the refusal.
- **Generation.** `startSwitch` increments `xr_release.switch_gen`. `XR_PREMATCH` and `XR_GATE` carry `gen` and stop
  silently when it differs, so the stale chain of a cancelled switch cannot mark the new one PREPARED.
- **Pre-match.** `XR_PREMATCH` walks the current release's `name_match` keys (name, rank, hint key) and matches them
  against k with `match-parallelism` threads, filling the cache. A name CLB cannot match (probe fine) is counted, not
  stored; so is a key that cannot be asked (a bad stored hint key) or whose answer cannot be read (WARN with the key),
  so one bad key cannot stop the pre-match and leave the switch PREPARING. The last batch sets PREPARING to PREPARED (conditional on the generation) and enqueues `XR_GATE`.
- **Gate.** Counts the current release's keys with no match in k (or unmatchable) among rows with `matched_at` before
  `switch_started` (keys matched during the pre-match are not lost). Over `garrulus.xr.max-lost-share` (0.05): the
  release is REFUSED (note `refusedBy:"gate"`, ERROR with both numbers) unless the switch was started with `force`.
  `keys_total` and `keys_lost` are stored.
- **Flip.** In one transaction the old release becomes RETIRED (or REFUSED with `refusedBy:"rollback"` when the switch
  was started with `refuse`), k becomes `current` and SWITCHING; the old key is written into k's note as `previous`;
  `BUILD_DICTIONARY` and `XR_REPLACE` are enqueued. New `MATCH_NAMES` already pin k.
- **Refuse / force / cancel.** `XR_SWITCH {"refuse":true}` is the rollback of a bad release: the old key is marked REFUSED
  and kept out of the automatic switch. `XR_CANCEL {"key","refuse"}` returns a PREPARING, PREPARED or gate/admin-REFUSED
  key to SEEN (or to REFUSED with `refuse`); it is an ERROR no-op after the flip, for CURRENT and RETIRED releases and for
  a rollback hold, which only an admin `XR_SWITCH` lifts. R4: `refuse:true` on a SEEN key refuses it ahead (kept).
- **Rollback hold (R5).** A switch back is a new switch: it is refused while another one is SWITCHING until `XR_REPLACE`
  has ended.
- **Replace.** `XR_REPLACE {"key","batch":500}` takes listed works with no pending index whose `placed_release` is not k
  (`IndexMapper.unplacedWorkIds`: a union of ordered ranges of the partial index `work_placed_release_idx`, so each batch
  reads about `batch` rows), places each (cache hits from the pre-match) and calls `WorkIndexer.replace`. `replace`
  rewrites `work_name`, codes, taxgroups, `has_acts` and `placed_release` and never touches `state`, `indexed_at` or
  `first_indexed_at`; it enqueues no `EVALUATE_HITS`. A work that has a pending index is skipped (its `MATCH_NAMES`
  places it against k). A non-empty batch enqueues the next; an empty batch enqueues `XR_FINISH`.
- **Finish.** `XR_FINISH` first, without a lock, logs the listed works with a pending index not yet placed in k and
  enqueues `MATCH_NAMES` (dedup `match-pending:<id>:<run>:<sha12>`) for every listed work with a pending index and no
  QUEUED or RUNNING `MATCH_NAMES` (its task ended DEAD, or its enqueue was swallowed by the dedup key of a
  `MATCH_NAMES` still running that had already swapped an earlier pending index in); at most 100,000 per run. The
  admin task `MATCH_PENDING {"limit":n}` (default 1000) runs the same sweep. Then, with the releases locked, it checks
  that no listed work is unplaced (else it re-enqueues `XR_REPLACE`). Then:
  - **Follow check.** TAXON follows (paused too) not yet noted since the switch are checked against k. An id is proven
    present by a `name_match` row of k with it as `usage_id`, or by a `work_name` row of a work placed in k with it in
    `taxon_path`; anything else is looked up with `GET /dataset/{k}/taxon/{id}` on the CLB API (never the bundle). FOUND
    changes nothing; an archived 404 with `usage.supersededBy` moves the follow (`target_status` MOVED, `previous_target`,
    note); a 404 without a successor flags it MISSING with a note (V11). The successor is not verified in k. If the user
    already follows the successor, the old follow is flagged MISSING with a note saying so. A MISSING follow found again is
    set back to OK. The notes show on the Following page and once in the next digest that is sent (R9: a user with no
    hits learns of it on the page only).
  - **R6.** A followed id that is now a synonym (404 with `acceptedId`) counts as FOUND: works matched to the synonym carry
    the id in `taxon_path`.
  - **R7.** A 4xx from the taxon lookup for the dataset itself is an ERROR, skips the follow check and still finishes, so
    a bad release cannot block later switches.
  - **Finish and prune.** In one transaction (releases locked): `REBUILD_STATS` is enqueued (dedup `stats:xr:<k>`) and k
    becomes CURRENT. Afterwards `name_match` rows of every release except k and the previous one are deleted, never those
    of a release in PREPARING, PREPARED or SWITCHING. `xr_release` rows are never deleted. A redelivered `XR_FINISH` of an
    already CURRENT release only prunes.
  - **R1.** `REBUILD_STATS` may chain `RESCORE_SALIENCE` and `EVALUATE_HITS` through the existing ubiquity mechanism. That
    is independent of placements, and the `created` before `indexed_at` rule still applies. The switch itself creates no hit.
- **Dictionary per release.** `BUILD_DICTIONARY {"key":k}` downloads `garrulus.finder.xr-zip-url`, reads the zip as a
  stream, checks that its release key is k (otherwise WARN, nothing written), writes `dictionary-<k>.bin` atomically to
  `garrulus.finder.dictionary-dir` and swaps the in-process finder (`ReloadableNameFinder`) if k is current. An existing
  file is swapped in without a download. Only a failed connection, a broken download stream and 5xx/429 are outages;
  any other failure keeps the current dictionary (WARN). The zip is read through `HttpURLConnection` (`StreamingGet`,
  shared with the snapshot client) with a read timeout (`garrulus.finder.xr-zip-read-timeout`, 5m): a download that
  stalls is a broken stream, so an outage, instead of a task kept RUNNING for ever by its heartbeats. The build runs in
  the indexer's heap: check `-Xmx` against the dictionary build's peak before the GBIF deploy. Without a directory, or with a remote finder service, it is a
  no-op. At start the finder uses `dictionary-<currentKey>.bin`, else `FINDER_DICTIONARY`, else the newest file.
- **Bootstrap (R8).** With a directory and no usable dictionary the finder bean is empty (not ready): `FIND_NAMES` is
  deferred 30 minutes without an attempt, and at start the indexer enqueues `BUILD_DICTIONARY` for the current release.
  A fresh GBIF deploy therefore downloads one dictionary (about 1.4 GB) at first start.

### Backfill from the OpenAlex snapshot

- **Snapshot.** `SnapshotClient` reads the manifest at `<snapshot.base-url>/data/jsonl/manifest.json`, maps `s3://openalex/`
  file urls to the base url and streams each works file as gzip JSON lines.
- **Streaming with read timeout.** The body is read through `HttpURLConnection` with a read timeout
  (`garrulus.snapshot.read-timeout`, 5m). A stall mid-body is the same broken-off failure as a truncated gzip: the
  task fails, uses an attempt and reads the file again. A stalled response header is a retryable outage; the
  connection is closed on every failure.
- **`BACKFILL`** payload `{"fromYear":2000,"toYear":<this year>,"files":null,"fromFile":0}`. It inserts a `backfill_run`
  (V12), one `backfill_file` per file of the slice and one `BACKFILL_FILE {"runId","idx","records"}` task at priority
  `BACKFILL` (dedup `backfill:<r>:<i>`). Redelivery reuses a RUNNING run with the same years, snapshot date, first index
  and size.
- **`BACKFILL_FILE`** filters each record cheaply before parsing: the primary source must be an active CORE/BROAD source,
  `publication_year` in range, `type` article or review. It maps kept records as `OpenAlexWorks.parse(rec).toWork(sourceId,
  "BACKFILL")` and commits in transactions of 500 works, each with a `RESOLVE_CONTENT {"workId":n}` task at `BACKFILL`
  priority. Free routes only: `OpenAlexTeiRoute` skips `origin=BACKFILL` works.
- **`insertIfNew` never updates an existing work.** If the openalex id or the normalised DOI exists, nothing happens: no
  update, no content task. Existing LIVE works keep their origin, title and `first_seen_at`.
- **Counters.** `works_created` is added in each 500-work transaction (exact over failed and repeated passes); the file's
  records read and kept, and `done_at`, are written once at the end, under a lock on the run row, which turns the run
  DONE with its last file. If the records read differ from the manifest's count, a WARN is logged.
- **Repeated `BACKFILL`.** With the same parameters while the run is RUNNING it enqueues only the files not done: a file
  whose task is QUEUED or RUNNING keeps it, a file whose task is DEAD gets a new QUEUED task with the same dedup key (the
  DEAD row stays). A DONE run is never reused; the same call then makes a new run (and reads about 675 GB again, with
  zero new works).
- **Hits.** Backfilled works are subject to the alert window of ADR-0006; only a follow with an early `since_year` gets
  hits from them.

## Consequences / known gaps

- **R1 chain.** After an XR switch the next `REBUILD_STATS` can still cause a few hits through `RESCORE_SALIENCE`. The dev
  check counts hits after a switch and would show it.
- **Config revert.** A to B to A of the finder config does not re-roll: A's hash is already in `finder_rollout`, so
  nothing is enqueued; run `REPROCESS_NAMES` by hand. Overlapping rollouts both run in full (the work is done twice).
- **Baseline after bootstrap.** After a dictionary bootstrap the finder is not ready at start, so the rollout baseline is
  only recorded at the next restart. Restart once after the bootstrap before changing the finder config; otherwise the
  changed config becomes the baseline and is never rolled out.
- **Deploy during a backfill.** A restart while `BACKFILL_FILE` tasks read their files can use up their attempts (a
  file read again from the start each time); re-run the same `BACKFILL` to revive files that ended DEAD.
- **Shared manual continuation key.** Two simultaneous manual `REPROCESS_NAMES` runs whose batches end on the same work id
  share the dedup key `reprocess:manual:<lastId>` and the second chain is dropped.
- **Stale rows.** A `MATCH_NAMES` that ends `DEAD` while its work has a pending index leaves that work waiting until the
  next `XR_FINISH` sweep or a `MATCH_PENDING` (both skip a work whose `MATCH_NAMES` is still queued or running). A forced
  `FIND_NAMES` redelivered after it ran runs the finder again.
- **Follow notes.** An XR note waits for the next digest that has hits; a MOVED follow with a null note shows nothing.
- **Cost of a pre-match.** A full release is millions of unthrottled CLB calls (`match-parallelism` bounds the
  concurrency). The first one on the GBIF VM should be watched.
- **Large snapshot files.** A file up to about 1 GB that breaks off is read again from the start; many resets can take it
  to `DEAD` (re-run `BACKFILL` to revive it). A clean shutdown mid-file can use an attempt.
- **Prune scans.** The presence check for a followed high taxon uses the GIN index on `taxon_path`; its cost at 5M works is
  untested. The `unplacedWorkIds` ranges rely on `placed_release` only holding `xr_release` keys.
