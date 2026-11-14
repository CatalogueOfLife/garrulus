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
- `web`: `ADMIN_TOKEN` enables `POST /api/admin/tasks` (send it in the `X-Admin-Token` header). If blank, the endpoint
  returns 404.
- `indexer`: `NAMETRACKER_INDEXER_PORT` (8081), `NAMETRACKER_INDEXER_WORKERS`, `NAMETRACKER_INDEXER_POLL_INTERVAL`,
  `NAMETRACKER_INDEXER_MAX_ATTEMPTS`, `NAMETRACKER_INDEXER_BACKOFF`, `NAMETRACKER_INDEXER_STALE_AFTER` (1h),
  `NAMETRACKER_INDEXER_REQUEUE_INTERVAL` (10m), `NAMETRACKER_INDEXER_HEARTBEAT_INTERVAL` (2m). Task delivery is
  at-least-once, so handlers must be idempotent.
- Both apps also read a local `.env` (git-ignored, see `.env.example`) from the working or the parent directory.
- Port clash (for example a CLB `WsMatchingServer` on 8080 or 8081): `SERVER_PORT=9080 mvn -pl web spring-boot:run`.

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
- **Runs:** `run --finder italics|gnfinder|pipeline|remote:<url> --corpus eval/corpus/pensoft --split dev|test --mode A|B`
  (`--match STRICT|OVERLAP`, `--doc-level`).
  - Reports land in `eval/reports/{date}-{finder}-{corpus}-{split}-{mode}-{match}[-doc].md` plus `.json`, and are
    committed.
- **Mode B** (PDF path):
  - Start GROBID with `docker compose --profile grobid up -d grobid` (`GROBID_URL`, default `http://localhost:8070`).
  - `fetch-pdf --corpus eval/corpus/pensoft` fills `eval/work/pdf/`: per document the PDF, GROBID's TEI and the source
    URL. PDFs come from the PMC open data copy on AWS when there is one (byte-identical); pensoft.net is the fallback
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
  suffix, markup), the stages, the judge SPI, the JATS/TEI converters and `RemoteNameFinder`.
- `finder-service`: thin HTTP wrapper exposing `/v1/find` (plus `/find/text`, `/find/jats`), reusable by CLB and Blixa.
- `store`: the shared schema, Flyway migrations and MyBatis mappers; used by both apps.
- `indexer`: the worker app for harvesting, analysis and matching; applies the Flyway migrations; port 8081, bound to 127.0.0.1.
- `web`: the user-facing read app and the admin task endpoint; validates the schema but does not migrate; port 8080.
- `eval`: evaluation harness for the finder: corpora, scorer, reports, mode B and tuning (see above).
- `frontend`: Vite, React and Mantine SPA (not a Maven module).

## Read first

- `docs/design.md`: the approved design. Goals, constraints, components, data model, workflows, roadmap.
- `docs/adr/`: later decisions, one file each.

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
