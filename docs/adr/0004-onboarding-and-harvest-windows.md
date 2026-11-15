# ADR-0004: Onboarding gate, harvest windows, budget and keyless limits

Date: 2026-11-15. Status: accepted.

## Context

M2 harvests the registry's journals from OpenAlex and Crossref and resolves content for the new works. This ADR
records what we decided while building it and the facts we verified against the live APIs (2026-11-15). Task numbers
refer to `.superpowers/sdd/2026-11-15-m2-harvest-and-content/`.

## Decisions

### Onboarding gate

- `LIVE` and `SWEEP` harvests only visit **onboarded** sources: active, tier CORE or BROAD, `onboarded_at` set
  (`SourceMapper.listHarvestable`).
- A new source, or one promoted to CORE/BROAD, first gets an `ONBOARD_SOURCE` task: an import of the last **120 days**
  at `BACKFILL` priority. Its works get origin `ONBOARDING` and **never notify**. `onboarded_at` is set when the last
  page is done; only then does the daily harvest pick the source up.
- The seeder, `ADD_SOURCE` and the admin `PATCH /api/admin/sources/{id}` all enqueue the same task with the dedup key
  `onboard:<sourceId>`, so it is queued at most once.
- Without the gate, a promoted journal would flood users with its back catalogue as if it were news, and the
  daily harvest would compete with the import for the budget.

### Continuation tasks are harvest checkpoints

- A harvest task fetches **one page**, enqueues its own continuation with the next position (batch, cursor or page,
  run id) and completes. A crash re-runs at most one page; delivery is at-least-once and the upserts are idempotent.
- The dedup keys encode the position, so a re-run page re-enqueues the same key and the queue absorbs it:
  - OpenAlex: `harvest:openalex:<window>:<from>:<batch>:<pos>`, with `<pos>` = `c<12 hex of SHA-1(cursor)>`,
    `p<page>` or `start` for the first page of the next batch.
  - Crossref: `harvest:crossref:<date>:<batch>:<pos>`.
  - Onboarding: `onboard:<sourceId>:<pos>`.
  - A deferral at the same position adds `:d<retryAt epoch seconds>`, because the running task already holds the key
    of its own position.
- The scheduler's first tasks are `harvest:openalex:<LIVE|SWEEP>:<date>`, `harvest:crossref:<harvested day>`,
  `openalex-recheck:<date>` and `seed-sources:<yyyy-MM>`.
- The scheduler skips an OpenAlex window while a chain of it is still QUEUED or RUNNING (`TaskMapper.countLive`, a
  payload match on `window`), so a run that outlasts the day is not started again on top of itself; the windows
  overlap, so the next fire covers what a skip left out.
- Crossref is never skipped, because its days do not overlap and a skipped day would be lost: each fire enqueues every
  day from the one after the latest non-FAILED `CROSSREF` run up to yesterday (at most 14 days back, only yesterday
  when there is no run yet), each under its own dated key, so a re-fire adds nothing.
- Windows: LIVE re-reads the last 120 days, SWEEP the last 400. They overlap on purpose: a late-indexed or updated
  work is caught by the next window, and the upsert never changes `origin` or `first_seen_at`.

### Budget: the source of truth is OpenAlex's headers

- `X-RateLimit-Remaining` and `X-RateLimit-Credits-Used` are **credits**, 1 credit = $0.0001. USD comes in
  `X-RateLimit-Cost-USD` and `X-RateLimit-Remaining-USD`. Our own price table (singleton $0, list or group-by
  $0.0001, content download $0.01) is used only when a response carries no credits header.
- `api_usage` stores the spend per service and UTC day, and the last remaining USD. A call is refused before it is
  sent when the day's cap (`nametracker.openalex.max-daily-usd`) or the reported remainder would be exceeded; content
  downloads additionally keep the `content.reserve-usd` free.
- A **spent day** defers the task to the next **00:05 UTC**. A **final 429** (after the HTTP layer's retries) defers
  it by **15 minutes**, without marking the day spent. Work is deferred, never failed into `DEAD`.

### Crossref

- Repeated `issn:` filters are **ORed**. Verified 2026-11-15: ZooKeys 8,542 + Zootaxa 50,404 = 58,946 works in one
  query with both ISSNs. We therefore ask for 40 ISSNs per request.

### OpenAlex without an API key

Measured on the keyless pool (2026-11-15):

- The budget is **$0.10 per day per IP**, ten times less than with a free key.
- A group-by call returns only its **first page (200 groups)**, so the registry seeding sees a truncated
  list and says so.
- Page paging is **capped at 10,000 results**; cursor paging needs a key. A capped batch is logged and noted on the
  harvest run (`truncated at 10000 results (no API key)`).
- `id` is **not a valid sort field**, so pages are sorted by `publication_date:desc` only. Works sharing a date can be
  skipped across a page boundary; the overlapping 120 and 400 day windows mostly heal that.
- **Recommendation: use a free API key** (`OPENALEX_API_KEY`). It lifts all four limits and is still free.

### Licences and retention

- Blobs (raw and `Document` JSON) are kept only for open licences: `cc-by`, `cc-by-sa`, `cc0`, `public-domain`
  (`nametracker.content.open-licences`). Otherwise the `content` row keeps hashes, URL and licence with
  `retained=false`, and the text is re-fetched when names are found (M3).
- The PMC open data bucket is laid out as `PMC<id>.<v>/` holding `.xml`, `.json`, `.txt` and `.pdf`. The licence is the
  `license_code` in the JSON; we read it before fetching the XML.

### Local GROBID on publisher PDFs is not in M2

Design route 5 (GROBID on `best_oa_location.pdf_url`) is left out:

- The roadmap names routes 1 to 4 for M2, and they cover the open-access journals the CORE tier is made of
  (Pensoft, PMC, Europe PMC, OpenAlex TEI).
- GROBID TEI carries no italics (ADR-0002), so it is the weakest input for the finder and needs its own tuning (mode B).
- Fetching publisher PDFs needs per-host politeness, robots.txt handling and blocklists, and the licence is often
  not machine-readable there. That is a project of its own.
- Works without a route end at `ABSTRACT_ONLY` or `NO_TEXT`. Route 5 can be added later behind the same route SPI.

## Consequences

- Nothing is scheduled unless `nametracker.harvest.enabled` (env `NAMETRACKER_HARVEST_ENABLED`) is true; it defaults to
  false. Handlers run for tasks enqueued by hand, so the pipeline can be driven step by step through
  `POST /api/admin/tasks`.
- Curating the registry (`GET`/`PATCH`/`POST /api/admin/sources`) is the only way a source enters the harvest after the
  seed; demoting a source stops its harvests at the next batch listing.
- Keyless operation works for a small registry and is truncated for a large one; run with a key.
