# M3 dev check

The M3 finish line (design roadmap): "Taxon page for Carabidae shows this week's papers with correct placements". This
file records how the M2 dev run was switched to the M3 code, the index numbers at the time of the check, and the check
itself. The run continues; see `docs/m2-dev-run.md` for the commands and the week-long harvest criterion.

## Build

On 2026-11-15 at `2d3d66c`: `mvn -B verify` green (all modules, 789 tests and ITs, 0 failures) and
`cd frontend && npm test && npm run build` green (11 files, 25 tests; build ok).

## Restart with the M3 code (2026-11-15, round 5)

- `mvn -B -DskipTests clean install` (a plain `install` had left the indexer jar with an older `store` jar inside), jars
  copied to `data/run/`, old logs kept as `indexer-6.log` and `web-3.log`. Same ports (18080/18081), pid files and
  `.env` as before, plus `FINDER_DICTIONARY=$PWD/eval/work/dictionary-316691.bin`; gnfinder from
  `docker compose up -d gnfinder` (8778).
- **Indexer up 21:53:33 UTC.** Flyway applied V3 (maintenance) and V4 (index) in 5 s.
- **Web up 21:54:37 UTC.** Its first start at 21:53:27 raced the migration and stopped as designed ("database schema
  is behind this web build; start the indexer first"); that log is `web-4.log`. Start web after the indexer has logged
  `Started IndexerApplication`.
- **21:54:46** `XR_CHECK` (`xr-check:manual`): pinned 316691 "COL26.10 XR".
- **21:54:57** `FIND_PENDING` `{"limit":100000}` at `REPROCESS` (`find-pending:manual`): 29,879 `FIND_NAMES` tasks, one
  per `CONTENT_RESOLVED` work, highest work id first. `REPROCESS` keeps the nightly `LIVE` harvests ahead of the backlog.
- **One-off queue edit, 21:56:38.** Work ids are not in publication order, so "highest id first" did not put this
  week's works first: all 976 works published 2026-11-08..14 were still queued. Their `FIND_NAMES` tasks were raised to
  `LIVE` by hand (`UPDATE task SET priority = 0 … publication_date >= '2026-11-08'`, 976 rows), so they and their
  `MATCH_NAMES` follow-ups ran first. Without it, every queued `FIND_NAMES` runs before the first `MATCH_NAMES` of equal
  priority (the queue orders by priority, then `run_after`), and the check would have waited for the whole backlog.
- **22:10:10** all 976 of this week's works `INDEXED` (`FIND_NAMES` about 4 a second, `MATCH_NAMES` bound by CLB's 10
  requests a second).
- **22:10:15** `REBUILD_STATS` by hand (`stats:manual`, `LIVE`), so `taxon_stats` and `name_ubiquity` exist before the
  nightly run at 04:30 UTC; it enqueued `CATEGORISE_SOURCES`, which found no source with 20 indexed works yet. Both
  `DONE`. Note: the ubiquity shares come from these 976 works until the nightly rebuild.

## Numbers at the check (22:13 UTC)

The backlog was still running; these are the numbers when this week's works were done.

- **Works:** `INDEXED` 976 (931 abstract only, 45 full text), `NAMES_FOUND` 1,360 (657 / 703), `CONTENT_RESOLVED`
  27,543 (21,519 / 6,024). 692 of the indexed works have at least one main-subject name.
- **Index rows:** 73,994 mentions (all works the finder has seen, 2,336), 4,741 `work_name` rows, 12,350 name
  strings, 988 classification contexts. `fallback=abstract` (closed full text resolved before M3, so never spooled): 2
  works so far. No mention dropped for a span mismatch.
- **`name_match` by type** (4,734 rows): variant 2,683, exact 1,020, higherrank 778, none 222, ambiguous 31. CLB in
  `RELAXED` mode answers `variant` for most names that are spelled exactly, so `variant` is the usual match type.
- **Placements** (`work_name`, total / main subject / reference only): MATCH 3,751 / 1,391 / 480, VERBATIM_CONTEXT 464 /
  92 / 138, GENUS 252 / 159 / 12, NONE 274 / 58 / 60.
- **Tasks:** `FIND_NAMES` 2,336 done and 27,543 queued; `MATCH_NAMES` 976 done and 1,360 queued; `XR_CHECK`,
  `FIND_PENDING`, `REBUILD_STATS` and `CATEGORISE_SOURCES` one each, done. `RESOLVE_CONTENT` 2,975 `LIVE` and 16,016
  `BACKFILL` re-checks queued for 2026-11-22.
- **DEAD tasks: 0.** No task has a `last_error`. `indexer.log` and `web.log` hold no ERROR and no WARN since the restart.

## Done-when check: Carabidae (8KTL7)

`GET /api/taxa/8KTL7/stats`: `{"total":4,"mainSubjectTotal":3,"years":[{"year":2026,"works":4,"mainSubjectWorks":3}]}`.
`GET /api/taxa/8KTL7/works?limit=50` (main subject by default) lists three works, all published this week:

| Work | Published | Journal | Title (shortened) |
|---|---|---|---|
| 187 | 2026-11-12 | Zootaxa | Revision of the genus *Uenoites* Belousov & Kabak, 2016 (Coleoptera: Carabidae: Trechini) … the gregoryi species group |
| 1859 | 2026-11-11 | Journal of Natural History | The systematic position of the ground beetle *Pterostichus kiangsu* Jedlička (Coleoptera: Carabidae) |
| 210 | 2026-11-09 | Zootaxa | A valid synonymy concealing an undescribed species: Revision of the Maghrebian *Paussus* of the subgenus *Klugipaussus* (Coleoptera, Carabidae, Paussinae) |

All three carry the nomenclatural-acts flag and appear under "New names" too. **Recall:** every work of this week whose
title or abstract says Carabidae, carabid or ground beetle is on the page. One more work names Carabidae only in its
reference list (727, *Elmomorphus*, Dryopidae; "Phylogeny of Bembidion and related ground beetles (Coleoptera:
Carabidae …)"); it is correctly left out (`only_reference`).

**Placements checked by hand.** Each main-subject name of the three works that is placed under 8KTL7 was matched
again with the live CLB API (`/dataset/316691/match/nameusage?mode=RELAXED`, no hints, User-Agent `Garrulus/dev`):
all 19 (18 distinct names) lie under Carabidae.

| Work | Name (kind) | Garrulus placement | CLB, by hand |
|---|---|---|---|
| 187 | *Uenoites* | MATCH 8KZF7 | variant, genus *Uenoites*, Carabidae |
| 187 | Trechini, Carabidae | MATCH 8KTXW, 8KTL7 | tribe Trechini, family Carabidae |
| 187 | *Uenoites gregoryi* | MATCH 7DD8W | variant, species, Carabidae |
| 187 | *U. microps*, *U. setipes*, *U. angulosus*, *U. oculatus* (sp. nov., abbreviated) | VERBATIM_CONTEXT 8KTXW Trechini | higherrank to genus *Uenoites* 8KZF7, Carabidae |
| 187 | *U. gregoryi robustus* (ssp. nov., abbreviated) | VERBATIM_CONTEXT 8KTXW Trechini | higherrank to species *U. gregoryi* 7DD8W, Carabidae |
| 1859 | *Pterostichus kiangsu* | MATCH 4Q5BQ | variant, species, Carabidae |
| 1859 | *P. chinkiangensis* (syn. n.) | MATCH 4Q47K | variant, *Pterostichus (Petrophilus) chinkiangensis*, Carabidae |
| 1859 | *Rhagadus*, Carabidae | MATCH SBC4K, 8KTL7 | *Rhagadus* is a synonym genus (subgenus of *Pterostichus*); path is the accepted lineage, Carabidae |
| 210 | *Klugipaussus* | MATCH PKY69 | synonym genus, Carabidae |
| 210 | *Paussus absconditus* (sp. nov.) | GENUS 8KVXV | higherrank to genus *Paussus*, Carabidae |
| 210 | *Paussus*, Paussinae, Carabidae | MATCH 8KVXV, 8KTQL, 8KTL7 | genus, subfamily, family, Carabidae |
| 210 | *Paussus olcesii* | MATCH 7673D | variant, *Paussus (Klugipaussus) olcesii*, Carabidae |

The main-subject Coleoptera rows of the same works are placed on C2L (Coleoptera), not under Carabidae, as they should
be.

**Verdict: met.** The Carabidae page shows this week's three Carabidae papers, and every main-subject name of them that
is placed under Carabidae belongs there. Screenshot of `http://localhost:5174/taxon/8KTL7` (Vite against web on
18080): `m3-carabidae.png` in the session scratchpad (not committed).

## Wrong or imprecise placements found

- **Abbreviated new species are not placed on their genus (code).** *U. microps* and its siblings are expanded to
  *Uenoites microps* (kind `ABBREVIATED`); CLB answers `higherrank` to genus *Uenoites*, but `Placer` gives `GENUS` only
  to the kinds `BINOMIAL`, `TRINOMIAL` and `INFRAGENERIC`, so they fall back to the lowest context taxon, Trechini. The
  placement stays inside Carabidae, so the done-when holds, but it is one rank level too coarse for every abbreviated
  name of a new species, which is the usual way a revision lists them. Fix: treat `ABBREVIATED` like the multi-word kinds.
- **A subspecies whose species matched** (`higherrank` to a species) falls back to the context as well, by the ADR-0005
  rule. *U. gregoryi robustus* would be better on *U. gregoryi*; worth a rule change in the tuning stage.
- **"Colfax" in a bird paper.** Work 1678 (*Toxostoma crissale*, a thrasher) mentions "Colfax County, New Mexico". The
  finder accepted "Colfax" as a uninomial, and CLB matches the carabid genus *Colfax* 8KW8B. Salience 0.01, so it is
  not a main subject and is not listed on the page, but it makes `total` 4 instead of 3 and shows when
  `mainSubjectOnly=false`. Cause: finder precision (a capitalised place name that is also a COL genus).
- **The same kind of false positive elsewhere** (not Carabidae), found by listing the most frequent matched uninomials:
  - "Microbiota" (gut or root microbiota) placed on the gymnosperm genus *Microbiota* in four papers, all four as main
    subject (title words).
  - "Karnataka" (the Indian state) on a plant genus in three papers, two as main subject.
  - "Prosternum" (a beetle body part, capitalised at the start of a description) on the click-beetle genus in four
    papers, never a main subject.
  - "Meigen" in "*Hilara* Meigen" and "*Coenosia* Meigen, 1826": the author name is found as a separate uninomial and
    matched to a Diptera genus (three papers, one main subject).

  Cause: the finder accepts capitalised words that are COL genera without enough evidence (no italics in abstracts,
  title-case words, authorship tokens). The fix belongs to the finder-tuning stage (judge features for title case,
  sentence start and authorship position; a stop list of place names and anatomical terms).
- **Taxon page "Verbatim classifications used"** lists every context of the recent works, including "Formicidae:
  Myrmicinae" (the host ants of *Paussus*, work 210). Correct data, but not a classification of Carabidae; the page
  could keep only contexts that contain the taxon's name or one of its descendants.
- Work 1678 also has two `TREATMENT` contexts that are binomials (*Toxostoma crissale*, *Campylorhynchus
  brunneicapillus*): the context extractor treats a species heading as a classification.

## Still running

The indexer works through the backlog: about 27,500 `FIND_NAMES` and then their `MATCH_NAMES` (CLB at 10 requests a
second); expect some hours. The nightly crons run as before (harvests 02:00/03:00, stats 04:30, spool purge 05:30,
`XR_CHECK` 06:00 UTC).

## Final fix wave (2026-11-15)

The whole-milestone review fixes (`ef7fd70`..`a4cec59`): depth-first follow-ups, `ABBREVIATED` names on their genus,
the name page match of hinted names, outage classification for gnfinder and CLB, and the smaller items in ADR-0005.
`mvn -B verify` green (812 tests and ITs, 0 failures); frontend 11 files, 26 tests, build ok.

- **Restart.** `mvn -B -DskipTests clean install`, jars copied to `data/run/`, old logs kept as `indexer-7.log` and
  `web-5.log` (no WARN or ERROR in either). Only the pids in `data/run/*.pid` were stopped (22:36:42 UTC); same env,
  ports and `.env` as before. **Indexer up 22:36:53 UTC** (Flyway applied V5, the `name_match (release_key, usage_id)`
  index), **web up 22:36:59 UTC**.
- **Re-match of the indexed works**, so that placements made by the old `Placer` are redone. There is no bulk admin
  task for matching, so the tasks were inserted with `data/run/psql.sh` at 22:37:07 UTC:

  ```sql
  INSERT INTO task (type, payload, priority, dedup_key, run_after)
  SELECT 'MATCH_NAMES', jsonb_build_object('workId', w.id), 1, 'match:' || w.id || ':fix-wave',
         timestamptz '2026-11-15 21:55:00+00'
  FROM work w WHERE w.state = 'INDEXED'
  ON CONFLICT (dedup_key) WHERE state IN ('QUEUED','RUNNING') DO NOTHING;
  ```

  1,021 tasks at `REPROCESS` (priority 1). `run_after` is set just before the oldest queued `REPROCESS` task
  (21:55:03), so the re-match runs ahead of the `FIND_NAMES` backlog instead of behind it. All 1,021 `DONE` between
  22:37:09 and 22:41:25 UTC (matches came from the `name_match` cache), none failed.
- **`/api/works/187`** after the re-match: *Uenoites microps*, *U. angulosus*, *U. oculatus* and *U. setipes*
  (`ABBREVIATED`, sp. nov.) are now `GENUS` on *Uenoites* 8KZF7 instead of `VERBATIM_CONTEXT` on Trechini. *U. gregoryi
  robustus* stays `VERBATIM_CONTEXT` (Trechini): its match is `higherrank` to the species *U. gregoryi*, which is a
  context fallback by the ADR-0005 rule. Saliences moved down a little (e.g. *Uenoites* 0.86 to 0.84), because
  re-indexing applies the `name_ubiquity` penalty that did not exist when the works were first indexed. Over all works,
  `ABBREVIATED` placements are now MATCH 526, GENUS 105, NONE 37, VERBATIM_CONTEXT 18.
- **`/api/names/6664`** (*Uenoites gregoryi*, matched only with context hints and its authorship) now shows its match,
  7DD8W *Uenoites gregoryi* (species) with its classification; before, the page said "not matched". This needed no
  re-match, only the new query. `/api/names/6666` (*U. microps*) shows the genus *Uenoites* it is placed on.
- **Depth first in the live queue.** After the re-match the backlog resumed with each `FIND_NAMES` followed by its own
  `MATCH_NAMES` about two seconds (one poll) later, for example work 22914: found 22:41:27.02, matched 22:41:29.01.
  Works are `INDEXED` steadily now instead of after the whole `FIND_NAMES` backlog.

## N1: probe of the match endpoint (2026-11-15)

`5ff02f4`: after a failed match call `ClbClient` now probes the match endpoint itself (`Animalia`, kingdom, same
release) instead of `/dataset/3LXR`, a probe 4xx fails the task instead of placing names `NONE`, and more than 3 names
at fault in one task defer it as an outage (ADR-0005). `mvn -B verify` green, 817 tests and ITs. Only the indexer was
restarted: old log kept as `indexer-8.log` (no WARN or ERROR), pid 63095 stopped at 22:53:54 UTC, **indexer up
22:53:57 UTC** with the same env on 18081; web (18080) kept running. In the first 14 s it finished 27 `FIND_NAMES` and
26 `MATCH_NAMES`, depth first, with no WARN, ERROR or `DEAD` task.
