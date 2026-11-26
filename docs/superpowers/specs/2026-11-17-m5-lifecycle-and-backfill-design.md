# M5: lifecycle and backfill — design

Date: 2026-11-17. Status: draft for Markus' review.

## Intent

Garrulus must stay correct without a person tending it, and it must cover the literature from 2000 on.

- **Lifecycle (M5a):** a new COL XR comes out every month. Garrulus moves its whole index to the new release on its own,
  without bothering anybody. A better finder can be rolled over the existing papers. Re-indexing a paper never makes
  it disappear from the site for a while.
- **Backfill (M5b):** the index grows from "papers harvested since the dev run started" to every paper from 2000 to now
  in the registered journals, about 4–5M works, from free sources only.

M5a is built and checked locally. M5b is built locally and checked on a slice of the snapshot; the full run happens
later on the GBIF VM (deployment is the last stage, "keep it all local until the end").

## Goals

1. **Automatic XR switch** (Markus, 2026-11-17: "switch to new col xr on its own"). When `XR_CHECK` sees that the XR
   (`3LXR`) is a different release than the current one, Garrulus pre-matches, switches and re-places every work with
   no human step. Zero hits come from a switch.
2. **Finder reprocessing.** A new finder version or configuration is rolled over all works in the background. An admin
   can also re-run the finder on a subset with a force flag.
3. **No gaps while re-indexing.** A work that is re-indexed (finder, full text arriving, title clean-up) stays listed
   with its previous names until the new ones are complete.
4. **Backfill job** `BACKFILL` (`fromYear`, `toYear`): reads the free OpenAlex snapshot and imports the works of the
   registered CORE and BROAD journals in that year range, then runs them through content, finder and matching at the
   lowest priority. Re-runnable for earlier years later.
5. **Matching without a throttle** (Markus, 2026-11-17: "drop the matching api throttling. It is designed to be fast").

## Non-goals

- Deploying anything, the GBIF VM, swapping a local CLB bundle in production (a deploy script in the deployment stage).
- Pre-2000 and BHL (M6), new harvest adapters, discovery and promotion.
- Finder or salience tuning (the tuning stage, after Markus fixes the gold).

## Anti-goals

- Relying on the paid OpenAlex API (Markus, 2026-11-17: "don't rely on the paid api"). The backfill uses the free
  snapshot and free full-text routes only: no OpenAlex list calls, no OpenAlex content downloads. Reprocessing never
  buys content.
- A switch or a reprocessing run that notifies people about papers they have already seen, or that pulls in papers a
  follow should not get (see "Hits" below).
- Half a release: a switch that stops halfway must resume, not leave the index split between releases for good.
- A switch to a broken release: if the new release suddenly fails to match many names that matched before, Garrulus
  stops before anything visible changes.
- Filling the VM's small local disk: the 675 GB snapshot is streamed, never stored.

## Constraints

- Two apps, one database; only the indexer runs this work. Everything is a task in the existing queue, resumable after
  a restart, idempotent under at-least-once delivery.
- Priorities: live harvest work stays at `LIVE`; switch and reprocessing at `REPROCESS`; backfill at `BACKFILL`. The live
  daily harvest keeps running during all of it.
- Schema changes are expand-only Flyway migrations (V8…), so a running `web` keeps working.
- GBIF disk (memory `nametracker-gbif-storage`): blobs on NFS, spool and Postgres on local disk. The full backfill
  adds about 150–200 GB to Postgres.
- `GET /api/xr` and the SPA show the current release; the CLB suggest and taxon calls of the SPA use it.

## Facts found while designing

- **The snapshot** (`s3://openalex`, readable anonymously over HTTPS at `https://openalex.s3.amazonaws.com/`, CC0,
  quarterly; the latest is 2026-10-14). Since November 2025 it lives under `data/jsonl/` and `data/parquet/`
  (`legacy-data/` is frozen). Works: 464,765,485 records in 1,898 gzipped JSON-lines files, 675 GB (median file 367 MB,
  largest 985 MB), partitioned by `updated_date`. `data/jsonl/manifest.json` lists every file with its size and
  record count and is written last. A record has the same shape as the API's work (`primary_location.source.id`,
  `publication_year`, `type`, `abstract_inverted_index`, `best_oa_location`, …), so the harvest's `OpenAlexWorks`
  mapping applies.
- **XR pin today** (ADR-0005): `XR_CHECK` records a new key with `current=false` and logs it. `name_match` is keyed by
  (`name_string_id`, `hint_key`, `release_key`), so a second release's matches can sit next to the current ones.
  `work_name` holds the placement (`usage_id`, `taxon_path`, `code`, `taxgroup`) of one release; `work` aggregates
  `codes[]`, `taxgroups[]`.
- **Local bundle** (ADR-0005, development only from M5 on): with `GARRULUS_CLB_MATCH_BASE_URL`, the bundle tells the release, and a bundle of
  another release than the pinned one makes `MATCH_NAMES` fail. CLB publishes a bundle artifact per release.
- **The re-index gap:** `FIND_NAMES` deletes the work's index and sets it back to `NAMES_FOUND` before `MATCH_NAMES`
  runs; the public API lists `INDEXED` works only, so the work vanishes for that time (about a minute in the dev run,
  hours in a backlog).
- **No reprocessing path:** `FIND_NAMES` is a no-op when the chosen document was already named by a current run,
  whatever the finder version.
- **Superseded COL ids:** CLB's 404 for a dead taxon id carries `supersededBy` (backend
  `docs/2026-11-09-superseded-id-redirects.md`; implemented, prod rollout pending).
- **CLB throttle:** `PoliteHttp` gives every non-loopback host a minimum interval; CLB matching runs at about 10 calls a
  second.

## Approaches

### The XR switch

1. **Pre-match, flip, then re-place in batches (chosen; Markus agreed 2026-11-17).** Match every distinct (name, hints)
   key against the new release into `name_match` while the site keeps reading the current release; check the result;
   flip the current release; re-place all works in batches. Costs: for a few hours some works show the old release's
   placement and some the new; COL ids are stable across monthly releases, so almost nothing differs visibly. No extra
   disk, resumable.
2. A full second copy of `work_name`, swapped in at once. Atomic, but doubles the largest table and needs a catch-up
   for works indexed during the build. Rejected: much more complexity for little gain.
3. One transaction over all rows (10^8 at full size). Rejected: locks and bloat.

### The backfill source

1. **Stream the JSON-lines snapshot over HTTPS (chosen, builder's call).** One task per file: stream, decompress,
   filter by journal, year and type, upsert. Uses the existing JSON mapping, no new dependency, no local copy. Cost:
   all 675 GB pass through the network once per run (a few hours of transfer at GBIF).
2. Parquet with column projection (for example DuckDB over HTTP range requests). Reads far fewer bytes for the filter,
   but adds a native dependency and a second mapping of the records. Rejected for now; a later optimisation if
   transfer becomes the bottleneck.
3. The OpenAlex API (about 25k list calls, roughly $2.50, or a few days inside the free daily allowance). Rejected:
   Markus does not want to rely on the paid API, and it would eat the live harvest's daily allowance.

## Design

### Matching without a throttle

- CLB match calls (base URL and local bundle) have no minimum interval. Other hosts keep theirs. The XR and taxon calls
  to `api.checklistbank.org` are rare and need none either; the CLB host is simply unthrottled.
- Throughput comes from concurrency: matching a work's names runs in parallel (bounded, `GARRULUS_CLB_MATCH_PARALLELISM`,
  default 8, builder may tune), plus the indexer's worker count. Retry, outage and probe rules of ADR-0005 stay.
- ADR-0005, `application.yml` and `CLAUDE.md` drop "at most 10 requests a second".

### Automatic XR switch

**Matching goes to the public CLB API in production** (Markus, 2026-11-17: "Go with the API. Once the app is locally
deployed in the gbif network overhead is minimal. Just for developments a local bundle helps"). The API serves every
release by key, so the new release is pre-matched while live matching stays on the current one. The local bundle
(`GARRULUS_CLB_MATCH_BASE_URL`) stays a development option: with a bundle configured, `XR_CHECK` only logs a new
release and does not switch on its own; the developer swaps the bundle and starts the switch with the admin command.
A bundle serving another release than the pinned one then defers `MATCH_NAMES` (30 minutes, no attempt used) instead
of failing it `DEAD`, so live tasks wait out a switch.

States of a release in `xr_release` (new column `state`): `SEEN`, `PREPARING`, `PREPARED`, `SWITCHING`, `CURRENT`,
`RETIRED`, `REFUSED`. Exactly one row is current. Every step is a task; all run at `REPROCESS`.

1. **Detect.** `XR_CHECK` (daily, as now) finds the `3LXR` key different from the current one. Different, not only
   higher: the XR alias moving back is followed too. One switch at a time: a new key seen during a switch waits until it ends. A key in state `REFUSED` is never switched to
   automatically (see Admin).
2. **Dictionary.** `BUILD_DICTIONARY` downloads `xr_latest_coldp.zip` (free), checks that it belongs to the new key,
   and writes `dictionary-<key>.bin` into `GARRULUS_DICTIONARY_DIR`. The dictionary is **not a gate** (my call): when
   the zip belongs to another release (only the latest XR is published under a stable URL, so a switch to an older
   release cannot get its own) or the build fails, the finder keeps its current dictionary, a WARN says so, and the
   switch goes on; names are matched against the new release either way. The in-process finder picks up the newest
   dictionary for the current release after the flip (builder's call how: reload on flip or on restart, logged). A
   dictionary change is recorded on finder runs but is **not** a finder upgrade and triggers no reprocessing (design.md
   open items). When `GARRULUS_FINDER_URL` points at a remote finder, Garrulus builds nothing and logs that the
   remote finder needs its own dictionary.
3. **Pre-match.** `XR_PREMATCH` walks the distinct (`name_string_id`, `hint_key`) keys of the current release's
   `name_match` in batches and matches each against the new release into `name_match`. Nothing visible changes.
4. **Gate.** When all keys are matched, compare: of the keys that matched on the current release (not `NONE`), how many
   are `NONE` on the new one. Above `garrulus.xr.max-lost-share` (default 0.05, builder's call) the release becomes
   `REFUSED`: no flip, an ERROR in the log with the numbers. An admin can force or skip it (below).
5. **Flip.** In one transaction: the new release becomes current (state `SWITCHING`), the old one `RETIRED`. From now on
   `MATCH_NAMES` of new works uses the new release (its cache is warm). `GET /api/xr` answers the new key.
6. **Re-place.** `XR_REPLACE` walks all works with an index (by id, up to the highest id at the flip; later works are
   placed on the new release anyway) in batches. Per work, in one transaction: placement, `taxon_path`, `usage_id`,
   code and tax group of each `work_name`, then the work's `codes[]`, `taxgroups[]`, and the classification contexts'
   matched taxa, from the new release's `name_match` rows. A key with no new-release row is matched on the spot. It
   writes no `indexed_at`, does not change the work state and enqueues no `EVALUATE_HITS`.
7. **Finish.** `REBUILD_STATS` (taxon stats, ubiquity, categorisation), then the follow check (below); the release
   becomes `CURRENT`. Old releases' `name_match` rows are kept for one more switch, then deleted (they are only cache).

**Admin** (`POST /api/admin/tasks` as today, or small endpoints, builder's call): start a switch to a given key (also
used to roll back), force a `REFUSED` release, cancel a switch before its flip. A switch after the flip is not
cancelled; a switch back is a new switch. **Rollback hold** (my call): a switch started by hand can mark the release it
leaves `REFUSED` (`refuse: true`), so the next daily `XR_CHECK` does not switch straight back to it; a later, different
XR key switches automatically as usual, and an admin switch to the refused key clears the hold.

**Follows on removed taxa.** After the re-place, every `TAXON` follow whose COL id is not in the new release is
looked up: when CLB reports `supersededBy`, the follow moves to that id and keeps its label with a note; otherwise it
is flagged (`subscription.target_status = MISSING`, V8) and keeps its old id, so it matches nothing new. The user sees
the move or the flag on the Following page and in the next digest's footer (one line per affected follow). No hits
come from it.

### Finder reprocessing

- **Automatic on upgrade.** At indexer start, if the finder's `configHash` differs from the one of the latest
  completed rollout, `REPROCESS_NAMES` is enqueued for every work the finder has seen (with or without names) at
  `REPROCESS` priority (dictionary key ignored, see above). It walks works by id in batches and enqueues `FIND_NAMES`
  with `force: true`. My calls: the trigger is `configHash` only, never the jar version (which changes with every
  deploy); a finder-core code change that should reprocess bumps an `epoch` number in `pipeline-defaults.json`, which is
  part of the hash; the first start with M5 records the current hash as the baseline and reprocesses nothing.
- **By hand.** `REPROCESS_NAMES` with a filter (`source`, `workIds`, `yearFrom`/`yearTo`, `all`) and `force`.
- **`force`** makes `FIND_NAMES` run even when the document was already named. Text comes only from what Garrulus may
  use for free: retained open text from the blob store, closed text still in the spool, or a free route re-fetch
  (Pensoft, Europe PMC, PMC); never OpenAlex content. A work whose full text is no longer free to get keeps its current
  names (counted and logged), rather than falling back to its abstract.
- **Hits** (my call, narrowing ADR-0006): a re-indexed work (reprocessing, full text arriving, title clean-up) gives a
  follow an `ARTICLE` hit only when it matches that follow now and did **not** match it with its previous index: it is
  new to the follow because the finding improved, which is Markus' wish of 2026-11-16. A re-index that changes nothing
  for a follow gives it nothing, so a full rollout after the tuning stage cannot flood every follow created since the
  first indexing. Window, `since_year`, the one-`ARTICLE`-per-follow rule and `NEW_NAMES` stay as in ADR-0006. The
  previous index is still at hand when the new one replaces it (see the next section). ADR-0006 gets this amendment.

### No gaps while re-indexing

A work being re-indexed stays `INDEXED` and keeps its current `mention`/`work_name` rows until the new ones are
complete; then they are replaced in one transaction. Builder's call how (for example: the finder writes the new
mentions under the new finder run and `MATCH_NAMES` swaps the run pointer and the `work_name` rows at once, deleting
the old run's rows). The public API, feeds and hit evaluation only ever see one complete version of a work.

### Backfill

- **`BACKFILL`** (admin, payload `fromYear`, `toYear`, optional `snapshot` date, default the latest manifest; optional
  `files` limit for a slice): reads `data/jsonl/manifest.json` over HTTPS, records a `backfill_run` row (V8: years,
  snapshot date, counts, state) and enqueues one `BACKFILL_FILE` per works file at `BACKFILL` priority.
- **`BACKFILL_FILE`** streams one file, decompressing and parsing line by line (constant memory, no local copy), and
  keeps a record when all hold:
  - `primary_location.source.id` is an active, registered `CORE` or `BROAD` source (read once per task);
  - `publication_year` is in `[fromYear, toYear]`;
  - `type` is `article` or `review` (the harvest's types).
  Kept records are upserted like harvested ones (`TitleText.clean`, Crossref-free), `origin = BACKFILL` for new works
  only (an existing work keeps its origin and `first_seen_at`). An existing work's fields are updated only when the
  snapshot record's `updated_date` is newer than what is stored (my call: a snapshot up to three months old must not
  overwrite fresher harvested data). A new work gets `RESOLVE_CONTENT` at `BACKFILL`
  priority. Per file it counts records read, kept and new into `backfill_run`. A file that fails (network) is retried;
  an outage is deferred like the harvest's.
- **Content for backfilled works:** free routes only (Pensoft, Europe PMC, PMC, local GROBID on open PDFs), never
  OpenAlex content, whatever the tier. Otherwise the abstract.
- **Hits:** none special-cased. ADR-0006 already says a follow with an early `since_year` gets the backfill's papers of
  its taxon (capped in the digest); default-window follows get none, because backfilled papers are old.
- **Snapshot age.** The snapshot is up to three months old; works updated after it are covered by the live harvest and
  the 400-day sweep, and upserts are idempotent, so overlap is harmless.
- **Throughput** (builder's estimate, to verify on the slice): the file tasks are network and gzip bound; the finder and
  matching backlog afterwards is the long part. The full run belongs on the VM, with several workers.
- **Admin status** shows backfill runs with per-file progress (`GET /api/admin/status`).

## Hits, in one place

| Event | Hits |
|---|---|
| XR switch (pre-match, flip, re-place, follow moves) | none |
| Finder reprocessing, full text arriving, title clean-up | only follows the work did not match before the re-index, within ADR-0006's window; `NEW_NAMES` as in ADR-0006 |
| Backfill | ADR-0006 rules; in practice only follows with an early `since_year` |

## Done when

- **M5a (local):** in the dev run, a switch from 316691 (COL26.10 XR) to the older 315557 (COL26.6 XR), started by the
  admin command, and the automatic switch back by `XR_CHECK`, both complete: every work placed on the new key, `GET
  /api/xr` and taxon pages on the new key, zero hits created, no work ever missing from `/api/works` during the run.
  A forced reprocess of one journal re-indexes its works without a gap.
- **M5b (local):** `BACKFILL` on a slice of snapshot files (a handful, a few GB) imports the matching works of the
  registered journals with `origin = BACKFILL`, they reach `INDEXED` through free routes only, the OpenAlex budget
  meter shows no backfill spend, and a re-run imports nothing new.
- **Full run (later, GBIF VM):** about 4–5M works indexed.

## Storage

`mention` stays one table (my call; ADR-0005 deferred partitioning to the backfill). Its access is by `work_id`, which
the indexes serve at 10^8 rows; undated works have no good partition key; converting later is possible offline if
measurements on the VM call for it.

## Left to the builder

- Batch sizes, parallelism defaults, the `max-lost-share` default, task and column names.
- How the finder reloads its dictionary, how the re-index swap is stored, the shape of admin endpoints.
- Whether the slice for the local check is chosen by files or by a source filter.
