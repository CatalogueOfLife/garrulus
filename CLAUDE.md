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

**Status:** design approved 2026-11-14. M0 (foundations), M1 (finder v1), M2 (harvest and content) and M3 (match and
index: ADR-0005) and M4 (accounts and alerts: ADR-0006; local only, deployment comes last) are built; M2's week-long
dev run continues (`docs/m2-dev-run.md`, M3 check in `docs/m3-dev-check.md`). Next: M5. Finder and salience tuning
is a final stage after the milestones.

## Commands

Prerequisites: `sdk env` (JDK 25 from `.sdkmanrc`), Docker (OrbStack works), Node 24 for the frontend.

```
docker compose up -d db gnfinder                    # db 5434, gnfinder 8778 (add --profile grobid / annotate for 8070 / 8090)
mvn -B verify                                       # all unit tests and ITs (ITs use Testcontainers)
mvn -B -pl <module> -am verify -Dit.test=<Class>    # one IT
mvn -B -pl eval -am verify -Pgrobid -Dit.test=GrobidIT   # GrobidIT only runs with -Pgrobid (1.2 GB image)
FINDER_DICTIONARY=$PWD/eval/work/dictionary-<key>.bin mvn -pl finder-service spring-boot:run   # finder service on 8095 (absolute path: it runs in finder-service/)
mvn -B -DskipTests install                          # once on a fresh clone and after changing store: -pl resolves siblings from ~/.m2
                                                    # (needs clb-bom/nomenclature 1.7.1-SNAPSHOT: GBIF snapshot repo, else see below)
mvn -pl indexer spring-boot:run                     # indexer on 8081 (127.0.0.1)
mvn -pl web spring-boot:run                         # web on 8080
cd frontend && npm install                          # once
cd frontend && npm run dev | npm test | npm run build   # Vite on 5173, proxies /api to 8080
```

**CLB snapshot modules.** `clb.version` is `1.7.1-SNAPSHOT`; `vocab` (`TaxGroup`) and `nomenclature`
(`TaxGroupAnalyzer`) come from the GBIF snapshot repository. When that repository is unreachable (it refused connections
on 2026-11-15), install them from a CLB backend checkout: `mvn -q -DskipTests -pl nomenclature -am install` in
`~/code/col/backend` (reads the checkout only). A fresh clone needs one of the two before the first build.

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
    outage (connection refused, unknown host, connect timeout, HTTP 502/503/504, also from a finder service) defers the
    task 30 minutes without using an attempt. Any other gnfinder or finder error (4xx, 500, a read timeout) fails the
    task like any error: it uses attempts and ends `DEAD`.
  - `NAMETRACKER_CLB_BASE_URL` (default `https://api.checklistbank.org`) serves the XR and everything but matching when
    `NAMETRACKER_CLB_MATCH_BASE_URL` is set: a local CLB release bundle for all match calls and the N1 probe, e.g.
    `NAMETRACKER_CLB_MATCH_BASE_URL=http://localhost:18100` (never 8080/8081). It is not throttled, its `GET /dataset`
    gives the release key to pin, and a bundle of another release than the pinned one makes `MATCH_NAMES` fail DEAD
    (ADR-0005, "Local bundle matching").
  - `NAMETRACKER_HARVEST_OUTAGE_DELAY` (30m): a harvest or onboarding page that meets an outage is enqueued again after
    this delay instead of failing.
- `indexer` matching and index (ADR-0005):
  - `NAMETRACKER_CLB_BASE_URL` (`https://api.checklistbank.org`): the XR (`GET /dataset/3LXR`) and name matching, at
    most 10 requests a second. A CLB outage defers `MATCH_NAMES`/`XR_CHECK` 30 minutes without using an attempt. When
    a single match call fails (after the HTTP retries, or with a 4xx), the same match endpoint and release are probed
    with `Animalia`/kingdom: probe ok → the name is at fault, placed `NONE` (not cached, WARN) and the work goes on;
    probe fails retryably → outage; probe 4xx → ordinary failure (attempts, `DEAD`). More than 3 names at fault in one
    task → the whole task is deferred as an outage and nothing is written.
  - A task deferred by an outage that was first enqueued more than 24 hours ago is logged as a WARN on every deferral.
  - `NAMETRACKER_SPOOL_MAX_AGE` (14d) bounds the spool; it is purged daily at 05:30 UTC.
  - `NAMETRACKER_STATS_CRON` (`0 30 4 * * *`): nightly `REBUILD_STATS` (ubiquity, taxon stats) at `LIVE` priority, which
    then enqueues `CATEGORISE_SOURCES`. `XR_CHECK` runs daily at `0 0 6 * * *`. Neither is behind the harvest switch.
- `indexer` salience (properties only, no env variables; set them in `application.yml`, `.env` or as `-D`/args):
  `nametracker.salience.{title=0.40, abstract=0.20, act=0.35, heading=0.15, share=0.25, repeat=0.15, ubiquity=0.50,
  ubiquity-full-share=0.02, threshold=0.35}`. A name with salience >= threshold is a main subject of the work.
- `web` accounts and alerts (ADR-0006):
  - `NAMETRACKER_PUBLIC_URL` (`http://localhost:5173`, links in mails and feeds), `NAMETRACKER_MAIL_FROM`
    (`Garrulus <noreply@localhost>`), `spring.mail.*` (without `spring.mail.host` mails are logged as suppressed, with
    the user id), `NAMETRACKER_SIGNING_KEY` (signs unsubscribe links; blank = random per start, WARN),
    `NAMETRACKER_DIGEST_CRON` (`0 5 * * * *`, UTC, `-` = off), `NAMETRACKER_COOKIE_SECURE` (true behind HTTPS).
  - `ORCID_CLIENT_ID`, `ORCID_CLIENT_SECRET`, `ORCID_BASE_URL` (`https://orcid.org`; `https://sandbox.orcid.org` for a
    sandbox client): the ORCID button shows only with a client id.
  - `NAMETRACKER_HIT_WINDOW_DAYS` (90) sets the public feeds' window in `web` and the alert window in the `indexer`
    (`nametracker.hits.{window-days,future-days=7}`).
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
- Index task types (M3): `FIND_NAMES` (`workId`) → `MATCH_NAMES` (`workId`) → the work is `INDEXED`; `XR_CHECK` (`{}`),
  `REBUILD_STATS`, `CATEGORISE_SOURCES`, `EVALUATE_HITS` (`workId`; enqueued after a work is `INDEXED` and by `RESCORE_SALIENCE` for works with a new main subject: writes `hit` rows for matching subscriptions once per subscription, work and kind; `NOTIFIED` is unused), `RESCORE_SALIENCE` (`{"afterId":0,"batch":500,"all":false}`: recomputes salience, main subject and the work's codes/taxgroups/has_acts from stored mentions and the current ubiquity, placements untouched, continues per batch at REPROCESS; targeted at works with a name whose ubiquity penalty moved by more than 0.05 since `name_ubiquity.scored_share`, `REBUILD_STATS` enqueues it when there is one; admin `{"all":true}` rescores everything, e.g. after changing salience properties) and the admin-only `FIND_PENDING` (`{"limit":n}`, default 100), which enqueues
  `FIND_NAMES` for up to n `CONTENT_RESOLVED` works the finder never saw, highest work id first. The queue runs by
  priority (`LIVE`, `REPROCESS`, `BACKFILL`), then `run_after`, then id. A follow-up (`RESOLVE_CONTENT` → `FIND_NAMES` →
  `MATCH_NAMES`, `REBUILD_STATS` → `CATEGORISE_SOURCES`) inherits the priority and the `run_after` of its task, so it
  takes its parent's place in the queue: depth first, a work goes all the way to `INDEXED` before the peers queued
  after it start.

```
# pin the XR (the first key seen stays current until M5), then name and index every resolved work
curl ... -d '{"type":"XR_CHECK","payload":{},"priority":"LIVE","dedupKey":"xr-check:manual"}'
curl ... -d '{"type":"FIND_PENDING","payload":{"limit":100000},"priority":"REPROCESS","dedupKey":"find-pending:manual"}'
```
- `GET /api/admin/sources?tier=&active=&q=&limit=&offset=` lists sources (limit at most 500, default 100).
- `PATCH /api/admin/sources/{id}` with `{"tier":"CORE","active":true}` (either field) sets tier and active as ADMIN. A
  promotion to CORE/BROAD of an active, not yet onboarded source enqueues its onboarding. 404 for an unknown id, 400
  for a bad tier.
- `POST /api/admin/sources` with `{"openalexId":"S123","tier":"CORE"}` enqueues `ADD_SOURCE` (202).
- `GET /api/admin/status`: task counts by type and state, work counts by state and text level, today's (UTC) `api_usage`
  rows, the last 20 harvest runs and source counts by tier. Only COUNT/GROUP BY queries, but they scan the whole task
  and work tables, which grow with the backlog: poll it every few minutes, not in a tight loop.

## Public API (`web`, no token)

`/api/works` (filters `taxon`, `name`, `code`, `taxgroup`, `subfield`, `journalType`, `tier`, `oa`, `country`, `source`,
`yearFrom`, `yearTo`, `hasActs`, `actType`, `mainSubjectOnly`, `q`; `limit` ≤ 100, `offset` ≤ 10000; repeatable
`facet`), `/api/works/{id}`, `/api/names?q=`, `/api/names/{id}`, `/api/taxa/{colId}/works` (`mainSubjectOnly` defaults
to true), `/api/taxa/{colId}/stats`, `/api/sources`, `/api/sources/{id}`, `/api/xr`, `/api/vocab/taxgroup`. Only
`INDEXED` works are listed; taxon filters ignore names that occur only in references. The full text of a work is served
only when its content is retained (open licence). Against a manual run on 18080, start Vite with a scratch config whose
proxy targets `http://localhost:18080` on another port (never edit `vite.config.ts` for it).

## Accounts and alerts (M4, `web`)

- `/api/me/**` (session; `GET /api/me`, `PATCH`, `POST /api/me/email`, `DELETE`, `/export`, `/subscriptions`,
  `/subscriptions/{id}/feed-token`, `/hits`), `/api/auth/**` (`POST /link`, `GET /config`, `POST /verify-email`,
  `POST /api/auth/logout`), `/api/unsubscribe` and ORCID under `/oauth2`, `/login`. XSRF cookie only on those paths.
  Public reads carry no cache headers; `/api/me/**`, `/api/auth/**`, `/login/**` and `/api/admin/**` send
  `Cache-Control: no-store` (ADR-0006).
- Feeds: `/feeds/{taxon,source,name,taxgroup}/<id>.atom` (public) and `/feeds/s/<token>.atom` (private, per follow).
- Admin: `POST /api/admin/digests/run` with `{"userId":n}` and the admin token sends that user's digest now.
- Dev sign-in: `docker compose --profile mail up -d mailpit` (SMTP 1025, UI http://localhost:8025) and
  `spring.mail.host=localhost`, `spring.mail.port=1025` in `.env`. Vite must listen on 5173 (it proxies `/api`,
  `/oauth2`, `/login`, `/feeds` and keeps the `Host` header; a scratch config may point at 18080 but keeps 5173), with
  `NAMETRACKER_PUBLIC_URL=http://localhost:5173`. The ORCID client needs the redirect URI
  `http://localhost:5173/login/oauth2/code/orcid`.

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
  - `ls-import` infers what the annotation leaves out, unless `--strict` is given (explicit values always win):
    an Act without act_type gets its type from `ActPatterns` (plus "nom. rest." as STAT_REV, "<higher rank abbrev> n." as
    HIGHER_NOV); an Act without an `act_of` relation links to the nearest Name ending before it in the same block, with no
    other Name or Act in between and at most 80 code points of gap; a Context without ranks text and rank word gets
    "rank: Name" pairs, else names split on ":" or "," ranked by the COL dictionary (`--dictionary` or `FINDER_DICTIONARY`),
    then suffix rules, else UNRANKED. Every inferred value is listed in `eval/work/ls-import-inferred.tsv` (docId, region id,
    kind, region text, value, gap); check it after each import. Failures of all tasks are reported together.
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
- **Salience tuning:** `tune-salience --corpus eval/corpus/curated --split dev [--report eval/work/salience-tuning.md]`
  runs the pipeline finder (needs gnfinder and the dictionary), builds the salience inputs per document with the
  indexer's code (ubiquity 0) and searches title, abstract, act, heading, share, repeat and threshold by coordinate
  descent (two starts: the defaults and flat 0.2 weights; grids 0 to 0.5 and 0.20 to 0.60) for the best micro F1 of main-subject
  names against the curated labels (documents with at least one label only). It prints `nametracker.salience.*`
  overrides; copy them into `indexer/src/main/resources/application.yml` by hand. A document with only TITLE/ABSTRACT blocks counts as abstract-only.
- **Tuning stage hand-over** (fixing the gold, import, baselines, tune, tune-salience, rescore): `docs/tuning-stage.md`.
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
- `docs/adr/`: later decisions, one file each (0004: onboarding gate, harvest windows, budget, keyless limits; 0005:
  index pipeline, spool, XR pin, placement, salience, categorisation, OpenAPI).

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
