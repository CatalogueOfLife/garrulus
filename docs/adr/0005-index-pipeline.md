# ADR-0005: Index pipeline — spool, XR pin, placement, salience, categorisation, API

Date: 2026-11-15. Status: accepted.

## Context

M3 turns resolved works into an index: `FIND_NAMES` runs the finder and stores mentions, `MATCH_NAMES` matches every
name on the COL XR through the CLB API, places it, adds its code and `TaxGroup`, and writes one `work_name` row per name
with a salience score; the work ends `INDEXED`. A nightly `REBUILD_STATS` writes `name_ubiquity` and `taxon_stats`, and
`CATEGORISE_SOURCES` recomputes the journal categorisation. `web` serves the public API and the React pages. This ADR
records the calls made while building it (plan `docs/superpowers/plans/2026-11-15-m3-match-and-index.md`) and the facts
verified against the live CLB API on 2026-11-15.

## Decisions

### Closed full text waits in a transient spool, at most 14 days

- Full text that is not open-licensed is never written to the blob store (design: keep full text only for open-licensed
  works). The finder still has to read it once.
- `RESOLVE_CONTENT` writes the chosen document of a closed full text to the spool (`nametracker.content.spool-dir`,
  default `./data/spool`) as `<workId>-<docSha>.json.gz`, and only when it enqueues `FIND_NAMES`. `FIND_NAMES` reads it,
  commits the mentions, enqueues `MATCH_NAMES` and then deletes the file.
- Anything left behind (a task that never ran, a crash) is purged after 14 days (`nametracker.content.spool-max-age`),
  daily at 05:30 UTC (`Spool` itself; `REBUILD_STATS` no longer purges, so there is one purge with one configured age).
- If the spool file is gone, `FIND_NAMES` falls back to the rebuilt abstract and records `{"fallback":"abstract"}` in
  `work.finder_diagnostics`. A repeat `FIND_NAMES` for a document already named (same `names_doc_sha`) is a no-op, so a
  redelivery after the spool file was deleted never replaces full-text mentions with abstract ones. This holds even under
  a new finder version; M5's reprocessing adds a force flag.
- Snippets are cut 80 code points either side of the mention, at the block's edges and whole words, and capped at 200
  code points. A classification context's `verbatim` can be a whole heading block, so it is capped at 200 code points
  as well. Only snippets and derived data outlive the spool.
- The full text of a work is its newest non-`ABSTRACT` content row (by `fetched_at`, then id): content rows are kept
  per (work, route) and never removed, so a later, better route wins. `FIND_NAMES` and `/api/works/{id}` use the same
  rule (`Content.newestFullText`).

### `mention` is not partitioned yet

The design suggests partitioning `mention` by work year. M3 keeps one table with indexes on `work_id` and
`name_string_id`. The dev index has tens of thousands of works; partitioning pays off only at the backfill's 10^8 rows,
and the partition key (year) is not known for undated works. Revisit with M5's backfill. A later partitioning is a
migration of one table; nothing outside `IndexMapper` depends on its layout.

### The XR is pinned at the first key; switching is M5

- `XR_CHECK` (daily `0 0 6 * * *` UTC, ShedLock `xr-check`, dedup key `xr-check:<date>`, not behind the harvest switch)
  reads `GET /dataset/3LXR`. The first key it sees becomes the `current` row of `xr_release`; a later key is only
  recorded (`current=false`) and logged. On 2026-11-15 that was 316691, "COL26.10 XR".
- Every match, cache entry and `taxon_path` refers to the pinned key. Switching the pin means re-matching every name and
  rewriting every `work_name` without notifying anybody; that is M5's XR release workflow.
- Matching uses `GET /dataset/{key}/match/nameusage` with `mode=RELAXED`, `scientificName`, `rank`, `authorship` and the
  hints `kingdom`…`genus`, at most 10 requests a second. The result is cached in `name_match` under
  (`name_string_id`, `hint_key`, `release_key`); `hint_key` is the sorted hints plus the authorship. The `code`
  parameter is not sent yet.
- `taxon_path` is the usage id, then the ids of the response's `classification[]`. For a synonym `classification[]` is
  the accepted lineage (verified 2026-11-15: *Felis leo* gives `CFSCR`, then accepted `4CGXP` *Panthera leo*, `6DBT`,
  …, Felidae `623RM`), so a work naming a synonym appears on the pages of the accepted taxon and its ancestors.
- CLB sends no `group` in today's match response; `ClbMatch` reads it when present, otherwise `TaxGroupAnalyzer`
  computes it.
- A CLB or gnfinder outage defers the task by 30 minutes without using an attempt (`ServiceOutageException`); nothing
  goes `DEAD` because of an outage. Only a real outage counts:
  - gnfinder: a failure to connect (refused, unknown host, connect timeout) or HTTP 502/503/504. Any other status, a
    read timeout or a connection that breaks after the request was sent fails the task like any error, so a document
    gnfinder always chokes on ends `DEAD` where it can be seen instead of being retried forever. A finder service
    (`RemoteNameFinder`) is unavailable on 503 and on connection failures only.
  - CLB: a match call that still fails after `PoliteHttp`'s retries is followed by a probe, `GET /dataset/3LXR`. If
    the probe answers, CLB is up and the name is at fault: it is placed `NONE` (a context element counts as unmatched),
    logged as a WARN with the name and not cached, and the work goes on. A 4xx on one match call is treated the same
    way without a probe. Only when the probe fails too is it an outage.
  - Every outage deferral of a task first enqueued more than 24 hours ago is logged as a WARN (task id, type,
    service), so a task that waits for days is noticed.

### Placement rules

`Placer.place` decides one `placement_method` per name and work:

| CLB result for the name | Placement |
|---|---|
| `exact`, `variant` or `canonical` with a usage | `MATCH`: the usage and its path |
| `higherrank` to a **genus**, for a binomial, trinomial, infrageneric or abbreviated name (an expanded "U. microps") | `GENUS`: the genus and its path (an unknown or new species) |
| `higherrank` to a rank above genus, or to a species (a trinomial whose subspecies is missing) | the context fallback below |
| `none` or `ambiguous` | the context fallback below |
| an OTU (BOLD BIN, UNITE SH) | `NONE`, never sent to CLB |

- **Context fallback** (`VERBATIM_CONTEXT`): every element of every classification context of the work is matched on
  its own, without hints, as its own `name_string` (kind `UNINOMIAL`). The lowest-ranked element with an exact, variant
  or canonical match wins; unranked and "other" ranks never beat a ranked one, and of equal ranks the later one wins
  (a verbatim classification lists higher names first). The context is the one of the name's first mention that has
  one, else the work's document-scope context. Without a matched context the name is `NONE`.
- All contexts are matched even when every name matches directly, so the "how does the literature classify X" view has
  its data. A context element is asked with the rank stored on its `name_string` (the first rank it was seen with),
  not the rank of the context at hand: the cache key has no rank, so a cached match must always answer the same query.
- The name page (`/api/names/{id}`) shows, of the name's `name_match` rows on the current release (one per hint set),
  the one of the usage its `MATCH`/`GENUS` `work_name` rows point at most often, else the hintless row, else a row with
  a usage. Most names are matched with context hints or an authorship and have no hintless row.
- **Hints** for the name's own match come from the same context: only the ranks `kingdom`…`genus`.
- **Code and `TaxGroup`:** the match's code, else the group's single code; the group from the response, else
  `TaxGroupAnalyzer` on the usage and its classification. An unplaced name gets its group from the verbatim context's
  ranks when there is one.

`higherrank` above genus is deliberately not a placement: "Carabus xyzzyus" landing on genus *Carabus* is useful, but a
name landing on a family or order says only that CLB did not know the genus, and the article's own classification is
then the better evidence.

### Salience with untuned defaults

```
s = title*inTitle + abstract*inAbstract + act*hasAct + heading*inHeading
    + share*(bodyCount / maxBodyCountInWork) + repeat*min(1, (bodyCount-1)/4)
    - ubiquity*min(1, ubiquityShare / ubiquityFullShare)
salience = clamp(s, 0, 1); main_subject = salience >= threshold
```

- Defaults in `nametracker.salience.*`: `title=0.40`, `abstract=0.20`, `act=0.35`, `heading=0.15`, `share=0.25`,
  `repeat=0.15`, `ubiquity=0.50`, `ubiquity-full-share=0.02`, `threshold=0.35`. They are **not tuned**. The design wanted
  the threshold tuned on curated main-subject labels in M3; Markus moved all tuning (finder, salience) to a final
  finder-tuning stage after M3, once the curated set is annotated.
- Counts exclude `REFERENCE` blocks. `inAbstract` is an `ABSTRACT` block, or the whole document for an abstract-only
  work. A name only in references scores 0 and is never a main subject; an act on a reference mention gives no act
  weight and does not set `work.has_acts` (it still shows in the name's `act_types`).
- `ubiquityShare` is the name's share of indexed works of the last 5 calendar years (`name_ubiquity`, nightly), 0 for a
  name without a row. A lab organism mentioned in 2% or more of all works loses the full 0.50.
- **Cold start:** salience is computed once, when the work is indexed. Works indexed before `name_ubiquity` is
  meaningful (the first nights of the dev run, any fresh install) carry no ubiquity penalty, and the nightly rebuild
  does not rescore them. A future `RESCORE` task, planned for the finder-tuning stage, recomputes salience and the
  main-subject flags of indexed works from their stored `work_name` counts.
- `work.codes` and `work.taxgroups` aggregate the main-subject names only, `taxgroups` with every ancestor group, so a
  filter on Insects is an array contains.

### Journal categorisation only after 20 indexed works

- `CATEGORISE_SOURCES` runs after the nightly `REBUILD_STATS` (dedup key `categorise:<date>`). It recomputes a source
  only when it has at least 20 `INDEXED` works published in the current year or the two before.
- Groups and codes with a share of at least 20% of the main-subject, non-reference `work_name` rows are kept, groups as
  `{"Coleoptera":0.62,…}` (2 decimals). `act_rate` is the share of works with acts; a source is `TAXONOMIC` with
  `act_rate >= 0.10` or tier `CORE`, else `GENERAL`. `name_density` is the mean number of distinct non-reference names
  per work.
- There is no topic-based group seeding: until a source has 20 indexed works its taxonomic groups are empty and the
  other fields stay as seeded. A recomputed source with no named groups gets empty `taxgroups`.

### A hand-written OpenAPI file

`web/src/main/resources/static/api/openapi.yaml` (OpenAPI 3.1) is written by hand and served at `/api/openapi.yaml`.
No springdoc: it would add a dependency and runtime reflection for a dozen GET endpoints, and its generated schemas are
not what we want to promise to portal and third-party callers. `OpenApiCoverageTest` keeps the file honest in both
directions: every public GET mapping under `/api` (outside `/api/admin`) must be a path in the file, and every path in
the file must be a mapped GET. Parameters and schemas are not checked.

### The browser calls CLB directly for the taxon picker and the taxon header

`web` never calls external services (it only reads the index, the blob store and `taxon_stats`). The taxon picker uses
`GET https://api.checklistbank.org/dataset/{xrKey}/nameusage/suggest`, and the taxon page header (name, rank,
classification) uses `/dataset/{xrKey}/taxon/{id}` and its classification, with the pinned key from `GET /api/xr`. CLB
already serves these with CORS, and doing them server-side would add a CLB dependency and a cache to the always-on
`web`. `/api/taxa/**` itself sends CORS headers for the portal origins only (`nametracker.web.cors-origins`), GET only,
for the planned portal literature widget.

### Smaller calls

- `FIND_PENDING` (`{"limit":n}`) is an admin task that enqueues `FIND_NAMES` for up to n `CONTENT_RESOLVED` works the
  finder never saw, highest work id first.
- **Depth-first queue order.** The queue claims by priority, then `run_after`, then id. A follow-up
  (`RESOLVE_CONTENT` → `FIND_NAMES` → `MATCH_NAMES`, `REBUILD_STATS` → `CATEGORISE_SOURCES`) inherits both the priority
  and the `run_after` of the task that enqueues it, so it takes its parent's place in the queue and runs before the
  peers queued after the parent. A work therefore goes all the way to `INDEXED` before the next one starts, instead of
  every queued `FIND_NAMES` of a large backlog running before the first `MATCH_NAMES` (which made the index look empty
  for days in the M3 dev run). Peers enqueued in one transaction share a `run_after`; among them the id decides, so a
  follow-up still waits behind those few.
- `REBUILD_STATS` runs at `LIVE` priority (`0 30 4 * * *` UTC, `nametracker.stats.cron`, ShedLock `stats`),
  regardless of the harvest switch, so a `REPROCESS` backlog cannot hold up the nightly stats.
- The taxon filter and taxon pages ignore reference-only names; the name filter keeps them, because the name–literature
  index should list every place a name appears. `WorkSummary.mainNames` lists main-subject names only.
- `GET /api/taxa/{id}/works` defaults to `mainSubjectOnly=true`.

## Consequences

- A work whose closed full text was resolved before M3 (no spool yet) is named from its abstract, with
  `fallback=abstract` in its diagnostics. Content re-checks are scheduled only for `ABSTRACT_ONLY` works, so such a
  `FULLTEXT` work stays named from its abstract; nothing recovers it automatically. A one-off re-resolve fixes it:
  enqueue `RESOLVE_CONTENT` for every work whose `finder_diagnostics` has `fallback=abstract`; the closed full text is
  fetched again, goes to the spool and is named.
- Main-subject flags, taxon pages, facets and the categorisation all depend on the untuned salience defaults. Expect
  false main subjects (common names repeated in the body) and misses (a taxon named once in the title of a long paper)
  until the tuning stage.
- The pinned XR goes stale with every monthly release until M5 adds the switch; new names in later releases fall back
  to `GENUS` or the context.
- The categorisation stays at its seed values for small or newly onboarded journals for some weeks.
