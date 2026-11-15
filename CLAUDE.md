# CLAUDE.md

**Garrulus** (internal id `nametracker`) follows the journals of biodiversity science, finds scientific names (plus nomenclatural acts and the
article's verbatim classification) in abstracts and open-access full text, organises them on the latest COL XR, and
notifies users who follow taxa, journals or names. It also builds a public name–literature index.

**Naming:**
- **Internal id `nametracker`:** Java packages `org.catalogueoflife.nametracker.*`, artifactIds `nametracker-*`, the
  database, systemd units `col-nametracker-*`, the repo.
- **Public name "Garrulus"** (the European jay): everything users see. That covers the UI, logo, page titles, emails,
  feeds, public URLs and the outgoing HTTP User-Agent `Garrulus/<version> (+https://www.catalogueoflife.org)`.
- **Brand:** logo, jay colour palette and the Mantine theme are in `docs/brand/`. Take colours from there; never invent
  new ones.

**Status:** design approved 2026-11-14; implementation starts with milestones M0 (foundations) and M1 (finder v1).

## Commands

Prerequisites: `sdk env` (JDK 25 from `.sdkmanrc`), Docker (OrbStack works), Node 24 for the frontend.

```
docker compose up -d db gnfinder                    # db 5434, gnfinder 8778 (add --profile grobid / annotate for 8070 / 8090)
mvn -B verify                                       # all unit tests and ITs (ITs use Testcontainers)
mvn -B -pl <module> -am verify -Dit.test=<Class>    # one IT
mvn -B -pl eval -am verify -Pgrobid -Dit.test=GrobidIT   # GrobidIT only runs with -Pgrobid (1.2 GB image)
FINDER_DICTIONARY=$PWD/eval/work/dictionary-<key>.bin mvn -pl finder-service spring-boot:run   # finder service on 8095 (absolute path: it runs in finder-service/)
mvn -B -DskipTests install                          # once on a fresh clone and after changing store: -pl resolves siblings from ~/.m2
mvn -pl indexer spring-boot:run                     # indexer on 8081 (127.0.0.1)
mvn -pl web spring-boot:run                         # web on 8080
cd frontend && npm install                          # once
cd frontend && npm run dev | npm test | npm run build   # Vite on 5173, proxies /api to 8080
```

**Configuration** (environment variables; defaults in each `application.yml`):
- Both apps: `NAMETRACKER_DB_URL` (default `jdbc:postgresql://localhost:5434/nametracker`), `NAMETRACKER_DB_USER` and
  `NAMETRACKER_DB_PASSWORD` (both default `nametracker`).
- `web`: `ADMIN_TOKEN` enables the admin endpoints (`/api/admin/tasks`, `/sources`, `/status`; send it in the
  `X-Admin-Token` header). If blank, none of them is mapped (404). `NAMETRACKER_WEB_ADDRESS` is the bind address
  (default `127.0.0.1`, loopback only like the indexer; `0.0.0.0` to serve other hosts).
- `indexer`: `NAMETRACKER_INDEXER_PORT` (8081), `NAMETRACKER_INDEXER_WORKERS`, `NAMETRACKER_INDEXER_POLL_INTERVAL`,
  `NAMETRACKER_INDEXER_MAX_ATTEMPTS`, `NAMETRACKER_INDEXER_BACKOFF`, `NAMETRACKER_INDEXER_STALE_AFTER` (1h),
  `NAMETRACKER_INDEXER_REQUEUE_INTERVAL` (10m), `NAMETRACKER_INDEXER_HEARTBEAT_INTERVAL` (2m). Task delivery is
  at-least-once, so handlers must be idempotent.
- `indexer` harvest (see ADR-0004):
  - `NAMETRACKER_HARVEST_ENABLED` is the master switch of all scheduled harvests and **defaults to false**. Tasks
    enqueued by hand run regardless.
  - Crons (UTC, six fields): `NAMETRACKER_HARVEST_OPENALEX_CRON` (`0 0 2 * * *`), `NAMETRACKER_HARVEST_SWEEP_CRON`
    (`0 0 4 * * SUN`), `NAMETRACKER_HARVEST_CROSSREF_CRON` (`0 0 3 * * *`). The OpenAlex re-check (`0 30 3 * * *`)
    and the monthly source seeding (`0 0 1 1 * *`) are fixed.
  - `OPENALEX_API_KEY` (recommended, free), `CROSSREF_MAILTO` (optional), `NAMETRACKER_OPENALEX_MAX_DAILY_USD` (1.00),
    `NAMETRACKER_OPENALEX_CONTENT_ENABLED` (true), `NAMETRACKER_OPENALEX_CONTENT_RESERVE_USD` (0.30),
    `NAMETRACKER_BLOBS_DIR` (`./data/blobs`), `NAMETRACKER_CONTENT_OPEN_LICENCES`, `NAMETRACKER_SEED_*`,
    `NAMETRACKER_OPENALEX_BASE_URL`, `NAMETRACKER_OPENALEX_CONTENT_BASE_URL`, `NAMETRACKER_CROSSREF_BASE_URL`.
  - Name finding (`FIND_NAMES`): `NAMETRACKER_FINDER_URL` (a finder service) or else `FINDER_DICTIONARY` (+ `GNFINDER_URL`,
    `FINDER_CONFIG`) builds the finder in process; with neither set the handler is off (one WARN) and the tasks wait.
    `NAMETRACKER_SPOOL_DIR` (`./data/spool`) holds closed full text until the finder has run (purged after 14 days).
    Admin task `FIND_PENDING` (`{"limit":n}`) enqueues `FIND_NAMES` for works never seen by the finder. A gnfinder
    outage defers the task 30 minutes without using an attempt.
  - `NAMETRACKER_HARVEST_OUTAGE_DELAY` (30m): a harvest or onboarding page that meets an outage is enqueued again after
    this delay instead of failing.
- `indexer` salience (properties only, no env variables; set them in `application.yml`, `.env` or as `-D`/args):
  `nametracker.salience.{title=0.40, abstract=0.20, act=0.35, heading=0.15, share=0.25, repeat=0.15, ubiquity=0.50,
  ubiquity-full-share=0.02, threshold=0.35}`. A name with salience >= threshold is a main subject of the work.
- `web` public API: `nametracker.web.cors-origins` (env `NAMETRACKER_WEB_CORS_ORIGINS`, comma-separated, default
  `https://www.catalogueoflife.org,https://dev.catalogueoflife.org,https://preview.catalogueoflife.org`) are the origins
  allowed to call `/api/taxa/**` from a browser (GET only; no other path sends CORS headers). The hand-written
  OpenAPI file `web/src/main/resources/static/api/openapi.yaml` is served at `/api/openapi.yaml`; add every new public
  GET under `/api` to it (`OpenApiCoverageTest` fails otherwise).
- `finder-service`: `FINDER_DICTIONARY` (required), `GNFINDER_URL`, `FINDER_CONFIG` (a JSON file of pipeline overrides),
  `FINDER_SERVICE_PORT` (8095) and `FINDER_SERVICE_ADDRESS` (default `127.0.0.1`, loopback only; `0.0.0.0` to serve
  other hosts). A text or block over 100,000 code points (`maxChunkCodePoints`) is a 413; invalid documents are a 400.
- Both apps also read a local `.env` (git-ignored, see `.env.example`) from the working or the parent directory. In
  the indexer, `nametracker.dotenv` replaces that import list; the ITs use it to read no `.env` at all.
- Port clash (for example a CLB `WsMatchingServer` on 8080 or 8081): `SERVER_PORT=9080 mvn -pl web spring-boot:run`.

## Harvesting by hand and admin endpoints

All of these need `ADMIN_TOKEN` on `web` and the header `X-Admin-Token: $ADMIN_TOKEN`. Use the local ports of a
manual run (`SERVER_PORT=18080`, `NAMETRACKER_INDEXER_PORT=18081`), never 8080/8081.

```
# seed the journal registry, then harvest by hand (the indexer must be running; the master switch can stay off)
curl -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"SEED_SOURCES","payload":{},"priority":"LIVE","dedupKey":"seed-sources:manual"}'
curl ... -d '{"type":"HARVEST_OPENALEX","payload":{"window":"LIVE"},"priority":"LIVE","dedupKey":"harvest:openalex:LIVE:manual"}'
curl ... -d '{"type":"HARVEST_CROSSREF","payload":{"date":"2026-11-14"},"priority":"LIVE","dedupKey":"harvest:crossref:manual"}'
```

- Task types: `SEED_SOURCES`, `ADD_SOURCE`, `ONBOARD_SOURCE`, `HARVEST_OPENALEX` (`window` LIVE or SWEEP),
  `HARVEST_CROSSREF` (`date`), `OPENALEX_RECHECK`, `RESOLVE_CONTENT`. Only onboarded CORE/BROAD sources are harvested.
- `GET /api/admin/sources?tier=&active=&q=&limit=&offset=` lists sources (limit at most 500, default 100).
- `PATCH /api/admin/sources/{id}` with `{"tier":"CORE","active":true}` (either field) sets tier and active as ADMIN. A
  promotion to CORE/BROAD of an active, not yet onboarded source enqueues its onboarding. 404 for an unknown id, 400
  for a bad tier.
- `POST /api/admin/sources` with `{"openalexId":"S123","tier":"CORE"}` enqueues `ADD_SOURCE` (202).
- `GET /api/admin/status`: task counts by type and state, work counts by state and text level, today's (UTC) `api_usage`
  rows, the last 20 harvest runs and source counts by tier. Only COUNT/GROUP BY queries, but they scan the whole task
  and work tables, which grow with the backlog: poll it every few minutes, not in a tight loop.

## Evaluation (`eval`)

Run the CLI after `mvn -B -DskipTests install` with `mvn -q -pl eval exec:java -Dexec.args="<command> ..."`; `--help`
on any command lists its options.

- **Prerequisites:**
  - `docker compose up -d gnfinder` for the gnfinder and pipeline finders (`GNFINDER_URL`, default
    `http://localhost:8778`).
  - The COL dictionary for pipeline, mode B and tune: `--dictionary <file>` or `FINDER_DICTIONARY`.
- **Dictionary** (not committed): `build-dictionary --xr https://download.checklistbank.org/col/xr_latest_coldp.zip
  --out eval/work` writes `eval/work/dictionary-<releaseKey>.bin` (format v2, about 46 MB).
  Rebuild it for a new XR and after a format change; an old format fails with a message saying so.
- **Corpora** (committed): `eval/corpus/pensoft` is the silver set (300 CC-BY articles, 100 dev / 200 test, rebuilt by
  `rebuild-pensoft`). `eval/corpus/curated` holds only the manifest until it is annotated; then `ls-import --out
  eval/corpus/curated/gold.jsonl.gz`.
  - `ls-export` writes the Label Studio tasks (`eval/work/curated-tasks.json`, not committed). Its pre-tags are the union
    of italics, gnfinder and pipeline, rejected pipeline candidates included, each region scored (judge score, else
    1.0). It needs gnfinder and the dictionary.
  - `ls-export --min-score 0.1` (default) leaves out name pre-tags scoring below 0.1 (rejected common words such as
    "The"). Pipeline acts are pre-tagged too: an `Act` region with its `act_type` and an `act_of` link.
  - `curated-add-acts` added the ACTS stratum on 2026-11-15 (12 documents, 2 dev): act-rich excerpts of three CC-BY PDFs
    (GROBID, `--pdf path|docId|doi|licence|language|source`) and one abstract with act markers per journal (`--journal
    S…[:lang]`). It appends to `manifest.tsv` and `eval/work/curated-picks.jsonl.gz` and writes the new picks alone to
    `eval/work/curated-picks-acts.jsonl.gz`, for `ls-export --picks … --out eval/work/curated-tasks-acts.json`.
  - The curated gold also has authorships: an `Authorship` region belongs to the `Name` right before it. Reports on the
    curated corpus add an `authorship` row (matched spans only, whitespace ignored); other corpora have none.
- **Runs:** `run --finder italics|gnfinder|pipeline|remote:<url> --corpus eval/corpus/pensoft --split dev|test --mode A|B`
  (`--match STRICT|OVERLAP`, `--doc-level`).
  - Reports land in `eval/reports/{date}-{finder}-{corpus}-{split}-{mode}-{match}[-doc].md` plus `.json`, and are
    committed.
- **Mode B** (PDF path):
  - Start GROBID with `docker compose --profile grobid up -d grobid` (`GROBID_URL`, default `http://localhost:8070`).
  - `fetch-pdf --corpus eval/corpus/pensoft` fills `eval/work/pdf/`: per document the PDF, GROBID's TEI and the source
    URL. PDFs come from the PMC open data copy on AWS when there is one (identical in spot checks (MD5)); pensoft.net is the fallback
    and is throttled to a few KB/s.
  - `run --mode B` reads that cache, fetches what is missing, and always scores names per document. With
    `--cache-only` it skips uncached documents instead, so all finders score the same set. Skipped documents are listed
    in the report.
- **Tuning:** `tune --corpus eval/corpus/pensoft --split dev --modes A,B` is a grid search over the judge threshold and
  weights.
  - It prints the best overrides; copy them into
    `finder-core/src/main/resources/org/catalogueoflife/nametracker/finder/pipeline/pipeline-defaults.json`. That changes
    the finder's `configHash`.
  - Tune on dev only; reports quote test. Mode B is in the objective because GROBID TEI has no italics (ADR-0002).
- **ITs:**
  - `GnfinderClientIT` runs in every `verify`.
  - `GrobidIT` is excluded unless `-Pgrobid` is given, because the GROBID image is 1.2 GB.
- **Never commit** `eval/work/` or `eval/reports/OVERLAP/`.

## Modules

- `finder-api`: `NameFinder` SPI, the document model and its JSON contract `finder-api.yaml`.
- `finder-core`: `PipelineNameFinder` (built by `PipelineFactory`), the generators (gnfinder client, COL dictionary,
  suffix, markup), the stages, the judge SPI, the JATS/TEI converters and `RemoteNameFinder`. `OtuIdentifierGenerator`
  emits BOLD BINs and UNITE SH codes as certain `NameKind.OTU` mentions (`hints.scheme`) that bypass merge, parser and judge;
  the eval scorer counts them apart from names.
- `finder-service`: thin HTTP wrapper exposing `/v1/find` (plus `/find/text`, `/find/jats`), reusable by CLB and Blixa.
- `store`: the shared schema, Flyway migrations and MyBatis mappers; used by both apps.
- `indexer`: the worker app for harvesting, analysis and matching; applies the Flyway migrations; port 8081, bound to 127.0.0.1.
- `web`: the user-facing read app and the admin task endpoint; validates the schema but does not migrate; port 8080, bound to 127.0.0.1.
- `eval`: evaluation harness for the finder: corpora, scorer, reports, mode B and tuning (see above).
- `frontend`: Vite, React and Mantine SPA (not a Maven module).

## Read first

- `docs/design.md`: the approved design. Goals, constraints, components, data model, workflows, roadmap.
- `docs/adr/`: later decisions, one file each (0004: onboarding gate, harvest windows, budget, keyless limits).

## Ground rules (from the design)

- **Stack:** Blixa's stack (`~/code/col/blixa`): Spring Boot 4.1, **JDK 25**, MyBatis, Flyway, Postgres 17,
  React + Mantine. CLB modules and name-parser versions come from `org.catalogueoflife:clb-bom` via one `clb.version`
  property.
- **Two Spring Boot apps sharing one database.** They never call each other.
  - `web`: user-facing, mostly read, always on.
  - `indexer`: harvesting, analysis, matching; can be stopped at any time.
  - The schema lives once, in the shared `store` module.
- **Free by default.** Nothing may depend on a paid or metered service.
  - Every such integration (OpenAlex beyond the free allowance, OpenAlex content downloads, LLM judge) has a config
    switch and a cost cap.
  - The backfill uses free sources only.
- **The name finder sits behind the `NameFinder` SPI and its JSON contract** (`finder-api`), so alternative
  implementations can be swapped in. The default is gnfinder plus our own stages.
- **Never store a name that doesn't point at an exact span of source text.** No LLM-invented names.
- Keep full text only for open-licensed works; for everything else, only derived data and short snippets.
