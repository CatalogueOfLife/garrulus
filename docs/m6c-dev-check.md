# M6c dev check (crosslinks, languages, fallbacks, mentioned-in, institutional)

The dev check of M6c (ADR-0010). Three parts: a local run on the dev-run database of `docs/m2-dev-run.md` (pending,
below), the checklist for the GBIF VM, where everything that needs real nights, real keys or a week of logs runs, and
the open items: one product question for Markus and the deferred minors of the build, grouped for the final review.
As for M6a and M6b, the laptop's nights do not count (it is often offline). Paths are relative to the run directory;
`data/run/psql.sh -c "..."` is the psql helper of the dev run.

The admin calls below use this helper (manual run on 18080, `ADMIN_TOKEN` set as in CLAUDE.md):

```
task() { curl -s -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d "$1"; echo; }
```

## Local run (2026-11-21, done except LibreTranslate and the browser steps)

Runs once, after the final review's fix wave, so the dev run gets the final code. Results below the steps, dated.

1. **Build and test:** `mvn -B verify`, then `cd frontend && npm test && npm run build`, all green.
   `LinkWorkHandlerIT.newSchemeAndLookupNeedNoMigration` (a scheme and its lookup added without a migration) is part
   of `mvn -B verify`; there is no CI, so this run is its record. (The indexer tests use the production HTTP/1.1
   client since the fix wave; the earlier `EuropePmcLinkLookupIT` resets came from the JDK default's h2c upgrade.)
2. **Deploy:** jars from `main` into `data/run/` (the previous ones kept as `*-2026-11-2x-pre-m6c.jar`), then restart
   **the indexer first (it migrates), then web**: a web started first refuses to start against the older schema
   (`SchemaCheck`, "behind") until the indexer has migrated; the old web keeps serving meanwhile.
   **Flyway applies V21 to V24 together.** Record the time: V21's `work.fts`
   `SET EXPRESSION` rewrites all of `work` (about 104k rows) under an ACCESS EXCLUSIVE lock, and its `LEGACY` back-fill
   copies every work's language.

   ```
   data/run/psql.sh -c "select version, success, execution_time from flyway_schema_history order by installed_rank desc limit 4"
   data/run/psql.sh -c "select count(*) from work_language_evidence where kind = 'LEGACY'"
   ```

3. **Languages:** counts before, then `DETECT_LANGUAGE {}` (reads the works whose `language_source` is null or
   `UNDECIDED`), then the same counts after; and the 148 works OpenAlex called Uzbek.

   ```
   data/run/psql.sh -c "select language_source, count(*) from work group by 1 order by 2 desc"
   data/run/psql.sh -c "select coalesce(language,'(none)') l, count(*) from work group by 1 order by 2 desc limit 20"
   data/run/psql.sh -c "create table if not exists m6c_uz as select work_id from work_language_evidence where kind = 'LEGACY' and lang = 'uz'"
   task '{"type":"DETECT_LANGUAGE","payload":{},"priority":"REPROCESS","dedupKey":"detect-language:manual"}'
   # after all DETECT_LANGUAGE tasks are DONE:
   data/run/psql.sh -c "select w.language, w.language_source, count(*) from work w join m6c_uz u on u.work_id = w.id group by 1,2 order by 3 desc"
   data/run/psql.sh -c "select count(*) filter (where language_source = 'UNDECIDED') * 100.0 / count(*) from work where title is not null"
   ```

   Expected: no `uz` left among the 148 unless its journal lists Uzbek; the undecided share recorded (target on the VM:
   under 2 %). Drop `m6c_uz` afterwards. Then spot-check 20 `DETECTED` decisions by hand (title and decided language).
4. **Links:** `LINK_WORK` for 200 ZooKeys works, `LINK_SOURCE` for Phytotaxa, ZooKeys and Zootaxa.

   ```
   ids=$(data/run/psql.sh -At -c "select string_agg(w.id::text, ',') from (select w.id from work w join source s on s.id = w.source_id where s.issns && array['1313-2989','1313-2970'] and w.state in ('INDEXED','NOTIFIED') order by w.id desc limit 200) w")
   task "{\"type\":\"LINK_WORK\",\"payload\":{\"ids\":[$ids]},\"priority\":\"REPROCESS\",\"dedupKey\":\"link:manual:zookeys\"}"
   src=$(data/run/psql.sh -At -c "select string_agg(id::text, ',') from source where issns && array['1179-3155','1179-3163','1313-2989','1175-5326']")
   task "{\"type\":\"LINK_SOURCE\",\"payload\":{\"ids\":[$src]},\"priority\":\"REPROCESS\",\"dedupKey\":\"link:manual:sources\"}"
   data/run/psql.sh -c "select scheme, count(*) from work_identifier where work_id in ($ids) group by 1 order by 1"
   data/run/psql.sh -c "select lookup, found, attempts, count(*) from link_attempt where entity = 'W' and subject_id in ($ids) group by 1,2,3 order by 1,2"
   data/run/psql.sh -c "select s.title, i.scheme, i.value from source_identifier i join source s on s.id = i.source_id where s.id in ($src) order by 1,2"
   ```

   Record links per scheme and the miss share per lookup; check one ZooKeys work page and the three journal pages in
   the SPA. The Biodiversity PMC requests of the day: `select * from api_usage where service = 'biodiversitypmc'`.
   Rows with `attempts = 0` are lookups put off because their service was down or their cap spent (`next_try` is
   when they come due again); the other lookups of the same task ran regardless. Lookup `none` marks a work no lookup
   applied to (seen, never retried).
5. **LibreTranslate** (see CLAUDE.md "LibreTranslate"), started by hand:
   `libretranslate --host 127.0.0.1 --port 5055 --load-only zh,ja,ru,en`, then the indexer restarted with
   `NAMETRACKER_TRANSLATION_ENABLED=true` and `NAMETRACKER_TRANSLATION_URL=http://127.0.0.1:5055`. Note its resident
   memory (`ps -o rss= -p <pid>`) and record the codes it names (`curl -s 127.0.0.1:5055/languages | jq -c
   '[.[].code]'`): the client normalises them (`zh-Hans` and `zh-Hant` to `zh`, `nb` to `no`) and sends the service's
   own code back, so a Chinese title is translated whether the service says `zh` or `zh-Hans`. Translate 50 named works
   of those languages without an English title:

   ```
   for id in $(data/run/psql.sh -At -c "select id from work where names_doc_sha is not null and title_en is null and coalesce(title_lang, language) in ('zh','ja','ru') order by id desc limit 50"); do
     task "{\"type\":\"TRANSLATE_TITLE\",\"payload\":{\"workId\":$id},\"priority\":\"REPROCESS\",\"dedupKey\":\"translate:manual:$id\"}"
   done
   data/run/psql.sh -c "select w.language, count(*) filter (where t.work_id is not null) translated, count(*) from work w left join work_text t on t.work_id = w.id and t.kind = 'MACHINE' and t.field = 'TITLE' where w.id in (select id from work where coalesce(title_lang, language) in ('zh','ja','ru') and names_doc_sha is not null) group by 1"
   grep -c 'Dropped the translation' data/run/indexer.log
   ```

   Record seconds per title (task `finished - started`), how many were dropped for a lost name (each drop is remembered:
   `select count(*) from text_lookup where service like 'TRANSLATE:%'`; only `force` or a new model asks again), and
   that an English
   word of one translated title finds the work (`/api/works?q=<word>`) and its list entry says "Machine translation".
   (The brief's `TRANSLATE_TITLE {"all":true}` pages every non-English title; on the dev DB 50 single tasks are enough.)
6. **Top-ups:** one SciELO and one Europe PMC work without abstract.

   ```
   data/run/psql.sh -c "select id, doi from work where abstract is null and abstract_jats is null and doi like '10.1590/%' limit 3"
   data/run/psql.sh -c "select w.id, w.doi from work w join work_identifier i on i.work_id = w.id and i.scheme = 'PMID' where w.abstract is null and w.abstract_jats is null limit 3"
   task '{"type":"RESOLVE_CONTENT","payload":{"workId":<id>,"freeOnly":true},"priority":"REPROCESS","dedupKey":"content:manual:<id>"}'
   data/run/psql.sh -c "select id, abstract_source, abstract_lang, text_level from work where id = <id>"
   data/run/psql.sh -c "select * from text_lookup where work_id = <id>"
   data/run/psql.sh -c "select field, lang, kind, source from work_text where work_id = <id>"
   ```

   The SciELO PID comes from Crossref's `alternative-id` for the DOI (ArticleMeta's identifiers listing has no DOI field and
   filters by processing date, so the first dev run found nothing). SciELO answers no abstract for some new articles: a miss.

   Expected: the abstract filled (`SCIELO`, `EUROPEPMC`), a `FIND_NAMES` after it, SciELO's parallels in `work_text`.
7. **Browser** (Markus): the work page's identifier row, the "Translate" button on a non-English abstract (the network
   panel shows **no request** while it translates), the search page's language facet and mentioned-in boxes, a follow
   with "Mentioned in: title".

**Results (2026-11-21).** Steps 1 to 4 and 6 done; step 5 (LibreTranslate) not run on the laptop (torch and the
models are several GB over a travel connection: run it on the GBIF VM); step 7 is Markus's.

| Item | Count |
|---|---|
| Build and test (step 1) | `mvn -B verify` BUILD SUCCESS on every module; frontend 338 tests, `npm run build` green |
| V21 to V24 apply time | 21.3 s in all, V21 21.3 s (`work.fts` rewrite and `LEGACY` back-fill of 111,268 works under ACCESS EXCLUSIVE; about 17 min at 5M if linear: apply it before the backfill); V22 to V24 under 15 ms |
| `language_source` before / after `DETECT_LANGUAGE` | before: null 111,268 (`LEGACY` evidence 110,822); after (198 batches): DETECTED 79,252, FULLTEXT 19,008, OPENALEX 12,621, UNDECIDED 387; languages en 110,128, ru 313, es 148, fr 100, de 65, ja 23 |
| The 148 `uz` works: new values | UNDECIDED 143, `tr` (DETECTED) 5. **They are Uzbek** (one journal, source 221 "Agro kimyo himoya va o`simliklar karantini", no country, no languages): OpenAlex was right. See finding L1 |
| Undecided share of works with a title | 0.35 % |
| Spot check of 20 DETECTED decisions | 17 right; 3 short English titles decided `de`, `no`, `vi` by a title margin just over 0.10 against an English abstract. See finding L2 |
| ZooKeys links per scheme (200 works), misses per lookup | CROSSREF 200, DOI 200, OPENALEX 200, BIODIVERSITY_PMC 191, PMCID 191, PMID 190, PLAZI 139, BLR 198 (after the Zenodo fix); misses: europepmc 10, plazi 61, zenodo-blr 2. Biodiversity PMC 20 requests that day |
| Phytotaxa, ZooKeys, Zootaxa journal identifiers | Zootaxa WIKIDATA Q220370, CROSSREF_JOURNAL; Phytotaxa WIKIDATA Q4807825, IPNI_PUB 20011462-1, CROSSREF_JOURNAL (no BPH: known, ADR-0010); ZooKeys WIKIDATA Q219980, DOAJ, CROSSREF_JOURNAL |
| Titles translated / dropped, seconds per title, LibreTranslate RSS, its `/languages` codes | not run (step 5 pending) |
| Top-ups (SciELO, Europe PMC) | Europe PMC: definite misses for both works tried (no work with a PMID lacks an abstract on the dev DB). SciELO: after the fix 4 of 4 articles found by PID (language DECLARED `en`); no abstract added for the two BJB works (ArticleMeta has no `v83` for them, checked live); no parallels for the Zoologia ones (English only) |

**Keys tried (2026-11-24).**
- **Springer Nature Meta** (`SPRINGER_API_KEY`): works. A direct call answers 200 with one record and the abstract as a
  plain string (`records[].abstract`), the shape the code reads. End to end on three Ichthyological Research works
  (Springer DOIs, journal country JP, no abstract): two filled (`abstract_jats_source` SPRINGER, served by
  `/api/works/108416` with 6 abstract mentions, `FIND_NAMES` done), one a definite miss (no abstract at Springer);
  3 calls counted in `api_usage` (`SPRINGER_META`).
- **CiNii** (`CINII_APPID`): works. The id was refused (403 "access denied") on the day the registration mail
  arrived and accepted a few hours later (NII activates it after the mail). The live shape matches the code: items
  carry `description`, `abstractLicenseFlag` and the DOI as a typed `dc:identifier` (`{"@type":"cir:DOI","@value":…}`;
  no `prism:doi`). On the dev data only five works qualify (Acta Arachnologica, the dev DB's only Japanese journal
  with non-Springer DOIs), all flagged `disallow`: end to end on work 56760 CiNii answered, the miss was recorded
  (`text_lookup` CINII false) and no abstract was stored, as the licence rule requires. An `allow` case is still to
  be seen on the GBIF VM.

**Bugs found and fixed in this run** (each with a test that pins the live behaviour):
- `efd539f`: Zenodo DOI queries asked for 100 rows; Zenodo answers 400 above 25 for anonymous requests (checked live),
  so every `zenodo-blr` lookup was a refused DOI and a miss (0 of 200). Now 25 rows: 198 of 200.
- `331a65a`: the SciELO top-up searched ArticleMeta's identifiers listing for the DOI, but the listing has no DOI field
  (and its window filters the processing date), so it could never find an article. The PID now comes from
  Crossref's `alternative-id`; the article is accepted only when its DOI equals the work's.

**Findings left open** (recommendations; no code changed after the closed review):
- **L1, languages the detector lacks.** Lingua's set has no Uzbek, so the rare-language rule dropped OpenAlex's
  correct `uz` (143 UNDECIDED) and a Turkish detection overrode it 5 times. Recommended: keep OpenAlex's language
  when the detector does not support it, unless the detector is confident of English; and never let a DETECTED
  language override an unsupported OpenAlex language.
- **L2, a weak title beats a stronger abstract.** TITLE evidence ranks before ABSTRACT: 83 works have title and
  abstract disagreeing, 80 were decided by the title, 68 of those against the abstract and OpenAlex, 71 with a title
  margin under 0.15 (about 12 % of the non-English DETECTED decisions). Recommended: the title wins only from a margin
  of about 0.20 (a property), else an agreeing abstract and OpenAlex decide. Part of the min-margin tuning item.
- **L3, `DETECT_LANGUAGE` can fork.** A batch retried after a laptop sleep (connection pool errors during the clock
  leap) ended at another id, because the default mode selects by state, and its continuation key
  `detect-language:<last>` started a second chain (idempotent; undecided works read twice). Key the continuation on
  the task's own `afterId`. Other paged handlers select by id alone and cannot fork.

## GBIF VM checklist

Run after the M6c deployment on the always-on installation. Deployment checks first:

- **V21 to V24 apply together, before the full backfill.** V21's `work.fts` `SET EXPRESSION` rewrites all of
  `work` under ACCESS EXCLUSIVE in one transaction: instant on the empty database, blocking `web` for the whole rewrite
  on a populated one. Check: `select version, success, execution_time from flyway_schema_history order by installed_rank desc limit 4`.
- **Queue `DETECT_LANGUAGE {}` after the deploy** (on a database that held works before V21), so legacy rows are decided:
  `task '{"type":"DETECT_LANGUAGE","payload":{},"priority":"REPROCESS","dedupKey":"detect-language:deploy"}'`.
- **The `LINK_WORK` back-fill of existing works** (optional speed-up: the daily sweep also reaches every listed work no
  lookup has seen, at most `retry-per-day` a day; this pages through all of them at once and recovers a work whose
  enqueue was lost):
  `task '{"type":"LINK_WORK","payload":{"afterId":0},"priority":"BACKFILL","dedupKey":"link-works:backfill"}'`.
  It continues per batch at `BACKFILL`; a lookup whose service is down or whose cap is spent puts its targets off and
  the pages go on.
- **Works of origin `BACKFILL` get no `LINK_WORK` of their own** when first indexed: the snapshot backfill, route
  backfills, the Crossref history and any layer's records older than the live window (one task per work would turn the
  batched lookups into about 5M single Crossref and 5M single Europe PMC calls). **The daily link sweep catches them
  up**, with works indexed while linking was off: listed works without any `link_attempt` row, walked by id from
  `job_cursor` `link-untried:W`, at most `nametracker.links.retry-per-day` (100,000, shared with the due retries) a
  day. Record how many days it takes after the full backfill:
  `select position, updated from job_cursor where name = 'link-untried:W'` and
  `select count(*) from work w where w.state in ('INDEXED','NOTIFIED') and not exists (select 1 from link_attempt a where a.entity = 'W' and a.subject_id = w.id)`.
  Optional speed-up once the backfill's `FIND_NAMES`/`MATCH_NAMES` have drained: the paged run (works linked before
  are skipped by their attempts)
  `task '{"type":"LINK_WORK","payload":{"afterId":0},"priority":"BACKFILL","dedupKey":"link-works:after-backfill"}'`.
  The misses come due together after 30 days, under the same daily limit.
- Keys and switches: `SPRINGER_API_KEY` and `CINII_APPID` (free) set or knowingly blank;
  `NAMETRACKER_INSTITUTIONAL_ENABLED` **false** and `NAMETRACKER_INSTITUTIONAL_PUBLISHERS` empty;
  `NAMETRACKER_TRANSLATION_*` set only once LibreTranslate runs as a service; port 5055 (like gnfinder's 8778) closed to
  other hosts by the firewall.
- Indexer heap: Lingua adds about 52 MB (low accuracy); record the heap with and without
  `nametracker.language.high-accuracy=true` (about +970 MB) before deciding to switch it.

### 1. Done-when lines of M6c

**Links of a ZooKeys work.** After the first indexings (or the back-fill), the ZooKeys work of the tests
(`10.3897/zookeys.1187.113403`) shows DOI, Crossref, OpenAlex, PMID/PMCID, BLR, Plazi and Biodiversity PMC (it has acts):

```
data/run/psql.sh -c "select i.scheme, i.value, i.first_layer from work_identifier i join work w on w.id = i.work_id where w.doi = '10.3897/zookeys.1187.113403' order by 1"
curl -s "localhost:18080/api/works?identifier=PMCID:PMC10768028" | jq '.results[] | {id, title}'
```

Expected: `DOI`, `CROSSREF`, `OPENALEX` (W4390339873), `PMID` (38187161), `PMCID` (PMC10768028), `BLR` (10446357),
`PLAZI`, `BIODIVERSITY_PMC` (`pmc/PMC10768028`); the identifier search finds the work. Open its page and click each
link once.

**Phytotaxa's journal links.** OpenAlex, Crossref (journal), Wikidata and IPNI (`20011462-1`) on its page. **BPH cannot
show**: neither IPNI nor Wikidata holds a BPH id for Phytotaxa (2026-11-19, ADR-0010); check whether that changed:

```
data/run/psql.sh -c "select i.scheme, i.value from source_identifier i join source s on s.id = i.source_id where s.issns && array['1179-3155','1179-3163'] order by 1"
```

**Extension test.** `newSchemeAndLookupNeedNoMigration` green in the `mvn -B verify` of the deployed commit (no CI).

**Mentioned-in.** The taxon query returns only papers with a name of that taxon in title or abstract:

```
curl -s "localhost:18080/api/taxa/<colId>/works?mainSubjectOnly=false&mentionedIn=TITLE&mentionedIn=ABSTRACT&limit=100" | jq '[.results[].id]'
# every id: some name under the taxon in title or abstract
data/run/psql.sh -c "select w.id, bool_or(wn.in_title or wn.in_abstract) from work w join work_name wn on wn.work_id = w.id and '<colId>' = any(wn.taxon_path) and not wn.only_reference where w.id in (<ids>) group by 1 having not bool_or(wn.in_title or wn.in_abstract)"
```

The second query returns no rows. Then a follow with `mentioned_in = {TITLE}` (Markus' account, a busy taxon) over a
week: every `hit` has a name of that taxon in the title, and the existing follows' hit counts look as before.

```
data/run/psql.sh -c "select h.work_id from hit h join subscription s on s.id = h.subscription_id where s.type = 'TAXON' and s.mentioned_in = '{TITLE}' and not exists (select 1 from work_name wn where wn.work_id = h.work_id and wn.in_title and s.target = any(wn.taxon_path))"
data/run/psql.sh -c "select s.mentioned_in, h.created::date, count(*) from hit h join subscription s on s.id = h.subscription_id where h.created > now() - interval '8 days' group by 1,2 order by 2,1"
```

**Languages decided for all works.** Undecided under 2 % of works with a title; the 148 `uz` works (if the VM's data
holds them) re-decided; filter, facet and statistics answer:

```
data/run/psql.sh -c "select language_source, count(*) from work where title is not null group by 1 order by 2 desc"
curl -s "localhost:18080/api/works?language=zh&facet=language&limit=1" | jq '.facets'
curl -s "localhost:18080/api/works?language=none&limit=1" | jq '.total'
curl -s "localhost:18080/api/stats/languages?yearFrom=2020" | jq '.[0:10]'
curl -s "localhost:18080/api/stats/languages?taxgroup=Insects" | jq '.[0:10]'
curl -s -o /dev/null -w '%{http_code}\n' "localhost:18080/api/stats/languages?taxgroup=Insects&country=CN"   # 400
data/run/psql.sh -c "explain select count(*) from work w where w.state in ('INDEXED','NOTIFIED') and (w.language in ('zh') or w.language is null)"
```

The plan uses `work_language_idx` (an index condition on `language`), the predicate the `language` filter sends.

**Parallels.** Magtech and SciELO works carry their English (or original) versions:

```
data/run/psql.sh -c "select t.source, t.field, t.kind, t.lang, count(*) from work_text t group by 1,2,3,4 order by 1,2,3,4"
data/run/psql.sh -c "select title_lang, title_en_kind, count(*) from work w join work_identifier i on i.work_id = w.id and i.scheme = 'MAGTECH' group by 1,2"
```

Existing Magtech works get `title_lang` and their Chinese original only when their page is read again (ADR-0010).

**Machine titles.** With LibreTranslate as a service, non-English titles without an English version get a MACHINE
title, labelled "Machine translation" in lists, digests, feeds and the work page, and an English search finds them
(`/api/works?q=<an English word of one>`). **Abstracts are translated only in the browser:** the network panel shows no
request while the "Translate" button works.

**Institutional route off:** `select count(*) from institutional_fetch` is 0, and `GET /api/admin/status` shows no
`institutional` traffic.

**No paid call in the week:**

```
data/run/psql.sh -c "select service, day, calls, usd from api_usage where day > current_date - 8 order by day desc, service"
grep -o 'https\?://[^/ "]*' data/run/indexer.log | sort | uniq -c | sort -rn | head -50
```

Expected: OpenAlex `usd` within its allowance; every host a known free service (OpenAlex, Crossref, Europe PMC,
Biodiversity PMC, Zenodo, Plazi, Wikidata, IPNI, DOAJ, SciELO, CiNii and Springer when keyed, CLB, the journals' own
OAI and Magtech hosts); no `api.elsevier.com` or `api.wiley.com` while the route is off.

### 2. Measurements that confirm the start values

- **Links after a week of first indexings:** found per lookup and the miss share per scheme.

  ```
  data/run/psql.sh -c "select entity, lookup, found, attempts, count(*) from link_attempt group by 1,2,3,4 order by 1,2,3,4"
  data/run/psql.sh -c "select scheme, first_layer, count(*) from work_identifier where first_seen > now() - interval '8 days' group by 1,2 order by 1,2"
  ```

- **Wikidata and IPNI coverage of CORE/BROAD journals:**

  ```
  data/run/psql.sh -c "select i.scheme, count(distinct s.id), (select count(*) from source where tier in ('CORE','BROAD')) total from source s join source_identifier i on i.source_id = s.id where s.tier in ('CORE','BROAD') group by 1 order by 1"
  ```

  Also look at the `DOAJ` values from Wikidata (P5115 holds ISSN-like values; they link to `doaj.org/toc/<issn>`).
- **The Biodiversity PMC daily cap** (200 requests, about 1.7 GB): requests a day, and whether the cap is hit (its
  targets put off to 00:05 UTC, `link_attempt` rows with `attempts = 0`; the other lookups go on):
  `select day, calls from api_usage where service = 'biodiversitypmc' order by day desc limit 14` and
  `select lookup, count(*) from link_attempt where attempts = 0 and next_try is not null group by 1`.
- **Detection agreement:** on works with both a declared language and a detection, at least 95 % agree:

  ```
  data/run/psql.sh -c "select count(*) filter (where d.lang = x.lang) * 100.0 / count(*) from work_language_evidence d join work_language_evidence x on x.work_id = d.work_id and x.kind = 'DECLARED' where d.kind = 'DETECTED'"
  ```

  Then a hand check of 50 DETECTED decisions per top-5 non-English language
  (`select id, title, language from work where language_source = 'DETECTED' and language = '<l>' order by random() limit 50`).
  **Tune `nametracker.language.min-margin`** (start 0.10; Lingua's confidence is max-normalised, the margin is the
  gate) and `min-title-words` (3); after a change `DETECT_LANGUAGE {"all":true}`.
- **The `REBUILD_STATS` run time with the language statistics at 5M works** (`taxon_language_stats`, `language_stats`
  rebuilt in one transaction with `taxon_stats`):

  ```
  data/run/psql.sh -c "select finished - started, finished from task where type = 'REBUILD_STATS' and state = 'DONE' order by finished desc limit 7"
  data/run/psql.sh -c "select dim, count(*) from language_stats group by 1"
  ```

  If it grows past an hour, compute only the slices the endpoints read.
- **LibreTranslate:** resident memory with the loaded models (reject above 4 GB), seconds per title, and **Markus'
  rating of 30 Chinese, 30 Japanese and 30 Russian taxonomic titles** as useful or misleading. More than 20 % misleading:
  try OPUS-MT on CTranslate2 behind `TitleTranslator`. Also the drop rate of translations that lost a name (the INFO
  lines "Dropped the translation").
- **Top-ups:** abstracts gained per service and Springer's daily calls against its cap:

  ```
  data/run/psql.sh -c "select service, found, count(*) from text_lookup group by 1,2 order by 1,2"
  data/run/psql.sh -c "select abstract_source, count(*) from work group by 1 order by 2 desc"
  data/run/psql.sh -c "select day, calls from api_usage where service = 'SPRINGER_META' order by day desc limit 14"
  ```

- **TEI re-checks:** `select day, calls from api_usage where service = 'TEI_RECHECK' order by day desc limit 14` (cap 200).
- **Browser checks of links scripts cannot verify** (403 to scripted clients): a PMID link, a PMCID link (europepmc.org)
  and a DOAJ link (doaj.org) open the right page; else switch PMID to `https://pubmed.ncbi.nlm.nih.gov/{v}/`.

### 3. Live checks that need a key or a confirmation

- **Springer Nature Meta**, once a free key is set: one 200 answer has the shape the code reads (`records[].abstract`);
  the tests use the documented format (only the 401 was seen live).
- **CiNii Research**, once a free appid is set: one 200 answer has `description` and `abstractLicenseFlag` (or
  `cir:abstractLicenseFlag`) and the DOI in `prism:doi`, `doi` or a DOI-typed `dc:identifier`; the fixtures are
  synthetic (all live samples of 2026-11-19 were `disallow`).
- **OAI JATS, JoTT:** one `GetRecord` with `metadataPrefix=jats` gives a `<body>` with paragraphs at the path the route
  reads (the fixture is hand-made), and the work's text level becomes FULLTEXT under its CC licence.
- **Elsevier and Wiley**, only once KU tokens exist **and the KU library has confirmed the licences in writing**:
  whether Elsevier's full-text XML carries a DOCTYPE (the parser refuses one, which would make every answer UNUSABLE)
  and whether it needs a `view` parameter for the full text; whether Wiley's TDM URL redirects (a cross-host redirect
  drops the token header by design) and that GROBID reads its PDFs. Switch on one publisher, a handful of DOIs, and
  read `select publisher, status, outcome, count(*) from institutional_fetch group by 1,2,3`.

## Decided: taxgroup follows count main subjects only

Decided by Markus on 2026-11-24. The taxgroup criterion means "a main-subject name of that group (or a descendant
group)" in the works search, the public taxgroup feed and TAXGROUP follows and hits, with and without `mentionedIn`,
so all three list the same works. A follow's "main subject only" flag no longer matters for a TAXGROUP target. A work
whose only name of the group is not a main subject is neither listed nor alerted. Existing hits for such works stay.

## Deferred minors, by area (for the final review)

**HTTP and security**
- `PoliteHttp`: an http to https upgrade on the same host drops caller headers (safe, but surprising); no test that a
  redirect hop's error message is redacted; `sleep` takes a raw URI.
- `IdScheme.define`: the plain-host rule fires only for templates starting `https?://{0}`; a placeholder elsewhere in the
  authority (`https://www.{0}/`, `https://user@{0}/`) is not validated; `PLAIN_HOST` is loose (`.`, `-`, `..`).
  `IdScheme.of` searches only the built-ins.

**Crosslinks**
- `insertAll([])` returns -1 (as `WorkIdentifierMapper`).
- The `first_indexed_at` commit and the `LINK_WORK` enqueue are not atomic (the L11 back-fill recovers);
  `MatchNamesHandler` keeps a production constructor that disables linking (prefer test wiring).
- Clearing a due row of a work that left INDEXED is permanent (no retries if it returns); global `sweep()` counts in ITs
  may depend on test order in the shared DB; `LinkRunner.valid` hard-codes the column scheme strings.
- OpenAlex single-DOI lookup fetches the full work (no `select`).
- Rows of an unregistered scheme are listed publicly with their raw code (decide: hide them?); `distinct()` before
  `registry.links` for journal lists; tests for the `work.doi` fallback, no duplicate DOI, and the DOI URL form in the
  identifier filter; `IdentifierViews` depends on the static `WorkController.bad`; a long `WorkFilter.build()` line;
  `SearchMapperIT` keeps a local builder wrapper.

**Languages and texts**
- Multi-valued or decorated OAI language strings give null (a first-token fallback would help); locale tables depend on
  the JDK; the markup stripper drops text between a bare `<` and `>`; HTML entities are not decoded before detection.
- `translateIfNeeded` enqueues on every decide (deduplicated; no longer after a remembered drop); `BackfillFileIT`
  approximates "survives FIND_NAMES"
  through `detectFromWork` and `decide` instead of the handler.
- The Magtech Chinese ORIGINAL title is not in `work.fts`; overload noise in `WorksPager.store`, `recordLanguage` and the
  `HarvestedWork` constructors; the BLR layer's and the known-work JATS branch's Crossref items carry no
  `original-title`; a repeated OAI `dc:description` in another language than the work's (a translated rights
  statement) still becomes a PARALLEL abstract.
- `TRANSLATE_TITLE` retranslates a MACHINE row of another model without `force` (deviation from the brief, deliberate).

**Fallbacks and the institutional route**
- `OaiJatsRoute` repeats its identifier and route reads in `applies` and `fetch`.
- `TeiRecheck` drops a flip beyond its 60-day horizon (WARN); a dedup-swallowed enqueue still uses a slot of the day;
  no keyless test of the TEI gate.
- `ElsevierXml` ignores `ce:table` cells; the frontend `AdminStatus` type lacks `institutional`; a persistent publisher
  5xx or GROBID outage retries one work every 30 minutes (each a cap unit, bounded by the cap); a redirect chain inside
  one `getOnce` is one audit row; GROBID down after a Wiley success fetches the PDF again (two cap units).

**Mentioned-in, filters and frontend**
- The group test sits only in the `taxon == null && name == null` branch; the `WorkFilter` compact constructor
  recomputes `taxgroupMembers`.
- `SourcePage` always issues the works query (its facet counts the whole journal while the recent list shows 10);
  `mentionedIn` from the URL is not validated on taxon and name pages (the API's 400 shows); `setParams` is duplicated
  (a `useListParam` hook); "Mentioned in" and "Name occurs in" headings are redundant; the language facet is
  single-select though the API takes several.
- `/api/stats/languages` sends no CORS headers (only `/api/taxa/**` does); add it if the portal widget needs it.
