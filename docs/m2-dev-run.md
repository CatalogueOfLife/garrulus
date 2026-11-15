# M2 dev run

The M2 finish line (design roadmap): "A week of live harvesting runs unattended into the dev DB". This file records how
the run was started, how to stop it, the first-check numbers and the success criterion.

## Start

- **Started:** 2026-11-15 10:17 UTC (indexer up 10:17:16, `SEED_SOURCES` enqueued 10:17:29, first manual LIVE and
  Crossref harvests 10:22:59).
- **Restarted:** the indexer and web at 10:25:36 UTC, after the HTTP/1.1 fix (commit `9e133bf`, see below). Both now
  run from copies of their jars in `data/run/`, so a later `mvn install` cannot pull a class from under a running JVM.
- **Keyless:** no `OPENALEX_API_KEY` and no `CROSSREF_MAILTO`. OpenAlex runs on the keyless $0.10/day per IP, group-by
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
  journal hosts taken from the source homepage do not resolve in DNS (`asp.pensoft.net`, `vz.pensoft.net`).

## Fixed during the run

- `9e133bf` fix(indexer): speak HTTP/1.1 to external services. Europe PMC (`www.ebi.ac.uk`) ended every HTTP/2 stream
  of the JDK client with an EOF, so the Europe PMC and PMC routes never succeeded (48 failures in the first four
  minutes). Test: `HarvestConfigTest.httpClientSpeaksHttp11`. The works resolved before the fix ended at
  ABSTRACT_ONLY and get their full text at their scheduled re-checks.

## Open points

- **Crossref brings in the back catalogue as LIVE.** The index-date filter lists every DOI whose metadata changed that
  day. Of the first 2,060 Crossref-created works, 2,028 were published more than 120 days ago (back to 1998). They get
  origin `LIVE`, so they would notify as news in M3, and each takes a content task. Needs a decision (for example:
  create only works published inside the LIVE window, or give the others a non-notifying origin).
- **Throughput.** Content resolution runs at roughly 1.5 works/s, bounded by the 1 s Pensoft spacing and by 10 s of
  retries per Europe PMC 500. The first day's backlog takes several hours, and BACKFILL work (the last 36 onboardings)
  waits until the LIVE queue is empty.

## Success criterion

The week-long check is done when `harvest_run` shows **7 consecutive daily** `OPENALEX_LIVE` and `CROSSREF` runs as
`DONE` (or `DEFERRED` and then `DONE`), and the number of `DEAD` tasks does not grow. `GET /api/admin/status` shows
the last 20 runs; `data/run/check.sh` shows them all.
