# M2 dev run

The M2 finish line (design roadmap): "A week of live harvesting runs unattended into the dev DB". This file records how
the run was started, how to stop it, the first-check numbers and the success criterion.

## Start

- **Started:** 2026-11-15 10:17 UTC (indexer up 10:17:16, `SEED_SOURCES` enqueued 10:17:29, first manual LIVE and
  Crossref harvests 10:22:59).
- **Restarted:**
  - 10:25:36 UTC, after the HTTP/1.1 fix (commit `9e133bf`, see below). From then on both apps run from copies of
    their jars in `data/run/`, so a later `mvn install` cannot pull a class out from under a running JVM.
  - 11:08:26 UTC, after the round 2 fixes (`acd2c63`, `74c176f`, `07d54cb`). Both apps now listen on 127.0.0.1 only.
  - 11:54:03 UTC, the indexer only, after the final-review fixes (round 3, below).
  - 16:14:02 UTC, the indexer only, with an `OPENALEX_API_KEY` in `.env` (round 4, below).
- **Keyless until 16:14 UTC:** no `OPENALEX_API_KEY` and no `CROSSREF_MAILTO`. OpenAlex runs on the keyless $0.10/day per IP, group-by
  sees one page (200 groups), page paging stops at 10,000 results and the TEI route does not apply (ADR-0004). Crossref
  runs in the public pool.

## Commands

All from the repo root with JDK 25 (`sdk env`). The admin token lives only in the git-ignored `.env` as
`ADMIN_TOKEN=...`; both apps read it from there, and the shell commands below load it as `$ADMIN_TOKEN`.

```
docker compose up -d db
mvn -B -DskipTests install
mkdir -p data/run
cp indexer/target/nametracker-indexer-0.1.0-SNAPSHOT.jar data/run/indexer.jar
cp web/target/nametracker-web-0.1.0-SNAPSHOT.jar data/run/web.jar

NAMETRACKER_INDEXER_PORT=18081 NAMETRACKER_HARVEST_ENABLED=true \
  FINDER_DICTIONARY=$PWD/eval/work/dictionary-316691.bin \
  nohup java -jar data/run/indexer.jar > data/run/indexer.log 2>&1 &
echo $! > data/run/indexer.pid

SERVER_PORT=18080 nohup java -jar data/run/web.jar > data/run/web.log 2>&1 &
echo $! > data/run/web.pid

set -a; . ./.env; set +a
curl -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"SEED_SOURCES","payload":{},"priority":"LIVE","dedupKey":"seed-sources:manual"}'
# after the seed, so the first day does not wait for the 02:00 / 03:00 UTC crons
curl -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"HARVEST_OPENALEX","payload":{"window":"LIVE"},"priority":"LIVE","dedupKey":"harvest:openalex:LIVE:manual"}'
curl -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"HARVEST_CROSSREF","payload":{"date":"2026-11-14"},"priority":"LIVE","dedupKey":"harvest:crossref:manual"}'

curl -s localhost:18080/api/admin/status -H "X-Admin-Token: $ADMIN_TOKEN"
```

`data/run/` (git-ignored) also holds `psql.sh` (psql in the db container) and `check.sh`, which prints every number of
the checks below from the database.

## Stop

```
kill $(cat data/run/indexer.pid) $(cat data/run/web.pid)
```

SIGTERM shuts both down cleanly; the indexer gives running tasks 30 s, and whatever is still RUNNING is requeued at
the next start. Ports 8080/8081 are not used.

## Seed report (10:18:41 UTC)

`Seeded sources: 120 core, 89 broad, 104 candidate, 41 skipped (truncated); 209 awaiting onboarding`. Truncated as
expected without a key: each group-by returned only its first 200 journals. The seed cost $0.005 of OpenAlex (363
calls, most of them free singleton gets).

- ZooKeys, Zootaxa, Phytotaxa and MycoKeys are all CORE (2,098, 990, 506 and 199 articles a year), so the truncation
  did not drop them.
- CORE, largest first: Zootaxa, Phytotaxa, ZooKeys, Biodiversity Data Journal, PhytoKeys, MycoKeys, Ecologica
  Montenegrina, Zoological Journal of the Linnean Society, European Journal of Taxonomy, Molecular Phylogenetics and
  Evolution. A random ten: Herpetozoa, Acta entomologica Musei Nationalis Pragae, Journal of the Entomological Research
  Society, The Pan-Pacific Entomologist, Russian Journal of Herpetology, Zoosystematica Rossica, Ichthyology &
  Herpetology, Folia Entomologica Hungarica and two of the above. All plausible.
- BROAD, largest first: International Journal of Advanced Biochemistry Research, Animals, Plants, Frontiers in Plant
  Science, BMC Plant Biology, Ecology and Evolution, Journal of Animal Science, Poultry Science, Horticulturae, Plant
  Science Today. Random: Theoretical and Applied Genetics, Marine Ecology Progress Series, Plant Cell & Environment,
  Tropical Animal Health and Production, Plant Cell Reports, Journal of Plant Pathology, Applied Animal Behaviour
  Science, Biodiversitas, Plant Stress, Smart Agricultural Technology. Plausible for the subfield rule; several are
  agronomy or animal husbandry rather than biodiversity journals, which is what BROAD admits.

## First check (11:01 UTC, 44 minutes after the seed)

- **Sources:** CORE 120 (118 onboarded), BROAD 89 (55 onboarded), CANDIDATE 104. The remaining 36 onboardings wait on
  their page-2 continuation at BACKFILL priority behind the content backlog.
- **Works** (origin / state / text level):

  | origin | state | text level | works |
  |---|---|---|---|
  | ONBOARDING | DISCOVERED | — | 14,979 |
  | ONBOARDING | CONTENT_RESOLVED | FULLTEXT | 162 |
  | ONBOARDING | CONTENT_RESOLVED | ABSTRACT_ONLY | 73 |
  | LIVE | DISCOVERED | — | 408 |
  | LIVE | CONTENT_RESOLVED | FULLTEXT | 197 |
  | LIVE | CONTENT_RESOLVED | ABSTRACT_ONLY | 2,582 |

- **Content rows per route** (retained / not retained): ABSTRACT 524 / 2,490, PENSOFT 219 / 7, EUROPEPMC 91 / 25,
  PMC 3 / 14, OPENALEX_TEI 0 (keyless). 3,373 rows.
- **Tasks:** SEED_SOURCES 1 done; ONBOARD_SOURCE 209 done, 36 queued; HARVEST_OPENALEX 42 done; HARVEST_CROSSREF 16
  done, 1 queued; RESOLVE_CONTENT 3,014 done, 8 running, 18,034 queued (2,666 of them are re-checks due in the coming
  days).
- **DEAD tasks:** none, and no task has a `last_error`.
- **OpenAlex spend today:** $0.0261 in 3,762 calls, $0.0739 left of the keyless $0.10 (the configured cap is $1.00).
  No budget deferral has happened yet.
- **Harvest runs:**
  - `OPENALEX_LIVE` 2026-07-18..2026-11-15: DONE at 10:50 UTC, 42 calls, 8,014 works seen, 0 new (the same 120 days
    the onboarding had just imported). No batch hit the 10,000 cap.
  - `CROSSREF` 2026-11-14: still RUNNING, 16 calls, 3,498 works seen, 3,187 new.
- **Blobs:** `data/blobs` 20 MB in 1,669 files.
- **Logged route failures** (the work falls through to the next route; nothing goes DEAD): Europe PMC answers HTTP
  500 for `fullTextXML` of a PMCID that OpenAlex gave but that is not open access (250 so far), and five Pensoft
  journal hosts built from the DOI's journal slug do not resolve in DNS (`asp.pensoft.net`, `vz.pensoft.net`).

## Fixed during the run

- `9e133bf` fix(indexer): speak HTTP/1.1 to external services. Europe PMC (`www.ebi.ac.uk`) ended every HTTP/2 stream
  of the JDK client with an EOF, so the Europe PMC and PMC routes never succeeded (48 failures in the first four
  minutes). Test: `HarvestConfigTest.httpClientSpeaksHttp11`. The works resolved before the fix ended at
  ABSTRACT_ONLY and get their full text at their scheduled re-checks.

## Round 2 (rulings M2-K, M2-L, M2-M; restart 11:08:26 UTC)

- `acd2c63` fix(indexer): a work that Crossref creates (directly or through OpenAlex) and that was published more than
  120 days ago comes in as `BACKFILL`, with a BACKFILL content task. Recent and undated works stay `LIVE`. Test:
  `CrossrefHarvestHandlerIT.oldNewWorksAreBackfillRecentAndUndatedStayLive`.
- `74c176f` fix(indexer): Europe PMC fetches `fullTextXML` only when its (cached) search says `isOpenAccess = Y`,
  also for a PMCID that OpenAlex supplied. A work with only a PMCID is searched as `PMCID:<id>`. Test:
  `EuropePmcRouteTest.knownPmcidThatIsNotOpenAccessFetchesNothing`.
- `07d54cb` fix(web): binds to `127.0.0.1` by default (`NAMETRACKER_WEB_ADDRESS`). Test: `WebBindAddressTest`.
- **Old LIVE works from the first run (one-off note):** before the fix, the first Crossref run (2026-11-14) created
  3,187 works as `LIVE`. 3,144 of them were published more than 120 days ago, 33 have no date and 10 are recent. They
  stay as they are: `origin` never changes, and the design's hit rule ("published within 60 days") keeps them from
  notifying.
- **Check at 11:18 UTC, ten minutes after the restart:**
  - Since the restart, the rest of the Crossref run created 808 old works as `BACKFILL`, and 10 works as `LIVE` (3
    recent, 7 undated). No new `LIVE` work is older than 120 days. The `CROSSREF` run for 2026-11-14 ended `DONE`: 20
    calls, 4,421 works seen, 4,005 new.
  - Europe PMC rows keep coming: 248 `EUROPEPMC` and 74 `PMC` content rows since the restart. No HTTP 500.
  - The content rate went from 521 tasks in the 10 minutes before the restart (0.9/s) to 1,031 in the 10 minutes after
    (1.7/s).
  - DEAD tasks: 0. OpenAlex: $0.0265 spent in 4,584 calls. Onboarded: CORE 119 of 120, BROAD 55 of 89; the rest are
    running now that the LIVE queue is empty. Blobs: 52 MB.

## Round 3 (final-review fixes; restart 11:54:03 UTC)

The indexer only (pid 56563, same jar copy, port and env as above); no web file changed, so web keeps running. No
migration.

- `c619118` the Pensoft route applies only to a `[a-z0-9-]+` slug and a numeric id, and stays on `*.pensoft.net`.
- `640da35` the indexer ITs are hermetic: unroutable base URLs, no key or mailto, no `.env` (`nametracker.dotenv`).
- `94692ec` a spent OpenAlex budget requeues any task at `retryAt` without using an attempt (dispatcher level).
- `70b13f2` an outage defers a harvest or onboarding page by 30 minutes (`NAMETRACKER_HARVEST_OUTAGE_DELAY`), the run
  `DEFERRED`; other failures mark the run `FAILED`.
- `35a1943` Crossref catches up every missed day (at most 14 days back) instead of skipping a day while a chain runs.
  The next fire (2026-11-16 03:00 UTC) enqueues 2026-11-15, the day after the last `CROSSREF` run.
- `537eddb` content re-checks only for works that are not `BACKFILL` and published within 90 days (or undated).
- `58fb3d9` an unknown host fails at once, without 10 s of retries.
- **Check at 11:59 UTC, five minutes after the restart:** 1,044 tasks finished, all `DONE` (3.5/s); 0 `DEAD` and no
  task with a `last_error`; `ONBOARD_SOURCE` 226 done, 31 queued; `RESOLVE_CONTENT` 10,330 done, 20,798 queued.

## Round 4 (OpenAlex key; restart 16:14:02 UTC)

The indexer only (same jar copy, port and env), now with a free `OPENALEX_API_KEY` in `.env`: $1/day allowance
(`x-ratelimit-limit-usd: 1`), full paging and route 4. No code change.

- **The onboarding backlog was done by then.** All 18,991 queued tasks are content re-checks due on 2026-11-22, so
  route 4 had nothing to run and was smoke-tested by hand.
- **Stale remaining amount (one-off fix by hand).** Today's `api_usage` row still held the keyless `remaining_usd`
  (0.0665). It is below the content reserve (0.30), so `OpenAlexBudget.check(CONTENT)` would have deferred every route-4
  attempt to 00:05 UTC. I set it to `NULL`. The next OpenAlex response wrote the key's value (0.9699). A key added in
  the middle of a day therefore needs this step, or route 4 waits until the next UTC day (carried into M3).
- **Smoke test 16:15 UTC:** `RESOLVE_CONTENT` by hand (`dedupKey content:<id>:smoke-tei`) for three CORE works with
  `openalex_has_tei` and only an abstract, all `DONE` on the first attempt and now `FULLTEXT`:
  - 1677 and 1678 (Check List, CC-BY): `OPENALEX_TEI` rows `retained`, raw TEI and document blobs kept.
  - 428 (Phytotaxa, no licence): `OPENALEX_TEI` row not retained, no blobs, only the hashes.
  - Spend: 3 content calls, $0.03 by the response headers ($0.01 each, as priced). The stored URLs carry
    `api_key=***`.
  - Document 1677: 140 blocks (1 title, 1 abstract, 17 headings, 27 paragraphs, 7 captions, 47 footnotes, 40
    references), no style spans (GROBID has no italics, ADR-0002). GROBID tokenisation shows in the text: affiliations
    as footnotes, a space before commas (`Hohenheim , Institut`) and none after some full stops (`tray.In`). Worth a
    `TeiConverter` clean-up before mode B is tuned again.

## Open points

- **Throughput.** Even at 1.7/s, the onboarding backlog (about 19,000 content tasks) takes a few hours. Pensoft's 1 s
  spacing is the main bound, and BACKFILL work waits whenever the LIVE queue has work.

## Success criterion

The week-long check is done when `harvest_run` shows **7 consecutive daily** `OPENALEX_LIVE` and `CROSSREF` runs as
`DONE` (or `DEFERRED` and then `DONE`), and the number of `DEAD` tasks does not grow. `GET /api/admin/status` shows
the last 20 runs; `data/run/check.sh` shows them all.
