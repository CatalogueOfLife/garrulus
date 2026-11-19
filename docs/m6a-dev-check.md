# M6a dev check (layered harvest)

The dev check of M6a (ADR-0008) on the dev-run database of `docs/m2-dev-run.md`. Steps 1 and the baseline are done;
the two-night numbers (step 2) and the OpenAlex block (step 3) need real nights and are filled in later.

**Where steps 2 and 3 run (2026-11-19).** The dev run lives on a travelling laptop that is often offline or shut down,
and nothing restarts it (no launchd locally). Missed crons and the laptop's own outages would read as layer failures,
so its nights are a best-effort smoke run only. Steps 2 and 3 count on the GBIF VM, the first always-on installation:
run them there after the deployment, in the same order (two normal nights, then two with OpenAlex blocked), with the
queries and commands below (paths relative to the VM's run directory).

## Step 1: deploy (2026-11-19, 14:58 UTC)

- Jars from `main` at `10e2e85` (`mvn -B -DskipTests install`), copied to `data/run/`; the previous indexer jar is kept
  as `data/run/indexer-2026-11-19-pre-m6a.jar`, its log as `data/run/indexer-13.log`.
- Indexer restarted as before (`-Xmx6g`, port 18081, `NAMETRACKER_HARVEST_ENABLED=true`, `FINDER_DICTIONARY` 316691);
  web restarted on 18080 (it had been stopped; its jar was stale).
- Flyway applied V16 (layered harvest), V17 (Crossref history) and V18 (layer contribution) in 22.5 s, the V16
  back-fill included. Started in 24.5 s.
- Back-fill: 103,796 `DOI`, 103,623 `OPENALEX`, 23,442 `PMCID` identifiers; 103,623 `OPENALEX` and 261 `CROSSREF`
  sightings, for 103,884 works (88 without a DOI). No (scheme, value) occurs twice.
- `SEED_ROUTES`: 19 OAI routes (of the 78 seed lines, only these journals are in the dev registry; Biodiversity Science
  is not, so no Magtech route). All 19 passed `VERIFY_ROUTE` within a minute, none `BROKEN`. All their journals are
  onboarded and have an OpenAlex id, so they stay `VERIFIED` until the nightly rollout (00:30 UTC) starts their
  onboarding pass (at most 20 a night, so all 19 the first night).

**Redeploy 16:49 UTC** with the final-review fixes (jars from `02b62b1`, same settings; no migration). The nights
below therefore run the fixed code: a task claim that never exceeds its limit, coverage on LIVE-origin works only, OAI
continuations that keep their queue place, routes that record errors and turn BROKEN when their task dies, and free-only
re-resolves after a late abstract or a merge. Previous logs: `data/run/indexer-14.log`, `data/run/web-1.log`.

Routes: Acta Ichthyologica et Piscatoria, Arthropod Systematics & Phylogeny, Biodiversity Data Journal, Check List,
Deutsche Entomologische Zeitschrift, European Journal of Taxonomy, Evolutionary Systematics, Herpetozoa, Journal of
Hymenoptera Research, Journal of Orthoptera Research, Journal of Threatened Taxa, MycoKeys, PhytoKeys, Phytotaxa,
Vertebrate Zoology, ZooKeys, Zoologia (Curitiba), Zoosystematics and Evolution, Zootaxa.

**Baseline duplicates.** 136 groups of works share source, year and normalised title (titles over 20 characters). All
have distinct DOIs and none contains a work without a DOI: generic titles ("Introduction to the Symposium", conference
abstracts) and publishers' double DOIs, which the title match never joins (different DOIs never merge). The check
after two nights: no such group gains a work without a DOI, and identifier duplicates stay at 0.

**Seen during the deploy.** Before the restart the indexer log showed CLB match calls failing with I/O errors on a weak
connection while the probe passed, so those names were placed `NONE` (ADR-0005, ADR-0008 "known limits"). A
`REPROCESS_NAMES` with `"force":false` for the affected works re-places them once the connection is good.

## Step 2: two nights (pending; on the GBIF VM)

Record per layer, from `harvest_run`, `work_sighting`, `source_route`, `layer_health` and the log:

```
data/run/psql.sh -c "select adapter, state, count(*), sum(works_seen) seen, sum(works_new) new from harvest_run where started > now() - interval '2 days' group by 1,2 order by 1,2"
data/run/psql.sh -c "select layer, count(*), count(*) filter (where first_seen > now() - interval '2 days') new from work_sighting group by 1"
data/run/psql.sh -c "select layer, status, count(*) from source_route group by 1,2 order by 1,2"
data/run/psql.sh -c "select * from layer_health order by day desc, layer limit 24"
data/run/psql.sh -c "select type, state, count(*) from task where type in ('MERGE_WORKS','VERIFY_ROUTE','HARVEST_OAI','COVERAGE_CHECK') group by 1,2"
# pre-2000 skips are not logged per record: works_seen minus works_new per run covers them with the known works
# duplicates: both must stay as in the baseline
data/run/psql.sh -c "select count(*) from (select scheme, value from work_identifier group by 1,2 having count(*)>1) x"
data/run/psql.sh -c "select count(*) from (select 1 from work where title is not null and length(title) > 20 group by source_id, year, lower(regexp_replace(title,'[^[:alnum:]]','','g')) having count(*) > 1 and bool_or(doi is null)) x"
```

Expected: all 19 routes `ACTIVE` after the first night's onboarding passes, OAI runs `DONE` each night, works first
found by OAI for journals OpenAlex indexes late, `MERGE_WORKS` rare and never `DEAD`, both duplicate counts 0.

## Step 3: two nights with OpenAlex blocked (pending; on the GBIF VM, after step 2)

Blocking OpenAlex breaks the M2 success criterion (7 consecutive `OPENALEX_LIVE` runs), so it starts after the M2 check
on 22 Nov, for the nights of 22/23 and 23/24 Nov:

```
kill $(cat data/run/indexer.pid)
NAMETRACKER_INDEXER_PORT=18081 NAMETRACKER_HARVEST_ENABLED=true \
  FINDER_DICTIONARY=$PWD/eval/work/dictionary-316691.bin \
  NAMETRACKER_OPENALEX_BASE_URL=http://127.0.0.1:9 NAMETRACKER_OPENALEX_CONTENT_BASE_URL=http://127.0.0.1:9 \
  nohup java -Xmx6g -jar data/run/indexer.jar > data/run/indexer.log 2>&1 &
echo $! > data/run/indexer.pid
```

Check each morning: new works still arrive through Crossref and OAI (`work_sighting` without an `OPENALEX` row),
`layer_health` shows `OPENALEX` `DEGRADED` after the first night and `FAILING` after the second (a WARN in the log).
Then restart without the two variables and check that `OPENALEX` returns to `OK` and that works other layers brought
get no new `ARTICLE` hits when OpenAlex sees them again.

## Results

To be filled in after steps 2 and 3.
