# ADR-0008: Layered harvest — work identity, field precedence, routes, coverage

Date: 2026-11-19. Status: accepted.

## Context

Until M5 every work came through OpenAlex, directly or as the enrichment of a Crossref record. A journal OpenAlex does
not index, indexes late or indexes badly was invisible, and an OpenAlex outage or a change of its terms stopped the
whole intake. M6 adds more doors. The design is `docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md`;
this ADR records what M6a built and the rulings made while building, which override the spec where they differ.
Configuration and task types are in CLAUDE.md ("Harvesting by hand and admin endpoints", "Configuration"); they are not
repeated here.

M6a is built and tested locally; its dev check is `docs/m6a-dev-check.md`. Later: M6b (admins and admin pages, journal
registry, discovery, registration, classification, candidate sampling, ceased journals) and M6c (crosslinks, languages
and English titles, the mentioned-in filter, abstract fallbacks, the University of Copenhagen route).

Constraints that bind everything below:

- **Free only.** No layer needs a paid service or a key. OpenAlex stays (snapshot, daily window, TEI within its free
  allowance, ADR-0004) but is no longer the only door: Crossref no longer waits on it, and the other layers create works
  on their own. Every layer has a switch.
- Every handler is idempotent and every outage defers without using an attempt (ADR-0004). One `PoliteHttp` and
  `HostLimiter` per host, with the Garrulus user agent.
- Migrations are expand-only (V16 to V18).

## Decisions

### Layers

Six values of `Layer` (store module): `OPENALEX`, `CROSSREF`, `OAI`, `MAGTECH`, `EUROPEPMC`, `BLR`. Each harvest run has
its own `harvest_run.adapter` (`OPENALEX_LIVE` is kept for OpenAlex), its dedup key prefix and its switch
`nametracker.layers.<layer>.enabled` (default true). A false switch stops the layer's handler; its tasks wait instead
of failing. The layer schedulers (OAI 04:30, Magtech 04:45, Europe PMC 05:00, BLR 05:15 UTC) enqueue only for ACTIVE
routes of harvestable sources, one `LIVE` job per route, and sit behind the master harvest switch.

- **OAI-PMH** per journal or platform set, `from` = last run minus 3 days. HTTP 5xx is retryable and defers. Illegal XML
  1.0 characters are stripped and the page parsed once more, so one bad record does not break a route. DOIs are read
  from the several spellings repositories use (host-anchored `doi.org`). `dc:rights` maps **only Creative Commons and
  CC0 URLs** to licence codes; any other URL gives null, because `work.license` holds codes and only open-licence codes
  drive full-text retention. A non-CC open licence therefore counts as closed until it is mapped.
- **Restart cap.** A `badResumptionToken` restarts the job from its first page once (payload flag `restarted`). A second
  one in the same job sets the route `BROKEN` ("resumption tokens rejected twice") and ends the task `DONE`, so a flaky
  repository cannot loop; `VERIFY_ROUTE` revives it.
- **Magtech RSS** for Chinese journals on the Beijing Magtech platform (no OAI, DOIs often not in Crossref). Identity by
  scheme `MAGTECH` plus the DOI read from the article page. Abstracts are paired with their items, block tags become
  spaces (so names are not glued), entities are decoded with the decoder of `TitleText`. A missing optional feed never
  breaks the route.
- **Europe PMC** by ISSN and `FIRST_IDATE` (14-day window), PMID and PMCID as identifiers. `PROBE_EUROPEPMC` finds the
  journals Europe PMC holds and creates the route.
- **Plazi BLR** from the Zenodo community `biosyslit`, by `created` since the last run. It is a signal for taxonomic
  DOIs: a work is created only for an onboarded journal; Zenodo's own 10.5281 DOIs are ignored; a DOI of an unknown
  journal is kept in `layer_unmatched` for M6b's discovery.
- **Crossref** keeps `from-index-date` by ISSN and adds a history read (below).
- **Age rule.** `LayerWriter` skips any record published before 2000 and sets the origin: `ONBOARDING` and `BACKFILL`
  by mode; `LIVE` mode gives `LIVE` for a work published within the live window or without a date, else `BACKFILL`.
  Content of a `BACKFILL` work is queued at `BACKFILL` priority whatever the caller asks.

### Work identity (V16)

- `work_identifier` (`work_id`, `scheme`, `value`, `first_layer`, `first_seen`), unique on (`scheme`, `value`).
  Schemes: `DOI`, `OPENALEX`, `OAI`, `PMID`, `PMCID`, `BLR`, `MAGTECH` (the spec's `PLAZI`, `ZOOBANK`, `BHL_PART` come
  with M6c). Values are normalised per scheme. A DOI must match `^10\.\d{4,9}/\S+$`, which is the rule, so
  `10.1/X` is not a DOI. `work.doi` and `work.openalex_id` stay as columns and are mirrored into the table; V16 drops the
  V1 check that one of them is set, and the upsert requires at least one identifier row instead. V16 back-fills the
  rows from the existing columns and tolerates duplicates.
- **Matching order** in `WorkUpsert`, first hit wins: `DOI`, then `OPENALEX`, then the other identifiers of the incoming
  record, then a **title key**: same source, same year, same normalised title and, when both have authors, the same
  first author family name. The title match works in both directions (a DOI-less record joining a work with a DOI and
  the reverse), and not for titles under four words or generic ones (erratum, editorial, book review, obituary, ...).
- **Ambiguity rule.** If a title key finds candidates with different DOIs, nothing is joined and a new work is made.
  Without it `MERGE_WORKS` would have joined two distinct papers through a third record.
- **Advisory lock.** Upserts that need a title match take a transaction advisory lock on the title key, so two
  concurrent inserts of the same title cannot leave a silent duplicate. A partial index on `work (source_id, year)
  WHERE doi IS NULL` serves the candidate query, because that query runs once per unmatched record and the 5M-work
  backfill depends on it.
- **Late duplicates.** An identifier that already belongs to another work enqueues `MERGE_WORKS` (older work survives:
  identifiers, contents and hits move, the younger is deleted). **It refuses two works with different non-null DOIs**:
  it logs and leaves both. The survivor gets `FIND_NAMES` again when its abstract changed (the merge re-finds names).
- `work_sighting` (`work_id`, `layer`, `source_id`, `first_seen`), one row per work and layer. A sighting means "this
  layer saw the work", not "this layer owns its metadata": Crossref records its sighting also for works it stored through
  OpenAlex enrichment. The journal of a work is always `work.source_id`, never the sighting's.

### Field precedence

Each field group has an owner column: `work.meta_layer` (bibliographic) and `work.access_layer` (access), plus
`abstract_jats_source`. A layer overwrites a field only when its rank is lower (better) than the owner's; a worse layer
fills nulls only. Ranks (`Layer.java`, `WorkMerger`):

| Layer | Bibliographic rank | Access rank |
|---|---|---|
| CROSSREF | 0 | 1 |
| OPENALEX | 1 | 0 |
| EUROPEPMC | 2 | 3 |
| OAI, MAGTECH | 3 | 2 |
| BLR | 4 | 4 |

- Bibliographic: title, authors, volume, issue, pages, date, type, language. Access: OA status, licence, best OA URL.
  The publisher's own deposit (Crossref) wins the first, OpenAlex's open-access analysis the second.
- **Separate `access_layer`** (a ruling, not in the spec). With one owner, a Crossref-owned work would let Crossref's
  licence replace OpenAlex's. A layer claims `meta_layer` only when its record carries at least one bibliographic value
  and `access_layer` only when it carries an access value; otherwise Crossref's abstract-only merge would block OpenAlex.
- V16 back-fills the owners of existing rows (OpenAlex where `openalex_id` is set, else Crossref; access owner only
  where an access value exists), else the first OAI re-harvest would overwrite about 100,000 titles and licences.
- Abstract: replaced only by a better kind (Crossref JATS, other JATS, plain text, OpenAlex inverted index), never by
  an empty one. A title stays frozen once the finder has named the work, except through `CLEAN_TITLES`.
- `origin`, `first_seen_at`, `state`, `text_level` never change, as before.

### Routes and their lifecycle

`source_route` (`source_id`, `layer`, `config`, `status` PENDING / VERIFIED / ACTIVE / PAUSED / BROKEN, `origin`,
`verified_at`, `last_ok`, `last_error`, `onboarded_at`, `backfilled_at`), unique on (source, layer, config). OpenAlex and
Crossref need no row. Admin endpoints and the seed (`routes-seed.tsv`, `SEED_ROUTES`) are in CLAUDE.md.

- A new route is PENDING, `VERIFY_ROUTE` makes it VERIFIED (a feed with items, an endpoint that answers) or BROKEN with
  the reason. VERIFIED becomes ACTIVE through its `ONBOARDING` pass (120 days, `REPROCESS`), started by the nightly
  rollout (00:30 UTC, at most 20 routes a night, so the first deployment's alerts trickle in; the alert window of
  ADR-0006 decides, not the origin). A route of a journal onboarded before, or of one with an OpenAlex id that is not
  onboarded yet, goes straight to ACTIVE when verified.
- **Probe routes.** A Europe PMC route the probe finds for a source **without an OpenAlex id** starts VERIFIED, so the
  rollout gives it an onboarding pass and `ROUTE_BACKFILL` its history. One with an OpenAlex id starts ACTIVE: its
  history comes from the snapshot. Cost if wrong: one night's delay.
- **Route backfill** (`ROUTE_BACKFILL`, `RouteLifecycle`): per ACTIVE route not yet backfilled, in `BACKFILL` mode from
  2000 to 120 days ago. OAI reads the whole repository (no `from`; datestamps are not reliable enough), Europe PMC asks
  by `FIRST_PDATE`, Magtech has none (its feeds carry no history), and Crossref history runs for the journal's ISSNs.
  A backfill is never started twice: while a backfill job of the same route (or the source's Crossref history) still
  has a queued or running page, a new trigger skips it, `"force":true` included.
- **Crossref history** (V17). Cursors expire, so the history is read by offset, one publication year per listing, sorted
  by `created`. A year of 10,000 works or more is split by month. `source.crossref_history_at` records that the history
  ran to its end; later automatic route backfills skip it, an admin `ROUTE_BACKFILL` with `"force":true` reads it again.
- MNHN is not seeded (no sets, no journal field in its OAI records).

### Coverage monitor (V16, V18)

`COVERAGE_CHECK` runs daily at 06:30 UTC (`nametracker.layers.coverage-cron`), on yesterday (UTC), key
`coverage-check:<day>`. It is analysis, so it is **not** behind the harvest switch (like `REBUILD_STATS`). Per layer it
writes `layer_health` (state OK / DEGRADED / FAILING, failed runs, yield against the 8-week median, miss share) and per
journal `source_layer_coverage`, in one transaction. `GET /api/admin/status` shows `layers`.

- "Sources the layer serves": OpenAlex and Crossref the harvestable sources; OAI, Magtech and Europe PMC those with an
  ACTIVE route of that layer; BLR none (community-wide, miss share 0). A miss is a work another layer first saw 3 to 17
  days ago that this layer never sighted.
- **Zero yield counts only on run days**: a day on which the layer had a `DONE` run with no new sighting. No run means
  not expected (switched off, no routes); failed runs are caught by the failed-run rule, and a deferred run that finishes
  the next day is no failure.
- **Contribution** (`layer_contribution`, V18): per layer and journal country, works first found and found only by the
  layer, counted over `LIVE`-origin works created in the last 28 days (this removes the skew of the V16 seed and of
  backfills). The check computes and stores it per day; the status reads the latest stored day, so numbers can be a day
  old. This is how a layer that adds nothing, or a service turning paid, is judged.
- Mail to admins on FAILING, and the Layers admin page, belong to M6b; M6a shows state in the status only.

## Consequences / known limits

- **Magtech feeds have no history.** A site's RSS shows the current issue and online first only; whatever happens
  between two polls is lost, and a route first polled today starts today.
- **MNHN is not seeded** and any repository without sets or a usable journal field needs a hand-written route.
- **Crossref month cut at 10,000.** A journal-month with 10,000 works or more is cut at Crossref's offset ceiling;
  the cut is recorded in the run's error text, but `crossref_history_at` is still set (visible only there). A year split
  by month also re-reads its first 10,000 works as months: idempotent, but wasted free calls.
- **Zenodo paging stops at 10,000 records** per query. The daily BLR delta (about 500) stays far below; a catch-up after
  a long outage of more than a few weeks needs a smaller window by hand.
- **CLB I/O errors place names `NONE`.** When a single match call fails with an I/O error but the probe with
  `Animalia`/kingdom passes (ADR-0005), the name is placed `NONE`, not cached, with a WARN. A sick CLB that still answers
  the probe therefore loses placements until the next reprocess; the more-than-3-names rule defers the task in a real
  outage.
- **Non-CC licences** count as closed until mapped (above); a repository with a CC-compatible open licence in another
  form loses full text retention.
- **A silent scheduler is not flagged.** With no run there is no expected run; a scheduler that stopped shows only in
  the run list.
- **Title matching is a heuristic.** Same source, year, title and first author can still join two papers (parts of a
  series with the same title are the likely case); the guards on length and generic titles reduce, not remove, this.
- **Cost of the title match** is one query per unmatched record; it is watched on the 5M backfill. Legacy rows with an
  invalid DOI are matched through their columns only.
- **Deferred minors**: the quadratic skipped count in the BLR page mixes records and DOIs; a retried BLR page repeats
  its lookups.
- The two-night check with OpenAlex blocked (spec, "Done when") is prepared in `docs/m6a-dev-check.md` and needs two
  real nights.
