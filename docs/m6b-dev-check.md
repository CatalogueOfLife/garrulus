# M6b dev check (registry, discovery, registration, admins)

The dev check of M6b (ADR-0009). Two parts: a local run on the dev-run database of `docs/m2-dev-run.md` (pending,
below) and the checklist for the GBIF VM, the first always-on installation, where everything that needs real nights
runs. The dev-run laptop is often offline, so its nights are not counted (same reasoning as `docs/m6a-dev-check.md`).
Paths are relative to the run directory; `data/run/psql.sh -c "..."` is the psql helper of the dev run.

## Local run (2026-11-20, done except the browser steps)

**Results.** Deployed at 10:08 UTC from `main` (indexer/web jars of `e1363b8`; frontend `b5dc021` served by Vite on
127.0.0.1:5173). `mvn -B verify` and the frontend tests were green in the fix wave.
- Flyway applied V19 and V20 on the dev DB (about 104k works, 313 sources before) in under 0.2 s.
- Web started with `NAMETRACKER_ADMIN_ORCIDS` = the dev DB's one ORCID account, Mailpit on 1025/8025, the public URL
  127.0.0.1:5173. `REFRESH_ISSNL` not run (switch off).
- `DISCOVER_SOURCES` by hand for DOAJ, PKP_BEACON, PLAZI and BLR (COL references skipped: the XR zip over a travel
  connection): 15 tasks incl. continuations, all DONE.
  - New candidates: DOAJ 375, PKP Beacon 219, Plazi 59, BLR 0 (`layer_unmatched` is empty on the dev run; BLR runs
    nightly and the laptop's nights do not count). Signals: DOAJ 406, PKP Beacon 265, Plazi 119 (one per source).
  - Candidate countries: GB 88, none 76, BR 51, US 51, CH 48, ID 40, NL 30, **IN 24**, DE 21 … **CN 14**.
  - Routes: 265 OAI routes from Beacon hints stay PENDING (the route gate: candidates are not verified before
    promotion); the 18 ACTIVE and 1 BROKEN routes are M6a's seed.
  - 653 `IDENTIFY_SOURCE` queued at BACKFILL behind the dev run's backlog.
- `SAMPLE_SOURCE` (admin POST, forced) for three DOAJ/Plazi candidates: one 20 Crossref articles, all with abstracts,
  2.4 names per article, acts 0, groups Chordates 0.28 / Protists 0.15 / Crustacea 0.15 …, score 0.16; one 2 articles
  (below 5: counted as no sample), score 0.07 from signals; one with no reachable route (empty row), score 0.32 from
  signals. Nothing was written to `work`.
- An `admin_notice` inserted by hand was claimed by the 5-minute mail job and ended `SUPPRESSED`: the admin account
  (ORCID sign-in) has no e-mail address, and admin mail goes only to verified addresses of admin accounts (by design).
  To see it reach Mailpit, Markus adds and verifies an e-mail on his account first (B-steps below).

**Still Markus'**: the browser steps B1 to B5, plus adding a verified e-mail to the ORCID account and re-inserting a
notice to see the admin mail arrive in Mailpit (http://127.0.0.1:8025).

### The procedure

Runs once, after the final review's fix wave, so the dev run gets the final code. What a laptop can show. Steps 1 to 6
are for the controller or Markus at the terminal; the browser steps (B1 to B5) are **Markus' to do**.

1. Build and test: `mvn -B verify`, then `cd frontend && npm test && npm run build`, both green.
2. Install and start the dev-run apps from `main` (jars into `data/run/`, the previous ones kept under a dated name),
   with `NAMETRACKER_ADMIN_ORCIDS` set to Markus' iD, `ADMIN_TOKEN` set, Mailpit
   (`docker compose --profile mail up -d mailpit`, `spring.mail.host=localhost`, `spring.mail.port=1025` in `.env`),
   `NAMETRACKER_PUBLIC_URL=http://127.0.0.1:5173` and the usual ports (`SERVER_PORT=18080`,
   `NAMETRACKER_INDEXER_PORT=18081`). **`NAMETRACKER_DISCOVERY_ISSNL_ENABLED` stays false** (no `REFRESH_ISSNL`) until
   Markus confirms that the ISSN IC download URL may be used.
3. Flyway applied V19 and V20: `data/run/psql.sh -c "select version, success from flyway_schema_history order by installed_rank desc limit 2"`.
   V20 back-fills stored ISSNs into the upper-case hyphenated form; it changes only rows that differ.
3a. Fix-wave checks on the populated dev DB (the final review's recommendations):

   ```
   # ISSN lookups use the indexes (expect Bitmap Index Scans on source_issn_l_idx and source_issns_idx)
   data/run/psql.sh -c "explain analyze select * from source where issn_l = '1175-5326' or issns @> array['1175-5326']::text[]"
   # sampling and dormancy queries on real numbers (M8, deferred: add an index on work (source_id, publication_date)
   # and a max-then-window two-step only if dormancyBase is slow); copy the SQL of dueForSampling and dormancyBase
   # from their mapper XML and run it with explain analyze
   ```

   After the first Beacon load (step 4), the ISSNs typed into more than one OJS row (placeholders, shared cells) are
   signals only on known journals since the fix wave, but list them:
   `data/run/psql.sh -c "select issn, count(*) from pkp_beacon, unnest(issns) issn group by 1 having count(*) > 1 order by 2 desc limit 30"`.
   And an `ADD_SOURCE` of a journal that is already a candidate (pick one with an OpenAlex journal whose ISSNs it holds)
   ends with one source, the candidate adopted and promoted.
4. Discovery by hand (the master switch can stay off; every task is `BACKFILL` priority, so a busy queue delays them):

   ```
   for o in DOAJ PKP_BEACON PLAZI BLR; do
     curl -s -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
       -d "{\"type\":\"DISCOVER_SOURCES\",\"payload\":{\"origin\":\"$o\"},\"priority\":\"BACKFILL\",\"dedupKey\":\"discover:$o:manual\"}"
   done
   # COL references only if the XR zip download (about 1 GB) is acceptable on the network:
   curl -s -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
     -d '{"type":"DISCOVER_SOURCES","payload":{"origin":"COL_REFERENCES"},"priority":"BACKFILL","dedupKey":"discover:COL_REFERENCES:manual"}'
   ```

   Wait until `select type, state, count(*) from task where type in ('DISCOVER_SOURCES','IDENTIFY_SOURCE') group by 1,2`
   shows nothing QUEUED or RUNNING (titles of Plazi and COL continue as phase 2 tasks).
5. A few samples: pick three candidates with the highest score
   (`select id, title, candidate_score from source where tier='CANDIDATE' order by candidate_score desc nulls last limit 3`),
   then `curl -s -X POST localhost:18080/api/admin/sources/<id>/sample -H "X-Admin-Token: $ADMIN_TOKEN"` for each, and
   read `source_sample`.
6. A notice by hand reaches Mailpit (needs an account with a verified address whose ORCID iD is in the admin list):

   ```
   data/run/psql.sh -c "insert into admin_notice (kind, dedup_key, subject, body) values ('LAYER_FAILING','manual:test:1','Garrulus test notice','Hand-made notice from the M6b dev check.')"
   ```

   Within five minutes (`NAMETRACKER_ADMIN_MAIL_CRON`) the mail is in http://localhost:8025 and the row has `mailed_at`
   and `outcome`.

Browser steps (**Markus**, SPA at `http://127.0.0.1:5173` against the manual run, ORCID sign-in as in CLAUDE.md "Dev
sign-in"):

- B1. ORCID sign-in with the listed iD shows the admin pages (Journals, Journal, Layers, Registrations, Status).
- B2. A signed-in non-admin account (email link) gets 403 on `/api/admin/sources` and no admin pages.
- B3. A registration by ISSN through the SPA (a journal not yet in the registry): it identifies and shows a status.
- B4. A registration by article DOI through the SPA.
- B5. One accept (promotion to CORE or BROAD) from the journal page; then the user's registration shows `COVERED`
  and the registration mail arrives.

Counts to record here after the run (fill in):

```
data/run/psql.sh -c "select origin, count(*) from source where tier = 'CANDIDATE' group by 1 order by 1"
data/run/psql.sh -c "select origin, count(*) from source_signal group by 1 order by 1"
data/run/psql.sh -c "select count(*), count(*) filter (where articles >= 5) from source_sample"
data/run/psql.sh -c "select kind, outcome, count(*) from admin_notice group by 1,2"
data/run/psql.sh -c "select state, outcome, reason, count(*) from source_registration group by 1,2,3"
```

| Item | Count |
|---|---|
| Candidates per origin (DOAJ, PKP_BEACON, PLAZI, COL_REFERENCES, BLR, REGISTERED) | |
| Signals per origin | |
| Samples (all, with at least 5 articles) | |
| Notices (sent, suppressed) | |
| Registrations (state, outcome) | |

## GBIF VM checklist

Run after the M6b deployment on the always-on installation, in this order. Deployment checks first:

- Flyway applied V19 and V20 (`select version, success from flyway_schema_history order by installed_rank desc limit
  2`); the GBIF database starts empty at V18, so both are quick.
- Before the first Beacon run: `select issn, count(*) from pkp_beacon, unnest(issns) issn group by 1 having count(*) > 1
  order by 2 desc` (after the load) shows the ISSN cells shared by several OJS rows; since the fix wave they only add
  signals to a known journal.
- `NAMETRACKER_ADMIN_ORCIDS`, `ADMIN_TOKEN`, `spring.mail.host`, `NAMETRACKER_PUBLIC_URL` (the public https URL) and
  `NAMETRACKER_COOKIE_SECURE=true` are set; `nametracker.http.trusted-hosts` lists `host:port` of local services only
  (CLB match bundle, if any), nothing else.
- `NAMETRACKER_DISCOVERY_ISSNL_ENABLED` stays **false** until Markus confirms the ISSN IC URL may be used; then run
  `REFRESH_ISSNL` once by hand:
  `curl -X POST .../api/admin/tasks ... -d '{"type":"REFRESH_ISSNL","payload":{},"priority":"BACKFILL","dedupKey":"issnl:manual"}'`
  and check `select count(*) from issn_link` (about 2 million rows).
- The COL references phase streams the XR zip: check the indexer `-Xmx` against the title grouping (up to 2000 groups
  plus the ISSN groups) before the first monthly run.

### 1. M6a dev check, steps 2 and 3

`docs/m6a-dev-check.md` steps 2 (two normal nights) and 3 (two nights with OpenAlex blocked) also run here, with the
queries and commands of that file. Item 3 below is their M6b continuation (the mails).

### 2. First monthly discovery and its candidate counts

The scheduler runs at `0 15 1 1 * *` UTC; the first run can also be started by hand (see local run step 4) per origin.
After the run (and its phase 2 title tasks) is DONE:

```
data/run/psql.sh -c "select type, state, count(*) from task where type in ('DISCOVER_SOURCES','IDENTIFY_SOURCE') group by 1,2 order by 1,2"
data/run/psql.sh -c "select origin, count(*) from source where tier = 'CANDIDATE' group by 1 order by 2 desc"
data/run/psql.sh -c "select country, count(*) from source where tier = 'CANDIDATE' group by 1 order by 2 desc limit 25"
data/run/psql.sh -c "select country, origin, count(*) from source where tier = 'CANDIDATE' and country in ('CN','IN') group by 1,2 order by 1,3 desc"
data/run/psql.sh -c "select origin, count(*) from source_signal group by 1 order by 1"
data/run/psql.sh -c "select origin, count(*) filter (where resolved_at is null) open, count(*) filter (where resolved_at is not null and resolved_issns is null) unresolved from discovery_title group by 1"
```

Record the counts per origin and per country, with **China (CN) and India (IN)** called out (the spec's reach goal).
Expected: DOAJ about 600, no `DEAD` discovery task, the title tasks resolved except ambiguous titles, every candidate
identified (`IDENTIFY_SOURCE` DONE). Duplicates must not exist:

```
data/run/psql.sh -c "select issn, count(*) from (select id, unnest(issns) issn from source) x group by 1 having count(*) > 1"
data/run/psql.sh -c "select openalex_id, count(*) from source where openalex_id is not null group by 1 having count(*) > 1"
```

Both return no rows.

### 3. OpenAlex block: the FAILING and the recovery mail

After the M6a step 3 block (two nights, OpenAlex unreachable) the coverage check turns `OPENALEX` FAILING and writes
the notice; when OpenAlex is reachable again and a run is OK, the recovery notice follows. **Exactly one mail of each.**

```
data/run/psql.sh -c "select kind, dedup_key, created, mailed_at, outcome from admin_notice order by id"
data/run/psql.sh -c "select layer, day, state, failed_runs, yield from layer_health where layer = 'OPENALEX' order by day desc limit 10"
```

Expected: one `LAYER_FAILING` row (`outcome` SENT), no second one while it stays failing within 7 days, one
`LAYER_RECOVERED` after the recovery, and the two mails in the admins' inboxes. Link in the mail points to the
`<public URL>/admin/layers` page.

### 4. A week without a FAILING mail for weekday-quiet or Magtech layers

Seven consecutive nights with the weekday zero-yield rule: a layer that is quiet on weekends (Magtech, small OAI
routes) must not turn FAILING.

```
data/run/psql.sh -c "select layer, state, count(*) from layer_health where day > current_date - 8 group by 1,2 order by 1,2"
data/run/psql.sh -c "select kind, subject, created from admin_notice where created > now() - interval '8 days' order by id"
data/run/psql.sh -c "select layer, day, state, failed_runs, yield, median from layer_health where day > current_date - 8 and layer in ('MAGTECH','OAI') order by layer, day"
```

Expected: no `LAYER_FAILING` notice in the week (unless a real outage, then check the log); the Magtech weekend days
show yield 0 with state OK.

### 5. Dormant proposals after the first coverage run

```
data/run/psql.sh -c "select count(*), count(*) filter (where dormant_dismissed_at is not null) dismissed from source where dormant_since is not null"
data/run/psql.sh -c "select id, title, dormant_since, dormant_evidence from source where dormant_since is not null order by dormant_since limit 20"
curl -s "localhost:18080/api/admin/sources?dormant=true&limit=20" -H "X-Admin-Token: $ADMIN_TOKEN"
```

Expected: only active CORE/BROAD sources with status ACTIVE appear; each has dated evidence (last publication, usual gap);
none is changed (the proposal is never applied). Dismiss one with
`PATCH /api/admin/sources/<id>` `{"dismissDormant":true}` and check it leaves the list.

### 6. Ceasing one journal

Cease one active CORE journal with a route, note the time, and check the next night that no layer called its hosts:

```
curl -s -X PATCH localhost:18080/api/admin/sources/<id> -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"publicationStatus":"CEASED","ceasedReason":"STOPPED","lastIssueYear":2024}'
data/run/psql.sh -c "select id, publication_status, status_changed from source where id = <id>"
# the next morning: no task for its routes since the change
data/run/psql.sh -c "select t.type, count(*) from task t join source_route r on (t.payload->>'routeId')::bigint = r.id where r.source_id = <id> and t.created > '<status_changed>' group by 1"
data/run/psql.sh -c "select id, layer, last_ok, last_error from source_route where source_id = <id>"
grep -c '<route host>' data/run/indexer.log   # compare with the count before the change: no new request lines
```

Expected: no row in the task query, `last_ok` of its routes unchanged, no new requests to its hosts in the log, no new
work with this `source_id` (`select count(*) from work where source_id = <id> and first_seen_at > '<status_changed>'`
is 0). Then set it back (`{"publicationStatus":"ACTIVE"}`) if it was a test.

### 7. `REPROCESS_NAMES {"all":true}` includes its works

```
curl -s -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"REPROCESS_NAMES","payload":{"all":true},"priority":"REPROCESS","dedupKey":"reprocess:manual:all"}'
data/run/psql.sh -c "select state, count(*) from work where source_id = <ceased id> group by 1"
data/run/psql.sh -c "select type, state, count(*) from task where type in ('FIND_NAMES','MATCH_NAMES') group by 1,2"
```

Expected: the works of the ceased journal are reprocessed like all others (a ceased journal's works stay indexed and
listed), none `DEAD`, the same count `INDEXED` at the end.

### 8. Candidate sampling: 200 a night and the score distribution

With the master switch on and `nametracker.candidates.sampling.enabled` true, the nightly run (01:30 UTC) rescoring
all candidates and sampling up to 200:

```
data/run/psql.sh -c "select sampled_at::date d, count(*), count(*) filter (where error is not null) errors, count(*) filter (where articles >= 5) usable from source_sample group by 1 order by 1 desc limit 10"
data/run/psql.sh -c "select width_bucket(candidate_score, 0, 1, 10) b, count(*) from source where tier = 'CANDIDATE' and candidate_score is not null group by 1 order by 1"
data/run/psql.sh -c "select route, count(*), round(avg(articles),1) from source_sample group by 1 order by 2 desc"
data/run/psql.sh -c "select type, state, count(*) from task where type = 'SAMPLE_SOURCE' group by 1,2"
data/run/psql.sh -c "select count(*) from (select source_id from source_sample group by 1 having count(*) > 1 and max(sampled_at) - min(sampled_at) < interval '300 days') x"
```

Expected: about 200 samples a night until the candidates are all sampled (about 600 + discovery nights), no `DEAD`
sample task, no duplicate samples inside 365 days (last query 0 except forced ones), a score distribution that
separates (record the histogram; the weights are start values to tune), **nothing written to `work` by sampling**:
`select count(*) from work w join source s on s.id = w.source_id where s.tier = 'CANDIDATE'` stays 0 (and the work count
rises only by the layers' own harvest). Also watch the CLB load (about 12,000 calls a night, see ADR-0009) and a sample's duration.

### 9. No call to a paid service

In the week's logs and usage:

```
data/run/psql.sh -c "select service, day, calls, usd from api_usage where day > current_date - 8 order by day desc, service"
grep -ci 'budget exhausted\|402\|payment' data/run/indexer.log
grep -o 'https\?://[^/ "]*' data/run/indexer.log | sort | uniq -c | sort -rn | head -40
```

Expected: `usd` within the daily OpenAlex allowance (`NAMETRACKER_OPENALEX_MAX_DAILY_USD`), every host in the list one
of the known free services (OpenAlex, Crossref, Europe PMC, DOAJ, Zenodo, Plazi, ISSN IC if enabled, PKP Beacon,
CLB, the journals' own OAI and Magtech hosts), no other service. Switches that stop each integration:
`NAMETRACKER_DISCOVERY_*_ENABLED`, `NAMETRACKER_CANDIDATES_SAMPLING_ENABLED`, `NAMETRACKER_LAYERS_*_ENABLED`.

### Mail and registration smoke test on the VM

One registration by ISSN by a real signed-in account, then B5 of the local run (promote, user mail, status
`COVERED`); the daily admin note `REGISTRATIONS_WAITING` at 07:00 UTC while one registration still waits.
