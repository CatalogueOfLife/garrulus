# CLAUDE.md

**Garrulus** (internal id `nametracker`) follows the journals of biodiversity science, finds scientific names (plus nomenclatural acts and the
article's verbatim classification) in abstracts and open-access full text, organises them on the latest COL XR, and
notifies users who follow taxa, journals or names. It also builds a public name–literature index.

**Naming:**
- **Internal id `nametracker`:** Java packages `org.catalogueoflife.nametracker.*`, artifactIds `nametracker-*`, the
  database, systemd units `col-nametracker-*`, the repo.
- **Public name "Garrulus"** (the European jay): everything users see. That covers the UI, logo, page titles, emails,
  feeds, public URLs and the outgoing HTTP User-Agent `Garrulus/<version> (+https://www.catalogueoflife.org)`.
- **Brand:** logo, colour tokens (black, white, one red), typeface and the Mantine theme are in `docs/brand/` and
  `frontend/src/styles/tokens.css`. Take colours from there; never invent new ones.

**Status:** design approved 2026-11-14. M0 (foundations), M1 (finder v1), M2 (harvest and content) and M3 (match and
index: ADR-0005), M4 (accounts and alerts: ADR-0006; local only, deployment comes last) and M5 (lifecycle and
backfill: ADR-0007; M5a XR switch, reprocessing and pending re-index, M5b snapshot backfill) are built locally; the
full backfill runs later on the GBIF VM (apply V21 to V23 together before it: V21's `work.fts` `SET EXPRESSION` rewrites all of `work` under an ACCESS EXCLUSIVE lock, instant on the empty database, blocking the web for the whole rewrite on a populated one). M2's week-long dev run continues (`docs/m2-dev-run.md`, M3 check in
`docs/m3-dev-check.md`). The curated gold is imported and its baselines are committed (`docs/tuning-stage.md`).
Roadmap (2026-11-18): M6 reach (layered harvesting, design `docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md`,
research `docs/research/`), M7 judging (the next tuning step), M8 the portal widget. M6a (identity and layered harvest:
ADR-0008) is built locally and its dev check runs (`docs/m6a-dev-check.md`). M6b (journal registry, discovery,
registration, admins and admin pages, candidate sampling, ceased journals, disciplines: ADR-0009) is built locally; its
dev check is `docs/m6b-dev-check.md` (local run pending, GBIF checklist). M6c (crosslinks, languages and English titles,
abstract and full-text fallbacks, the mentioned-in filter, the KU route, off: ADR-0010) is built locally; its dev check
is `docs/m6c-dev-check.md` (local run pending, GBIF checklist, one open question for Markus). Next: M7.

## Commands

Prerequisites: `sdk env` (JDK 25 from `.sdkmanrc`), Docker (OrbStack works), Node 24 for the frontend.

```
docker compose up -d db                             # db 5434 (add --profile grobid / annotate / mail for 8070 / 8090 / 8025)
gnfinder -p 8778                                    # native gnfinder, see "gnfinder" below (not in docker)
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

## LibreTranslate

Machine-translated English titles (`TRANSLATE_TITLE`, ADR-0010) come from a local LibreTranslate 1.9.6 with Argos
models, run natively like gnfinder (no docker); `web` never calls it, and abstracts are never sent to it. Install it into
its own Python virtual environment:

```
python3 -m venv ~/.local/libretranslate && ~/.local/libretranslate/bin/pip install libretranslate==1.9.6
~/.local/libretranslate/bin/libretranslate --host 127.0.0.1 --port 5055 --load-only zh,ja,ru,en   # models load at first start
curl -s localhost:5055/languages      # check
```

- `--load-only` limits the models (memory); the VM loads the languages of the non-English titles it holds (the list in
  `.env.example`). Keep `en` in it: translations go into English only.
- Locally it is started by hand when needed, never as a login service: `nohup ~/.local/libretranslate/bin/libretranslate
  --host 127.0.0.1 --port 5055 --load-only zh,ja,ru,en > data/run/libretranslate.log 2>&1 &` (stop with
  `pkill -f 'libretranslate --host'`). The VM runs it as a systemd unit. Bind 127.0.0.1 and keep port 5055 closed to other hosts (firewall), as for gnfinder.
- The indexer uses it only with `NAMETRACKER_TRANSLATION_ENABLED=true` and `NAMETRACKER_TRANSLATION_URL`
  (`http://127.0.0.1:5055`); its own HTTP client, not `PoliteHttp` (loopback). Its `/languages` codes are normalised
  (`zh-Hans`/`zh-Hant` → `zh`, `nb` → `no`) and its own code is sent back on `/translate`. A new LibreTranslate or model
  version: change `NAMETRACKER_TRANSLATION_MODEL` (stored with every translation; a translation dropped for a lost name
  is remembered per model in `text_lookup` `TRANSLATE:<model>`) and run `TRANSLATE_TITLE {"all":true}`.

## gnfinder

gnfinder runs natively in development and in production (a systemd unit on the GBIF VM); docker only builds it for the
integration test `GnfinderClientIT` (`docker/gnfinder/Dockerfile`, which pins the version). v1.5.0 has no released
binary, Homebrew's `gnames/gn` tap is at v1.3.0, so install the tag with Go:

```
CGO_ENABLED=0 go install -ldflags "-X github.com/gnames/gnfinder/pkg.Version=v1.5.0" github.com/gnames/gnfinder@v1.5.0
gnfinder -p 8778        # GNFINDER_URL default http://localhost:8778; check: curl localhost:8778/api/v1/version
```

- Locally it is started by hand when needed, never as a login service: `nohup gnfinder -p 8778 > data/run/gnfinder.log
  2>&1 &` (stop with `pkill -f 'gnfinder -p 8778'`). Only production runs it as a service (systemd).
- gnfinder has no bind-address option and listens on all interfaces: keep port 8778 closed to other hosts (macOS
  firewall locally, a firewall rule on the VM).
- A version change: bump `GNFINDER_VERSION` in the Dockerfile, the image tag in `GnfinderClientIT` and the tag above,
  then `go install` again (and restart it). The gnfinder version is not part of the finder `configHash`.

**CLB snapshot modules.** `clb.version` is `1.7.1-SNAPSHOT`; `vocab` (`TaxGroup`) and `nomenclature`
(`TaxGroupAnalyzer`) come from the GBIF snapshot repository. When that repository is unreachable (it refused connections
on 2026-11-15), install them from a CLB backend checkout: `mvn -q -DskipTests -pl nomenclature -am install` in
`~/code/col/backend` (reads the checkout only). A fresh clone needs one of the two before the first build.

**Configuration** (environment variables; defaults in each `application.yml`):
- Both apps: `NAMETRACKER_DB_URL` (default `jdbc:postgresql://localhost:5434/nametracker`), `NAMETRACKER_DB_USER` and
  `NAMETRACKER_DB_PASSWORD` (both default `nametracker`). `NAMETRACKER_BLOBS_DIR` (`./data/blobs`) is relative to the
  working directory, and `mvn -pl <app> spring-boot:run` runs in the module directory: give both apps the same
  absolute path, or `web` finds no blobs and serves no full text.
- `web`: `ADMIN_TOKEN` enables the admin endpoints (`/api/admin/tasks`, `/sources`, `/status`; send it in the
  `X-Admin-Token` header, no XSRF). `NAMETRACKER_ADMIN_ORCIDS` (comma-separated ORCID iDs, read at start) makes an ORCID
  sign-in of a listed iD an admin session, which the admin endpoints also accept (XSRF needed for writes; the list is
  re-checked per request; email sign-in never grants admin; ADR-0006, amendment M6b). With neither set, none of them is
  mapped (404). Admin notices the indexer writes (`admin_notice`: layer failing, still failing, recovered, OAI route
  restart cap) are mailed by `web` (`NAMETRACKER_ADMIN_MAIL_CRON`, `0 */5 * * * *`, `-` = off, ShedLock `admin-mail`): up
  to 20 per run, once each, to every verified address of an account whose ORCID iD is in the admin list; without such an
  address or `spring.mail.host` the outcome is `SUPPRESSED` and it is logged (notice id only). A notice claimed more than
  10 minutes ago (the job's lock time) without an outcome was lost by a run that died and is claimed again. A coverage
  check of a day older than the layer's latest notice writes no notice. Registrations (M6b Task 9):
  `NAMETRACKER_REGISTRATIONS_MAIL_CRON` (`0 */5 * * * *`, ShedLock `registration-mail`) mails the user once when a
  journal registered as a `CANDIDATE` becomes CORE/BROAD (ACCEPTED) or EXCLUDED (DECLINED), never for withdrawn ones;
  `NAMETRACKER_REGISTRATIONS_ADMIN_NOTE_CRON` (`0 0 7 * * *` UTC) writes the admin notice `REGISTRATIONS_WAITING` (key
  `registrations:<date>`) when registrations identified as candidates since the last such note still wait (count, up to
  20 lines with `<public URL>/admin/journals/<id>`; a typed address is marked and defanged, `hxxps://`, as are links in
  page titles). Account merges
  move registrations; `/api/me/export` includes them with their notes. `NAMETRACKER_WEB_ADDRESS` is the bind address
  (default `127.0.0.1`, loopback only like the indexer; `0.0.0.0` to serve other hosts).
- `indexer`: `NAMETRACKER_INDEXER_PORT` (8081), `NAMETRACKER_INDEXER_WORKERS`, `NAMETRACKER_INDEXER_POLL_INTERVAL`,
  `NAMETRACKER_INDEXER_MAX_ATTEMPTS`, `NAMETRACKER_INDEXER_BACKOFF`, `NAMETRACKER_INDEXER_STALE_AFTER` (1h),
  `NAMETRACKER_INDEXER_REQUEUE_INTERVAL` (10m), `NAMETRACKER_INDEXER_HEARTBEAT_INTERVAL` (2m). Task delivery is
  at-least-once, so handlers must be idempotent. A finished task claims for the free workers at once; the poll
  (`NAMETRACKER_INDEXER_POLL_INTERVAL`, 2s) only picks up tasks enqueued by `web` or newly due. `task.started` (V14) is the start of the last attempt; `finished - started` is its run time.
- `indexer` harvest (see ADR-0004):
  - `NAMETRACKER_HARVEST_ENABLED` is the master switch of all scheduled harvests and **defaults to false**. Tasks
    enqueued by hand run regardless.
  - Crons (UTC, six fields): `NAMETRACKER_HARVEST_OPENALEX_CRON` (`0 0 2 * * *`), `NAMETRACKER_HARVEST_SWEEP_CRON`
    (`0 0 4 * * SUN`), `NAMETRACKER_HARVEST_CROSSREF_CRON` (`0 0 3 * * *`). The OpenAlex re-check (`0 30 3 * * *`)
    and the monthly source seeding (`0 0 1 1 * *`) are fixed.
  - `OPENALEX_API_KEY` (recommended, free), `CROSSREF_MAILTO` (optional), `NAMETRACKER_OPENALEX_MAX_DAILY_USD` (1.00),
    `NAMETRACKER_OPENALEX_CONTENT_ENABLED` (true), `NAMETRACKER_OPENALEX_CONTENT_RESERVE_USD` (0.30),
    `NAMETRACKER_BLOBS_DIR` (`./data/blobs`), `NAMETRACKER_CONTENT_OPEN_LICENCES`, `NAMETRACKER_SEED_*` (`NAMETRACKER_SEED_TAXONOMY_TOPICS`, comma-separated OpenAlex topic ids, default the 17 built-in; an id OpenAlex does not know is logged as a WARN per seeding run),
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
    `NAMETRACKER_CLB_MATCH_BASE_URL=http://localhost:18100` (never 8080/8081). It is for development only, not
    throttled, its `GET /dataset` gives the release key to pin, and a bundle of another release than the pinned one
    defers `MATCH_NAMES` 30 minutes without using an attempt (ADR-0005, "Local bundle matching").
  - Layered harvesting (M6): `NAMETRACKER_LAYERS_<OAI|MAGTECH|EUROPEPMC|BLR>_ENABLED` (default true; false also turns the
    layer's handler off, its tasks wait). Daily live crons, UTC, run only with the harvest switch and the layer switch on:
    `NAMETRACKER_LAYERS_OAI_CRON` (`0 30 4 * * *`), `..._MAGTECH_CRON` (`0 45 4 * * *`), `..._EUROPEPMC_CRON`
    (`0 0 5 * * *`), `..._BLR_CRON` (`0 15 5 * * *`); the route rollout is `NAMETRACKER_LAYERS_ROLLOUT_CRON`
    (`0 30 0 * * *`) with `NAMETRACKER_LAYERS_ROLLOUT_PER_NIGHT` (20). A cron enqueues one `LIVE` job per ACTIVE route
    of a harvestable source (none while that route's LIVE job is still queued or running), from 3 days before the
    route's last good run (120 days back if it never ran; Europe PMC always 14 days back); BLR one job from the end of
    its last DONE run (2 days back without one; a window past 9,000 records is cut by day, Zenodo pages no further than
    10,000). The monthly `PROBE_EUROPEPMC` (`0 0 1 1 * *`) needs the harvest and the Europe PMC switch (the handler
    itself is off with the Europe PMC switch); its new route is ACTIVE for a followed (CORE/BROAD) journal with an
    OpenAlex id, else VERIFIED (a journal without an OpenAlex id: the rollout onboards it; a candidate: nothing is
    harvested before an admin promotes it, the rollout then gives it its onboarding pass). A route job that fails writes the route's `last_error`; the attempt that makes
    its task DEAD marks the route BROKEN (no more nightly jobs until `VERIFY_ROUTE` or an admin revives it). Page
    continuations keep their task's `run_after` (depth first). A second lost OAI resumption token marks a route BROKEN
    in a LIVE job only; an ONBOARDING or BACKFILL pass starts again a day later. A set-less OAI route with `issns` keeps
    only records that carry one of them. OAI requests have their own per-host spacing (2 s), apart from the host's
    other traffic.
  - Coverage monitor (M6): `NAMETRACKER_LAYERS_COVERAGE_CRON` (`nametracker.layers.coverage-cron`, `0 30 6 * * *` UTC, not
    behind the harvest switch) enqueues `COVERAGE_CHECK {"day":"<yesterday>"}` (key `coverage-check:<day>`; admin may
    enqueue any day, a rerun overwrites that day). It writes `layer_health` (OK/DEGRADED/FAILING per layer and day) and
    `source_layer_coverage` (28-day sightings and misses per journal). Yield and misses count LIVE-origin works only:
    works of a snapshot backfill or an onboarding pass never count, but a V16 seed sighting of a LIVE work, or an
    onboarding pass that sights an existing LIVE work, does add yield on its day (never a miss). A miss is a LIVE work another layer created 3 to 17 days before the
    day (`work.first_seen_at`), in a journal the layer serves (OpenAlex: harvestable sources with an OpenAlex id;
    Crossref: harvestable sources with an ISSN; OAI, Magtech, Europe PMC: sources with an ACTIVE route of it; BLR none),
    that the layer never saw; a work the layer created itself is no candidate. A layer turning
    FAILING logs a WARN (no mail yet). Thresholds `nametracker.coverage.{fail-days=2, yield-ratio=0.5, miss-share=0.30,
    miss-baseline=0.10}` (env `NAMETRACKER_COVERAGE_FAIL_DAYS`, `_YIELD_RATIO`, `_MISS_SHARE`, `_MISS_BASELINE`).
    `GET /api/admin/status` adds `layers` (last 7 days), `routes` (layer, status, count) and the 28-day `contribution`
    (first-found and only-found per layer, also `contributionByCountry`), as of the last coverage check: the check
    stores them in `layer_contribution` (V18) for LIVE works created in the 28 days up to its day, and the endpoint
    reads the newest stored day. A zero-yield day counts for FAILING only with a DONE run that day.
  - `NAMETRACKER_HARVEST_OUTAGE_DELAY` (30m): a harvest or onboarding page that meets an outage is enqueued again after
    this delay instead of failing.
  - Abstract top-up (M6c, `content/topup`, ADR-0010): after the full-text routes, for a work still without an abstract,
    Europe PMC (keyless; `NAMETRACKER_TOPUP_EUROPEPMC_ENABLED`, `nametracker.content.topup.europepmc.enabled`, default
    true), Springer Nature Meta (`SPRINGER_API_KEY`, free, blank = off; `NAMETRACKER_TOPUP_SPRINGER_DAILY_CAP`
    100, `api_usage` `SPRINGER_META`), SciELO (`NAMETRACKER_TOPUP_SCIELO_COLLECTIONS`, `<doi prefix>=<collection>`,
    default `10.1590/=scl`; also asked for parallels and the declared language), CiNii Research (`CINII_APPID`, free,
    blank = off; only abstracts flagged `allow`). Each service at most once in 180 days per work (`text_lookup`; outages
    and spent caps are not remembered; a NO_TEXT work that met either gets the delayed recheck). Base URLs
    `nametracker.content.topup.{springer,scielo,cinii}.base-url`. OAI JATS (`OAI_JATS`) is the first full-text route
    for journals with a `jats` OAI route; it asks nothing with `NAMETRACKER_LAYERS_OAI_ENABLED=false` or for a CEASED
    journal. A TEI newly reported by OpenAlex re-resolves a
    work (`RESOLVE_CONTENT {"tei":true}`, REPROCESS) at most `NAMETRACKER_CONTENT_TEI_RECHECK_PER_DAY` (200) a day.
  - Institutional route `INSTITUTIONAL` (M6c, University of Copenhagen subscriber TDM APIs): **off**,
    `NAMETRACKER_INSTITUTIONAL_ENABLED` (`nametracker.content.institutional.enabled`, false) plus the allow-list
    `NAMETRACKER_INSTITUTIONAL_PUBLISHERS` (`ELSEVIER`, `WILEY`; empty). **Switch it on only after the KU library has
    confirmed in writing which licences permit text and data mining for this use** (and whether Garrulus counts as
    research under the EU TDM exception, DSM Art. 3; spec "Institutional access"). Elsevier needs `ELSEVIER_API_KEY`
    (+ `ELSEVIER_INSTTOKEN` outside the KU IP range); Wiley needs `WILEY_TDM_TOKEN` and a local GROBID
    (`NAMETRACKER_INSTITUTIONAL_GROBID_URL`) for its PDFs, else it is not loaded (WARN). Daily caps
    `NAMETRACKER_INSTITUTIONAL_{ELSEVIER,WILEY}_DAILY_CAP` (500, counted in `institutional_fetch`). It runs last in
    `RESOLVE_CONTENT`, and only when every earlier route answered (not after one was down or waited for its budget);
    only for DOIs of an allowed publisher, licence not open, no other full text, not answered within
    `nametracker.content.institutional.refetch-after` (365d) and not FAILED (e.g. a body over the size limit) within 30
    days (a reprocess re-fetch asks again); `freeOnly` does not skip it (no cost per call). Every request is a single
    attempt, audited in `institutional_fetch` (V21: work, DOI, publisher, API, status, bytes, outcome) and counted
    against the cap; 429/5xx/IO or GROBID down defers the task 30 minutes, a full cap to 00:05 UTC, a busy publisher
    lock 5 minutes. A 401, or `nametracker.content.institutional.max-consecutive-403` (5) 403s in a row (rows marked
    REFUSED_SYSTEMIC, which block nothing), turns the publisher off until restart. Its text is closed (spool, finder,
    purge), its abstracts are never stored. `GET /api/admin/status` reports `institutional` (per publisher `today`,
    `last30Days`).
- `indexer` matching and index (ADR-0005):
  - `NAMETRACKER_CLB_BASE_URL` (`https://api.checklistbank.org`): the XR (`GET /dataset/3LXR`) and name matching, not
    throttled. A CLB outage defers `MATCH_NAMES`/`XR_CHECK` 30 minutes without using an attempt. When
    a single match call fails (after the HTTP retries, or with a 4xx), the same match endpoint and release are probed
    with `Animalia`/kingdom: probe ok → the name is at fault, placed `NONE` (not cached, WARN) and the work goes on;
    probe fails retryably → outage; probe 4xx → ordinary failure (attempts, `DEAD`). More than 3 names at fault in one
    task → the whole task is deferred as an outage and nothing is written.
  - A task deferred by an outage that was first enqueued more than 24 hours ago is logged as a WARN on every deferral.
  - `NAMETRACKER_SPOOL_MAX_AGE` (14d) bounds the spool; it is purged daily at 05:30 UTC.
  - Layer health (`nametracker.coverage.*`, properties and `NAMETRACKER_COVERAGE_*`, start values to tune after a month):
    `fail-days` 2 (failed run days), `zero-fail-days` 3 (zero-yield days, counted only on days with a DONE run whose
    expected yield, the median of that weekday over 8 weeks, is at least `zero-yield-min-expected` 3.0; quiet days are
    transparent, a day without a DONE run breaks the streak), `yield-min-median` 14 (the 7-day yield ratio rule applies
    from this weekly median), `yield-ratio` 0.5, `miss-share` 0.30, `miss-baseline` 0.10. `nametracker.public-url`
    (`NAMETRACKER_PUBLIC_URL`, `http://localhost:5173`) is the link base of admin notices. A layer turning FAILING, still
    FAILING after 7 days and OK again after failing writes an `admin_notice`; `nametracker.layers.oai.max-long-restarts`
    (3): an ONBOARDING/BACKFILL OAI pass restarts a day later at most that many times after losing its resumption token
    again, the next loss makes the route BROKEN and writes a `ROUTE_RESTART_CAP` notice (the count resets on the route's
    last page and on a passing `VERIFY_ROUTE`).
  - `NAMETRACKER_STATS_CRON` (`0 30 4 * * *`): nightly `REBUILD_STATS` (ubiquity, taxon stats) at `LIVE` priority, which
    then enqueues `CATEGORISE_SOURCES`. `XR_CHECK` runs daily at `0 0 6 * * *`. Neither is behind the harvest switch.
- `indexer` lifecycle and backfill (ADR-0007):
  - `NAMETRACKER_CLB_MATCH_PARALLELISM` (8): parallel CLB match calls inside one `XR_PREMATCH` task.
  - `nametracker.xr.max-lost-share` (0.05, property only): the XR gate refuses a release that loses more than this
    share of the keys matched in the current one.
  - `NAMETRACKER_DICTIONARY_DIR` (blank = off): directory of `dictionary-<xrKey>.bin`. `BUILD_DICTIONARY` builds the file
    of a new release there from `NAMETRACKER_XR_ZIP_URL` (default
    `https://download.checklistbank.org/col/xr_latest_coldp.zip`) and the in-process finder reloads it. With an empty
    directory the indexer builds the current release's dictionary at start (about 1.4 GB), and `FIND_NAMES` waits
    (30 minutes, no attempt) until it is ready. The build runs inside the indexer: check its `-Xmx` against the
    dictionary build's peak heap before the GBIF deploy. `NAMETRACKER_XR_ZIP_READ_TIMEOUT` (5m): a zip download silent
    that long is broken off and deferred as an outage.
  - `nametracker.finder.rollout-on-start` (true, property only): at start, a changed finder `configHash` enqueues
    `REPROCESS_NAMES {"all":true,"rollout":hash}`; the first start only records the baseline. After a dictionary
    bootstrap the finder was not ready at start, so restart once before changing the finder config (else that start
    becomes the baseline and the change is not rolled out). Two rollouts that overlap both run in full.
  - `NAMETRACKER_SNAPSHOT_BASE_URL` (`https://openalex.s3.amazonaws.com`): the free OpenAlex snapshot;
    `NAMETRACKER_SNAPSHOT_READ_TIMEOUT` (5m): a works file silent that long counts as broken off (the task fails and
    reads the file again).
- `indexer` ISSN-L and the candidate registry (M6b, Task 5): `REFRESH_ISSNL` (`{}`) streams the ISSN IC zip
  (`nametracker.discovery.issnl.url`, env `NAMETRACKER_DISCOVERY_ISSNL_URL`, default
  `https://www.issn.org/wp-content/uploads/2014/03/issnltables.zip`, 27 MB, `…_READ_TIMEOUT` 5m) into `issn_link` in one
  transaction (COPY into a temp table, then upsert); a zip without the `ISSN_ISSN-L_table` entry is a WARN and keeps the
  table. **Off by default** (`nametracker.discovery.issnl.enabled`, env `NAMETRACKER_DISCOVERY_ISSNL_ENABLED`, false):
  the scheduler enqueues nothing and a task that runs anyway ends DONE without a call. Open question for Markus: the zip
  URL is the file the ISSN IC's "on demand" form serves and is undocumented; switch on only after he confirms it may be
  used. Without the table the `CandidateRegistry` still works and matches by plain ISSN, then OpenAlex id.
- `indexer` `DISCOVER_SOURCES` (`{"origin":"DOAJ"|"PKP_BEACON"|"PLAZI"|"COL_REFERENCES"|"BLR",…position}`, M6b Tasks 7 and 8; continuation key
  `discover:<origin>:<sha12 of the payload>` at the task's priority; offers to the `CandidateRegistry` go in at
  `BACKFILL`): nothing is harvested, a discovered journal is a `CANDIDATE` until an admin promotes it. Each origin's switch
  `nametracker.discovery.<origin, lower case, hyphens>.enabled` (default true) is read on every run: a disabled origin
  ends DONE with an INFO and no call. An outage defers the task without an attempt. **DOAJ**: one page of 100 per task
  over `nametracker.discovery.doaj.lcc-codes` (env `NAMETRACKER_DISCOVERY_DOAJ_LCC_CODES`, comma-separated, blank =
  `QL1-991,QK1-989,QH1-278.5,QR1-502,QH540-549.5,QE701-760`, about 600 journals; Biology (General) QH301-705.5 has 482
  and is left out), position `{"code","page"}`; signal detail `{"doajId","lcc":[…]}`, raw `DOAJ_LCC` rows. **PKP Beacon**
  (one task): streams `nametracker.discovery.pkp-beacon.url` (env `NAMETRACKER_DISCOVERY_PKP_BEACON_URL`, default
  `https://dataverse.harvard.edu/api/access/datafile/14084919`, `beacon.tab` of doi:10.7910/DVN/OCZNVY v7, CC0, 32 MB,
  `…_READ_TIMEOUT` 5m), replaces `pkp_beacon` with every row that has an ISSN (COPY, one transaction: a broken download
  changes nothing), then offers the rows that pass the filter with a PENDING `OAI` route hint `{"base","set","prefix":
  "oai_dc","issns"}` and signal detail `{"context","records2025","oaiUrl"}`. Filter: a topic term at a word start of the
  accent-folded lower-cased context name (`nametracker.discovery.pkp-beacon.include`, comma-separated, blank = built-in
  zoolog, taxonom, entomolog, botan, mycolog, fauna, flora, natural history, museum, ... museu, museo), none of
  `….exclude` (blank = museolog, museum studies, veterinar, medic, pharmac), endpoint not unresponsive, and
  `records2025 >= 5` or a last OAI response within 365 days (a zero count alone never excludes: Zootaxa reports 0).
- `indexer` discovery from titles and BLR (M6b Task 8), all offers at `BACKFILL`, a signal per journal (`signal_key` empty,
  or the title key for a journal found through a title). **Titles** (Plazi, COL references) are kept in `discovery_title`
  (`origin`, `title_key` = `JournalTitleKey`, `refs`, `datasets`, `detail`, `resolved_issns`, `resolved_at`) and phase 2
  (`{"origin","resolve":true,"after":key}`, 100 rows per task) asks Crossref `/journals?query=` (5 items) and takes the
  first whose title key equals the row's: found → offer with the row's detail; else only `resolved_at` is set, and a row is
  looked at again after 180 days. **Plazi** (`PLAZI`, phase 1 `{}`): `nametracker.discovery.plazi.base-url` (default
  `https://tb.plazi.org/GgServer/srsStats/stats`, 1 s per request) for the current year and the two before, summed per
  journal label (labels without a letter such as "183" skipped), `plazi.min-articles` (3) articles, detail
  `{"treatments","articles","label"}`. **COL references** (`COL_REFERENCES`, phase 1 `{}`): streams
  `nametracker.finder.xr-zip-url` (read timeout `nametracker.finder.xr-zip-read-timeout`, outages as `BUILD_DICTIONARY`),
  reads `Reference.tsv|txt|csv` (headers with or without `col:`), groups by normalised `issn` else by title key of
  `containerTitle`; groups with `col-references.min-references` (10) references: ISSN groups are offered at once, title
  groups (the `col-references.max-titles` = 2000 largest) go to `discovery_title`; detail `{"references","datasets","title"}`.
  **BLR** (`BLR`, one task, no call): `layer_unmatched` DOIs of the last 12 months by ISSN, `blr.min-dois` (2) or more
  offered, detail `{"dois","lastSeen","example"}`; `lastSeen` (newest first sighting) is the dated evidence of life.
  Switches `nametracker.discovery.<plazi|col-references|blr>.enabled` (default true). `DiscoveryScheduler`
  (`nametracker.discovery.cron`, default `0 15 1 1 * *` UTC, behind `nametracker.harvest.enabled`) enqueues per month
  `DISCOVER_SOURCES` for each enabled origin of `DOAJ,PKP_BEACON,PLAZI,COL_REFERENCES,BLR` (key `discover:<o>:<yyyy-mm>`)
  and `REFRESH_ISSNL` (key `issnl:<yyyy-mm>`) only when `nametracker.discovery.issnl.enabled` is true (default false); a
  disabled origin enqueues nothing. The OpenAlex seeding (`SEED_SOURCES`, origin `SEED_TOPIC`) stays on its own cron; it now
  writes a `SEED_TOPIC` signal `{"taxonomyWorks","broadWorks","totalWorks"}`, adopts an ISSN-only candidate (sets its OpenAlex
  id) instead of adding a second row, and never re-tiers a CEASED source.
- `indexer` candidate sampling and score (M6b Task 10). `SAMPLE_SOURCE` (`{"sourceId":n}`, BACKFILL, key `sample:<id>:<yyyy-mm-dd>`;
  skipped for a CEASED or non-`CANDIDATE` source; handler off without a finder) reads the newest articles of a candidate
  and stores **one aggregate row** in `source_sample` (`route`, `articles`, `with_abstract`, `names_per_article` = distinct
  non-OTU names per article, `act_share`, `taxgroups` = shares of the 5 biggest groups of up to 60 names matched in the
  current release through `ClbClient` + `TaxGroups`); the articles are transient and nothing goes to `work`, `mention`,
  `name_string` or `name_match`. Routes in order, the first with at least 5 articles of the last 2 years wins (at most 20, newest
  first; with none the route with most articles is kept): Crossref by ISSN (`filter=issn:…,from-pub-date:…,type:journal-article`,
  `sort=published&order=desc`), an OAI route of the source (status VERIFIED or ACTIVE: **an unverified (or BROKEN, PAUSED) route's host is
  never fetched**; at most 3 pages from 2 years ago), Europe PMC by ISSN (`FIRST_PDATE`, `P_PDATE_D desc`), OpenAlex by source id
  (through the budget; a spent budget defers). A finder that is not ready or down, CLB, or any route that is down defers 30
  minutes without an attempt. The score (`CandidateScore`, 0 to 1; weights `nametracker.candidates.score.*`, properties
  only: see `application.yml`) is `0.7 * sample + 0.3 * signals` with a sample of at least 5 articles, else `0.5 * signals`;
  signals count distinct origins, Plazi `articles` and COL `references` (**summed over all signal keys** of a source), and a
  registration. `CandidateScorer.rescoreAll` recomputes every active CANDIDATE and writes only changed scores. `SampleScheduler`
  (`nametracker.candidates.sample-cron`, default `0 30 1 * * *` UTC, env `NAMETRACKER_CANDIDATES_SAMPLE_CRON`; behind
  `nametracker.harvest.enabled` and `nametracker.candidates.sampling.enabled`, env `NAMETRACKER_CANDIDATES_SAMPLING_ENABLED`,
  default true): rescoring, then `SAMPLE_SOURCE` (never for a source that already has a QUEUED or RUNNING one; the handler skips a source sampled within 365 days unless `"force":true`) for up to `nametracker.candidates.sample-per-night` (200, env
  `NAMETRACKER_CANDIDATES_SAMPLE_PER_NIGHT`) active, not ceased candidates without a sample or with one older than 365 days:
  registered first, then never sampled, then by score descending.
- `indexer` `IDENTIFY_SOURCE` (`{"sourceId":n}`, M6b Task 6; queued by the registry for every new candidate, key
  `identify:s<n>`): completes the source step by step, each written at once: ISSN-L and siblings from `issn_link`;
  Crossref `/journals/{issn}` (ISSNs, the title while it is still an ISSN, a missing publisher); OpenAlex by id or
  `/sources/issn:` (id, ISSN-L, ISSNs, country, host, OA flags, raw subfields/topics; a spent budget skips the step);
  DOAJ (`NAMETRACKER_DOAJ_BASE_URL`, default `https://doaj.org/api/v4`; switch `nametracker.discovery.doaj.enabled`,
  env `NAMETRACKER_DISCOVERY_DOAJ_ENABLED`, true: its ISSNs, `in_doaj` (DOAJ alone sets it while switched on), raw
  `DOAJ_LCC`, `doaj_discontinued`, `successor_issns`, a missing country); a PKP Beacon row of its ISSNs gives a PENDING
  OAI route (`VERIFY_ROUTE` only for a CORE or BROAD source) unless an OAI route with that base and set exists; then `PROBE_EUROPEPMC` and the derived
  disciplines. A CEASED or deleted source is skipped; an outage defers the whole task. After every identity write
  (locked transaction) any other source sharing an ISSN, the ISSN-L or the OpenAlex id is a duplicate (`SourceFold`):
  the loser is chosen by foldability, not by age: only a `CANDIDATE` without works, without an `ACTIVE` route that has
  harvested (`last_ok`/`onboarded_at`), without admin curation (`tier_set_by`/`discipline_set_by` ADMIN) and without a
  different OpenAlex id folds (ISSNs, OpenAlex id, signals, routes, raw rows, samples, registrations, follows move;
  duplicates dropped; the loser is deleted). So a candidate folds into a followed, curated or EXCLUDED partner whatever
  the ids, and of two foldable candidates the younger into the older; when neither may fold, WARN and both stay (a held
  OpenAlex id is then not written, nor that OpenAlex answer's ISSNs). The fold's delete only removes a candidate without
  admin curation (a concurrent promotion wins; the fold's transaction fails and the task retries), and a registration
  that lands on an EXCLUDED journal counts as a direct hit (no "declined" mail). Folding runs to a fixpoint (the
  survivor's grown keys are scanned again); when the identified source is folded away, the remaining steps (DOAJ,
  Beacon, Europe PMC probe) run for the survivor, and stop when it is CEASED. `CandidateRegistry.offer` folds the same
  way after it adds keys to an existing source and names the survivor; seeding and `ADD_SOURCE` write through
  `SeedUpsert` (locked find by OpenAlex id, adoption of an ISSN-only candidate, upsert, the tier, fold). Only a lost
  database deadlock (SQLState 40P01) in identify, offer or a seed write defers the task by one minute without an
  attempt; a lock timeout uses attempts. Only offers with identity evidence (`Candidate.evidence`: DOAJ discovery, a
  title resolved by Crossref) widen a known source's ISSNs; PKP Beacon, COL ISSN groups, BLR and registrations only add
  their signal there (a PKP Beacon offer also adds its OAI route to a known live journal: PENDING, `VERIFY_ROUTE` at
  once only when followed). `Issn.normalise` rejects the placeholder `0000-0000`. Stored ISSNs are upper case in the
  hyphenated form (V20), so ISSN lookups use the indexes.
- `indexer` `IDENTIFY_SOURCE {"registrationId":n}` (M6b Task 9; enqueued by `web` at `LIVE`, key `identify:r<n>`): a
  no-op unless the registration is `IDENTIFYING`. ISSN as given; DOI: Crossref's work (ISSNs, container title,
  publisher), else OpenAlex's source of the work, else `DOI_UNKNOWN`; URL: `JournalPage` reads the page only from public
  hosts on the ports 80/443/8080/8443 (`PublicHost` in `store`, the rule `RouteConfigCheck` uses too): its own Apache
  HttpClient with the DNS answer pinned to the checked addresses (no rebinding), redirects by hand (at most 3, every hop
  checked), status/header lines ≤ 8 KB and ≤ 100 headers, `text/html` only, at most 2 MB, one 60 s deadline for the
  whole fetch (a timer cancels it); no retries. The page is read by a linear scanner (`PageMarkup`: first 512 KB, tags
  ≤ 4 KB, 2 s budget, else `LOOKUP_FAILED`). ISSNs (at most 4) from `citation_issn`/`prism.issn`/`prism.eissn`/
  `dc.identifier` metas, else the first 3 in the text, else a Magtech path `/CN|EN/<ISSN>/`; title (≤ 500 code points)
  from `citation_journal_title` or `og:site_name`; "Powered by Beijing Magtech" or the Magtech path gives a `MAGTECH`
  route hint `{"site","issn"}`, an OJS generator meta an `OAI` hint on `<journal>/oai`. Reasons: `DOI_UNKNOWN`,
  `ADDRESS_NOT_PUBLIC`, `ADDRESS_UNKNOWN`, `PAGE_UNREACHABLE`, `NO_ISSN_FOUND`, `LOOKUP_FAILED` (any non-outage lookup
  error; state `NOT_IDENTIFIED`). Else an offer (origin `REGISTERED`, signal key = registration id, detail
  `{"registrationId":n}`, at `LIVE`): outcome `NEW_CANDIDATE`, `EXISTING` or `CEASED`, `tier_at_identification` recorded.
  A page's ISSNs that hit more than one source or a non-CANDIDATE only add the signal to the best match (no identity
  widening, no fold). Crossref/OpenAlex outages and a spent OpenAlex budget defer.
- `indexer` routes found by registration or discovery (origin `REGISTERED`/`DISCOVERED`) stay `PENDING` and get no
  `VERIFY_ROUTE` (it skips them) until their journal is CORE or BROAD; a promotion (admin PATCH in `web`, `ADD_SOURCE`,
  the seeding) enqueues `VERIFY_ROUTE` for the source's PENDING routes.
- `indexer` `PoliteHttp` follows redirects itself (the client is `Redirect.NEVER`; 301/302/303/307/308, at most 5,
  never https→http) and checks the start URL and every hop with `PublicHost`. Exempt are only the exact authorities
  (scheme, host, port) of the configured service base URLs it fetches (`HarvestConfig.SERVICE_URLS`: OpenAlex, CLB and
  CLB match, Crossref, Europe PMC, DOAJ, PMC, Pensoft, Zenodo, Plazi, snapshot, XR zip, ISSN-L, Beacon; never
  `public-url`, gnfinder or a finder service) and the entries of `nametracker.http.trusted-hosts` (comma-separated
  `host:port` or URL; a bare host trusts every port of it), and only while the chain never left them: a public site
  cannot redirect into a trusted local service. A source folded into a CORE/BROAD one gets `VERIFY_ROUTE` for its
  PENDING routes. A name that does not resolve (in the check or at connect) is looked up once more after 2 s before it
  is an unknown host (non-retryable, a route BROKEN; an outage for the fixed API hosts). The JDK client resolves again
  when it connects (DNS rebinding is a known limit, ADR-0009). In
  production list `host:port` in `nametracker.http.trusted-hosts` (env `NAMETRACKER_HTTP_TRUSTED_HOSTS`) only for a local
  service; a route URL equal to a trusted (dev, local) authority is fetched with trust.
- `indexer` ceased journals and disciplines (M6b Tasks 2 and 11, ADR-0009). `source.publication_status` ACTIVE or CEASED
  (`ceased_reason` STOPPED, TITLE_CHANGE, MERGED, UNKNOWN; `last_issue_year`, `continued_by`; set through the admin PATCH).
  A CEASED journal is skipped by every layer job, the harvests, sampling and identification; a batch trimmed of it while a
  cursor is in flight restarts with cursor `*`. Dormancy proposals (coverage check, `dormant_since`/`dormant_evidence`,
  active CORE/BROAD only) and revival proposals are shown in the admin list, never applied. Disciplines: vocabulary
  `discipline` (9 codes, `replaced_by` for retired ones) with crosswalks `discipline_mapping` (`OPENALEX_SUBFIELD`,
  `DOAJ_LCC`, `TAXGROUP`); `DisciplineService` derives them from `source_classification_raw` (first rule wins: OpenAlex
  subfields, DOAJ LCC, taxonomic focus) unless an admin set them (`discipline_set_by`). Seeding thresholds:
  `nametracker.seed.{core-min-works=20, core-min-share=0.30, broad-min-works=30, broad-min-share=0.50,
  candidate-min-works=10}` (env `NAMETRACKER_SEED_*`).
- `indexer` identifier lookups (M6c; `LINK_WORK`, `LINK_SOURCE`): `nametracker.links.enabled` (master) and
  `nametracker.links.<name>.enabled` (default true) for `crossref openalex europepmc biodiversitypmc zenodo-blr plazi
  wikidata ipni crossref-journal bhl-doi`. Behind the harvest switch: `NAMETRACKER_LINKS_SOURCE_CRON` (`0 45 1 2 * *`,
  `LINK_SOURCE` for every journal monthly) and `NAMETRACKER_LINKS_RETRY_CRON` (`0 15 6 * * *`, due misses: tasks of
  5000 ids, at most `NAMETRACKER_LINKS_RETRY_PER_DAY`, 100000, subjects per entity and day). A lookup whose service is
  down or whose daily cap is spent is skipped for the rest of the task: its targets get `next_try` = the retry time
  without counting a try (`link_attempt.attempts` 0) and the next lookup and page go on; only a task in which no lookup
  could ask anything is deferred. `nametracker.links.biodiversitypmc.max-requests-per-day` (200): Biodiversity PMC
  answers about 860 KB a document and cannot be asked for less, so that lookup applies only to works with acts or from
  a Plazi/BLR journal and counts its requests per UTC day in `api_usage` (`biodiversitypmc`); when the cap is spent its
  targets wait for 00:05 UTC. Base URLs: `nametracker.links.{plazi,wikidata,ipni,biodiversitypmc}.base-url`.
- `indexer` languages (M6c, ADR-0010): `nametracker.language.{min-margin=0.10, min-title-words=3, min-text-letters=100,
  min-confidence=0.80, high-accuracy=false}` (properties only). Lingua's confidence is max-normalised, so the margin
  over the second language is the gate; `high-accuracy` costs about 1 GB of heap. After a change, `DETECT_LANGUAGE
  {"all":true}`. Titles: `NAMETRACKER_TRANSLATION_ENABLED` (false), `NAMETRACKER_TRANSLATION_URL` (blank = off),
  `NAMETRACKER_TRANSLATION_MODEL` (`libretranslate-1.9.6/argos`), `NAMETRACKER_TRANSLATION_TIMEOUT` (30s); see
  "LibreTranslate".
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

All of these need `ADMIN_TOKEN` on `web` and the header `X-Admin-Token: $ADMIN_TOKEN` (or, in a browser, an admin
session: an ORCID sign-in of an iD in `NAMETRACKER_ADMIN_ORCIDS`). Use the local ports of a
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
  `REBUILD_STATS`, `CATEGORISE_SOURCES`, `EVALUATE_HITS` (`workId`; enqueued after a work is `INDEXED` and by `RESCORE_SALIENCE` for works with a new main subject: writes `hit` rows for matching subscriptions once per subscription, work and kind; `NOTIFIED` is unused), `RESCORE_SALIENCE` (`{"afterId":0,"batch":500,"all":false}`: recomputes salience, main subject and the work's codes/taxgroups/has_acts from stored mentions and the current ubiquity, placements untouched, continues per batch at REPROCESS; targeted at works with a name whose ubiquity penalty moved by more than 0.05 since `name_ubiquity.scored_share`, `REBUILD_STATS` enqueues it when there is one; admin `{"all":true}` rescores everything, e.g. after changing salience properties), the one-off admin `CLEAN_TITLES` (`{"afterId":0,"batch":1000}`: runs `TitleText.clean`, which the harvest applies to every incoming title (entities decoded, markup stripped, whitespace collapsed), over the stored work titles in id order, continues per batch at REPROCESS, writes only changed titles; a named work whose document changes with its title gets `FIND_NAMES` at REPROCESS) and the admin-only `FIND_PENDING` (`{"limit":n}`, default 100), which enqueues
  `FIND_NAMES` for up to n `CONTENT_RESOLVED` works the finder never saw, highest work id first. The queue runs by
  priority (`LIVE`, `REPROCESS`, `BACKFILL`), then `run_after`, then id. A follow-up (`RESOLVE_CONTENT` → `FIND_NAMES` →
  `MATCH_NAMES`, `REBUILD_STATS` → `CATEGORISE_SOURCES`) inherits the priority and the `run_after` of its task, so it
  takes its parent's place in the queue: depth first, a work goes all the way to `INDEXED` before the peers queued
  after it start.
- Lifecycle task types (M5, ADR-0007):
  - `FIND_NAMES` also takes `"force":true` (run the finder again on the same document; a listed work goes to the pending
    index) and `"refetched":true` (set by a reprocess re-fetch: if the text is gone again, keep the names). `RESOLVE_CONTENT`
    also takes `"freeOnly":true` (skip paid routes) and `"reprocess":true` (no free full text, or a route that failed
    for good: keep the names, write nothing; a route that is down defers it 30 minutes).
  - `REPROCESS_NAMES` (`{"afterId":0,"batch":1000,"all":false,"force":true,"source":null,"workIds":null,"yearFrom":null,
    "yearTo":null,"rollout":null}`, batch at most 10000): forced `FIND_NAMES` at REPROCESS for works the finder has seen,
    continues per batch. Does nothing without a filter or `"all":true`; `force:false` enqueues plain `FIND_NAMES`, which
    does not name an already named document again but runs its `MATCH_NAMES` again (re-placed in the current release,
    `indexed_at` set; no `ARTICLE` for a follow that matched before).
  - `MATCH_PENDING` (`{"limit":n}`, default 1000, admin): enqueues `MATCH_NAMES` for listed works whose pending index has
    no QUEUED or RUNNING `MATCH_NAMES` (its task ended DEAD, or its enqueue was swallowed by a dedup key still held).
    `XR_FINISH` runs the same sweep (up to 100,000 works).
  - `XR_SWITCH` (`{"key":k,"refuse":false,"force":false}`; `auto` is set by `XR_CHECK`): starts PREPARING for release k.
    `refuse:true` also marks the old release REFUSED (rollback hold: no automatic switch back). `force:true` skips the
    gate. A refused key is cleared by an admin switch. Refused while another switch is running (also while the last
    one is SWITCHING).
  - `XR_PREMATCH` (`{"key","gen","afterName","afterHint","batch":2000}`) → `XR_GATE` (`{"key","gen"}`) → flip →
    `BUILD_DICTIONARY` (`{"key":k}`) and `XR_REPLACE` (`{"key","batch":500}`) → `XR_FINISH` (`{"key"}`). All steps are
    chained and idempotent; do not enqueue them by hand except to recover. `XR_CANCEL` (`{"key","refuse":false}`) puts a
    PREPARING, PREPARED or gate/admin-refused release back to SEEN (or REFUSED); after the flip it is an ERROR no-op.
  - `BACKFILL` (`{"fromYear":2000,"toYear":<this year>,"files":null,"fromFile":0}`, all optional): reads the snapshot
    manifest, creates a run and one `BACKFILL_FILE` (`{"runId","idx","records"}`, never by hand) per file of the slice at
    priority `BACKFILL`. Imports articles and reviews of active CORE/BROAD sources, `origin=BACKFILL`, and queues
    `RESOLVE_CONTENT`; an existing work (same OpenAlex id or DOI) is never changed. A repeated identical `BACKFILL` of a
    RUNNING run re-enqueues only the files not done (a DEAD file gets a new task); of a DONE run it starts a new run.
    A deploy (restart) during a backfill can use up attempts of the files being read; re-run the same `BACKFILL` to
    revive files that ended DEAD.
- `LINK_WORK` / `LINK_SOURCE` (M6c): `{"workId"}`/`{"sourceId"}`, `{"ids":[…]}` (at most 5000) or
  `{"afterId":0,"batch":…,"schemes":[…]}` (continues per batch). `LINK_WORK` is enqueued at `BACKFILL` when a work is
  indexed the first time, except for works of origin `BACKFILL` (snapshot backfill) and while no work lookup is on;
  `LINK_SOURCE` after a journal's completion and monthly. A miss is asked again after 30 and 180 days, then never.
  One-off back-fill of existing works after the M6c deploy, **and again after the full snapshot backfill** (its works
  are linked only this way): `LINK_WORK {"afterId":0}` at `BACKFILL`; a new lookup back-fills with
  `{"afterId":0,"schemes":[…]}`.
- `TRANSLATE_TITLE` (M6c): `{"workId":n,"force":false}` or `{"all":true,"afterId":0,"batch":500,"force":false}` (non-Latin
  scripts first, continues per batch). Enqueued when a named work's language is not English and it has no English title;
  off (DONE, INFO) without the translation switch and URL. Stores a MACHINE `work_text` title, never `work.title`.
- `DETECT_LANGUAGE` (M6c, `{"afterId":0,"batch":1000,"all":false}` or `{"workIds":[…]}`, admin): detects the language from title,
  abstract and retained full text and decides again. By default only works whose `language_source` is null (legacy rows) or
  `UNDECIDED` are read; `"all":true` re-decides every work (after changing the detector or `nametracker.language.*`);
  `workIds` always decides the works named. `LanguageService.decide` is the only writer of `work.language`/`language_source`.
  Queue `DETECT_LANGUAGE {}` once after the M6c deploy.
- Re-indexing: a work that is `INDEXED` stays `INDEXED` and listed while a new `FIND_NAMES` result waits in
  `mention_pending` / `classification_context_pending`; `MATCH_NAMES` swaps it in one transaction, without a gap.
  A re-index gives a follow an `ARTICLE` hit only if the follow did not match the previous index (ADR-0006,
  amendment), judged at the text level that index was built from (`work.names_text_level`, V13) and placed in the
  current release; this holds for any `MATCH_NAMES` of a listed work, with or without a pending index. An XR switch never writes a hit. The first release-based match of a 5M-work index is a large CLB load.

```
# pin the XR (first key seen), then name and index every resolved work
curl ... -d '{"type":"XR_CHECK","payload":{},"priority":"LIVE","dedupKey":"xr-check:manual"}'
curl ... -d '{"type":"FIND_PENDING","payload":{"limit":100000},"priority":"REPROCESS","dedupKey":"find-pending:manual"}'
```

```
# M5 by hand (18080, $ADMIN_TOKEN as above)
# switch to a release by key (also back to an older one); add "refuse":true to hold the old release, "force":true to skip the gate
curl ... -d '{"type":"XR_SWITCH","payload":{"key":315557},"priority":"REPROCESS","dedupKey":"xr-switch:manual"}'
# cancel before the flip, optionally refusing the key
curl ... -d '{"type":"XR_CANCEL","payload":{"key":315557,"refuse":false},"priority":"LIVE","dedupKey":"xr-cancel:manual"}'
# reprocess one journal's works with the current finder (source = source.id), then everything
curl ... -d '{"type":"REPROCESS_NAMES","payload":{"source":42,"batch":500},"priority":"REPROCESS","dedupKey":"reprocess:manual:src42"}'
curl ... -d '{"type":"REPROCESS_NAMES","payload":{"all":true},"priority":"REPROCESS","dedupKey":"reprocess:manual:all"}'
# backfill a slice: 3 snapshot files from file 10, years 2020 to 2024 (omit for 2000 to now, all files)
curl ... -d '{"type":"BACKFILL","payload":{"fromYear":2020,"toYear":2024,"files":3,"fromFile":10},"priority":"BACKFILL","dedupKey":"backfill:manual"}'
```
- `GET /api/admin/sources?tier=&active=&q=&origin=&country=&publicationStatus=&dormant=&registered=&proposal=&revival=&sort=&limit=&offset=`
  lists sources as `{"total":n,"items":[…]}` (**breaking for scripts that read the old array**; limit at most 500,
  default 100; `sort` is `score` (default for `tier=CANDIDATE`; candidate score, unscored last), `title` or `id`). An item
  carries origin, country, candidate score, the origins of its signals, its newest sample, its routes by status, the
  number of registrations, and the proposals, which are shown and never applied: `proposal` (`PROMOTE` for a BROAD journal
  with act rate >= 0.10 and >= 5 names per work, `DEMOTE` for a CORE one with act rate < 0.02 and < 1 name per work and for
  a BROAD one with < 0.5; properties `nametracker.admin.proposals.{promote-act-rate,promote-names,demote-core-act-rate,
  demote-core-names,demote-broad-names}`), `dormant` (the coverage check's proposal) and `revival` (a CEASED journal with dated signs of life: a BLR `lastSeen` or a REGISTERED
  signal newer than the status change; an EXCLUDED one: a REGISTERED signal of the last 90 days; a signal's `last_seen`
  and PKP records are no evidence).
- `GET /api/admin/sources/{id}` is the detail: signals (a `REGISTERED` one with the user's note and display name),
  samples, routes, registrations, disciplines with who set them, raw classifications, status and dormancy evidence.
- `PATCH /api/admin/sources/{id}` with any of `tier`, `active`, `publicationStatus` (+ `ceasedReason`, `lastIssueYear`,
  `continuedBy`), `dismissDormant`, `disciplines` (codes of the vocabulary, not retired; sets them as ADMIN) or
  `releaseDisciplines` (back to the derivation). A promotion to CORE/BROAD of an active, not yet onboarded source
  enqueues its onboarding (`ONBOARD_SOURCE`; for a journal without an OpenAlex id and without a PENDING, VERIFIED or ACTIVE
  route that is a 120-day Crossref `ONBOARDING` pass, key `onboard-crossref:<id>`, whose last page onboards it) and
  verifies its PENDING routes. When `VERIFY_ROUTE` marks the last PENDING/VERIFIED/ACTIVE route of such a followed,
  not yet onboarded journal BROKEN, it enqueues `ONBOARD_SOURCE` again at once. The PATCH runs in one transaction that
  locks the row first (a concurrent fold waits and then finds it promoted). 404 for an unknown id, 400 for a bad value or no recognised field.
- `POST /api/admin/sources/{id}/sample` (202) queues `SAMPLE_SOURCE` with `"force":true`, key
  `sample:<id>:manual:<yyyy-mm-dd>`.
- `GET /api/admin/registrations?state=&limit=&offset=`: what users registered, newest first, with the source's current
  tier and publication status. `GET /api/admin/layers?days=28` (1 to 90): daily layer health, 28-day contribution (in
  total and by country) and the 200 coverage rows with most misses.
- `POST /api/admin/sources` with `{"openalexId":"S123","tier":"CORE"}` enqueues `ADD_SOURCE` (202).
- Routes (M6, `source_route`): `GET /api/admin/sources/{id}/routes`; `POST /api/admin/sources/{id}/routes` with
  `{"layer":"OAI"|"MAGTECH"|"EUROPEPMC","config":{…}}` stores the route PENDING (origin ADMIN) and enqueues
  `VERIFY_ROUTE` (202; 409 if the source has that route; 400 unless the config's `base`/`site` is an http(s) URL of a
  public host: the name is resolved there, the only outbound request of `web`, and every address must be public, not
  loopback, link-local or private; single-label and unresolvable names are refused too); `PATCH /api/admin/sources/{id}/routes/{routeId}`
  with `{"status":"PAUSED"|"ACTIVE"}`. ACTIVE is an admin override: it skips `VERIFY_ROUTE`, also for a PENDING or
  BROKEN route (the journal page asks before it activates such a route).
  - Task types: `SEED_ROUTES` (`{}`, the routes of `indexer/src/main/resources/routes-seed.tsv` for the journals with
    those ISSNs), `VERIFY_ROUTE` (`{"routeId":n}`: pass → VERIFIED, or ACTIVE when the route was onboarded before or
    its journal has an OpenAlex id and is not onboarded yet; fail → BROKEN with the reason), `ROUTE_BACKFILL`
    (`{"sourceId":n,"force":false}`: the journal's ACTIVE routes not yet backfilled in `BACKFILL` mode from 2000 to
    120 days ago, plus `HARVEST_CROSSREF_HISTORY` by its ISSNs unless it ran before (`source.crossref_history_at`);
    `force` reads both again; a job with a live page is never started twice). The history reads one publication year
    per offset listing (sorted by DOI creation), a year past Crossref's 10,000 offsets month by month; a month past
    them is cut and noted on the run's error.
  - The nightly rollout (`nametracker.layers.rollout-cron`, `0 30 0 * * *` UTC, behind the harvest switch) starts the
    `ONBOARDING` pass (120 days, `REPROCESS`) of up to `nametracker.layers.rollout-per-night` (20) VERIFIED routes of
    active CORE/BROAD journals (not one whose onboarding pass went DEAD since its last verification). The pass's last
    page makes the route ACTIVE; for a journal without an OpenAlex id it
    also onboards the journal (then `PROBE_EUROPEPMC`) and starts its route backfill. An OAI backfill reads the whole
    repository (no `from`), a Europe PMC backfill asks by `FIRST_PDATE`.
- `GET /api/admin/status`: task counts by type and state, work counts by state and text level, today's (UTC) `api_usage`
  rows, the last 20 harvest runs, source counts by tier, and (M5) `xrReleases` (key, alias, state, current, `keysTotal`,
  `keysLost`, `switchStarted`, `switchedAt`), `backfillRuns` (years, snapshot date, files total and done, records read and
  kept, works created) and `finderRollouts` (config hash, started, enqueued, baseline), each the newest 20. Only COUNT/GROUP BY queries, but they scan the whole task
  and work tables, which grow with the backlog: poll it every few minutes, not in a tight loop.

## Public API (`web`, no token)

`/api/works` (filters `taxon`, `name`, `code`, `taxgroup`, `subfield`, `journalType`, `tier`, `oa`, `country`, `source`,
`yearFrom`, `yearTo`, `hasActs`, `actType`, `mainSubjectOnly`, `q`, `identifier=<SCHEME>:<value>`, repeatable
`mentionedIn` (`TITLE`, `ABSTRACT`, `BODY`; any suffices; also on `/api/taxa/{colId}/works`); `limit` ≤ 100,
`offset` ≤ 10000; repeatable `facet`), `/api/works/{id}`, `/api/names?q=`, `/api/names/{id}`, `/api/taxa/{colId}/works` (`mainSubjectOnly` defaults
to true), `/api/taxa/{colId}/stats`, `/api/sources` (filter `publicationStatus`; ceased journals are listed), `/api/sources/{id}`, `/api/xr`,
`/api/stats/languages` (works per language and year, `taxgroup/code/country/yearFrom/yearTo`; read from `language_stats`, as are a taxon's and a source's `languages` from `taxon_language_stats` and a live per-source count; `REBUILD_STATS` fills them, V23), repeatable `language` filter and facet on works (`none` = undecided; the facet shows undecided as `''`), `/api/vocab/taxgroup`, `/api/vocab/discipline` (`discipline` is an alias of the `subfield` filter; a retired code resolves to
its replacement). Work and journal details carry `identifiers` (scheme, label, value, url); a work detail also
`languageSource`, `titleLang`, `abstractLang` and `titles`/`abstracts` with their `kind` (ORIGINAL, PARALLEL, MACHINE: a
machine translation is always labelled). Only
`INDEXED` works are listed; taxon filters ignore names that occur only in references. The full text of a work is served
only when its content is retained (open licence). `/api/works/{id}` sends the abstract (the Crossref JATS one as plain
paragraphs, else OpenAlex's) with `abstractMentions` (stored mentions located by verbatim, like `titleMentions`) and,
when no text is served, one body `snippets` entry per name (never title, abstract or references). The act markers
("sp. nov.") are not stored: `abstractActs` and the `acts` of text blocks and snippets find them again with
`ActPatterns` (in `finder-api`), each going to the nearest name before it with that act, as `ActTagger` does. `q`
searches the title and the same abstract, JATS first (`work.fts`, V15). Against a manual run on 18080, start Vite with a scratch config whose
proxy targets `http://localhost:18080` on another port (never edit `vite.config.ts` for it).

## Accounts and alerts (M4, `web`)

- `/api/me/**` (session; `GET /api/me`, `PATCH`, `POST /api/me/email`, `DELETE`, `/export`, `/subscriptions`,
  `/subscriptions/{id}/feed-token`, `/hits`), `/api/auth/**` (`POST /link`, `GET /config`, `POST /verify-email`,
  `POST /api/auth/logout`), `/api/unsubscribe` and ORCID under `/oauth2`, `/login`, and `/api/admin/**`. XSRF cookie only on those paths.
  Public reads carry no cache headers; `/api/me/**`, `/api/auth/**`, `/login/**` and `/api/admin/**` send
  `Cache-Control: no-store` (ADR-0006).
- Journal registration (M6b Task 9): `POST /api/sources/register` (session and XSRF) with exactly one of `issn`
  (`Issn.normalise`, a bad check digit is a 400), `doi` (≤ 300 characters) or `url` (http(s), a host, no user info,
  ≤ 2000 characters) and an optional `note` (≤ 500); body ≤ 8 KB (413); the 21st within 24 hours is a 429, and so is any
  registration once all users together made `nametracker.registration.daily-cap` (200, env
  `NAMETRACKER_REGISTRATION_DAILYCAP`) in 24 hours ("paused for today"; not locked, parallel requests may pass it by a
  few); deleting the account deletes the `REGISTERED` signals of its registrations; the same
  input again while one is identifying or identified answers that registration's id. `web` stores the row (`IDENTIFYING`), enqueues
  `IDENTIFY_SOURCE {"registrationId":n}` and answers 202 `{"id":n}`; no outbound call. `GET /api/me/registrations`
  (newest first, 100), `GET`/`DELETE /api/me/registrations/{id}` (another user's is 404; DELETE sets `WITHDRAWN` and
  deletes its `REGISTERED` signal, the source stays). `status` is read from the source's current tier: `COVERED`,
  `CANDIDATE`, `DECLINED`, `CEASED` (plus `IDENTIFYING`, `NOT_IDENTIFIED`, `WITHDRAWN`).
- Feeds: `/feeds/{taxon,source,name,taxgroup}/<id>.atom[?mentionedIn=TITLE&mentionedIn=ABSTRACT|BODY]` (public; the same part filter as `/api/works`; empty value = anywhere) and `/feeds/s/<token>.atom` (private, per follow).
- Admin: `POST /api/admin/digests/run` with `{"userId":n}` and the admin token sends that user's digest now.
- Dev sign-in: `docker compose --profile mail up -d mailpit` (SMTP 1025, UI http://localhost:8025) and
  `spring.mail.host=localhost`, `spring.mail.port=1025` in `.env`. Vite listens on 127.0.0.1:5173 (it proxies `/api`,
  `/oauth2`, `/login`, `/feeds` with `changeOrigin: false`, so the `Host` header stays; a scratch config may point at
  18080 but keeps 127.0.0.1:5173). For ORCID, open the SPA at `http://127.0.0.1:5173` and set
  `NAMETRACKER_PUBLIC_URL=http://127.0.0.1:5173`: production ORCID rejects `http://localhost` redirect URIs but accepts
  `http://127.0.0.1:5173/login/oauth2/code/orcid`. ORCID allows one public client per person; Garrulus shares the
  existing client registered for Blixa for now.

## Evaluation (`eval`)

Run the CLI after `mvn -B -DskipTests install` with `mvn -q -pl eval exec:java -Dexec.args="<command> ..."`; `--help`
on any command lists its options.

- **Prerequisites:**
  - A running native gnfinder for the gnfinder and pipeline finders (`GNFINDER_URL`, default
    `http://localhost:8778`; see "gnfinder").
  - The COL dictionary for pipeline, mode B and tune: `--dictionary <file>` or `FINDER_DICTIONARY`.
- **Dictionary** (not committed): `build-dictionary --xr https://download.checklistbank.org/col/xr_latest_coldp.zip
  --out eval/work` writes `eval/work/dictionary-<releaseKey>.bin` (format v2, about 46 MB).
  Rebuild it for a new XR and after a format change; an old format fails with a message saying so.
- **Corpora** (committed): `eval/corpus/pensoft` is the silver set (300 CC-BY articles, 100 dev / 200 test, rebuilt by
  `rebuild-pensoft`). `eval/corpus/curated` holds the manifest and the hand-fixed gold `gold.jsonl.gz` (imported
  2026-11-18 by `ls-import --out eval/corpus/curated/gold.jsonl.gz` from the Label Studio project "Garrulus curated": 111
  documents, 860 names, 109 acts, 21 main-subject names).
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
  - The curated gold also has authorships: an `Authorship` region belongs to the `Name` right before it (spaces,
    commas, `*`, `+`, `\` and linked `Act` regions may lie between, "Bacillidae* subcl. n. Cavalier-Smith"). Reports on the
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

- `finder-api`: `NameFinder` SPI, the document model and its JSON contract `finder-api.yaml`, and the act marker
  table `ActPatterns` (also used by `web`).
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
  index pipeline, spool, XR pin, placement, salience, categorisation, OpenAPI; 0008: work identity, layered harvest, routes, coverage;
  0009: journal registry and its fold, discovery, registration, the SSRF design, admins and notices, disciplines, sampling, ceased journals;
  0010: identifier schemes and lookups, language decision, English and machine titles, abstract top-ups, the KU route, mentioned-in).

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
