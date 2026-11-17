# M5 dev check

The M5 finish lines, checked locally on 2026-11-17 against the dev run (web 18080, indexer 18081, public CLB API,
in-process finder with `eval/work/dictionary-316691.bin`):
- **M5a:** an XR switch, and the automatic switch back, complete with zero hits, and no work leaves the listings.
  A forced reprocess of a journal re-indexes its works without a gap.
- **M5b:** a backfill slice from the free OpenAlex snapshot imports the registered journals' works with
  `origin = BACKFILL`, spends nothing on OpenAlex, and a re-run imports nothing new.

The full backfill (4–5M works) is for the GBIF VM.

## Build and restart (09:50 UTC)

- At `0226590`, after the final review's fix wave, `mvn -B -DskipTests clean install` was run. Jars were copied to
  `data/run/`, and the logs were kept as `indexer-11.log` and `web-10.log`.
- Flyway applied V8 to V13 to the M4 dev database (43,302 indexed works) in 9 s.
- `FinderRollout` recorded the new configHash as the baseline and reprocessed nothing.
- All 43,302 works were placed in 316691 (`placed_release`), and 316691 was `CURRENT`.

## Switch 316691 → 315557 (admin `XR_SWITCH {"key":315557}`, 09:51 UTC)

| Step | Result |
|---|---|
| Pre-match | 215,144 distinct (name, hints) keys matched against 315557 (COL26.6 XR) on the public API, unthrottled, 8 threads: 14 minutes |
| Gate | 681 of 186,191 matched keys lost (0.4%, limit 5%): flipped at 10:06 |
| Re-place | all 43,302 works in 315557 by 10:11, about 5 minutes |
| Finish | `CURRENT`. Follows on Carabidae `8KTL7`, Coleoptera `C2L` and `RN` stay `OK`. `REBUILD_STATS`, then the rescore chain: 7 `EVALUATE_HITS`, no new hit |

During the switch, the watch (every 2 minutes) showed `/api/works` total 43,302 throughout, and hits 235 throughout.
`GET /api/xr` answered 315557 after the flip. The Carabidae taxon page lists 71 works.

## Switch back by `XR_CHECK` (10:12 UTC)

- `XR_CHECK`, enqueued by hand, saw 3LXR = 316691 ≠ 315557 and enqueued `XR_SWITCH {"auto":true}`.
- **Restart during the switch:** the indexer was restarted during the pre-match (with the fix below). The switch
  resumed from its stored cursor.
- Gate: 1,350 of 186,860 keys lost (0.7%).
- 316691 was `CURRENT` again at 10:21. All 43,302 works are placed in 316691, hits are still 235, and no XR task ended
  `DEAD`. Task totals for both switches: 216 `XR_PREMATCH`, 176 `XR_REPLACE`, 2 `XR_GATE`, 2 `XR_FINISH`.
- No `name_match` rows were pruned: only the two releases exist.

## A bug found on the way (fixed)

`RESCORE_SALIENCE`, from the stats rebuild after the switch, failed on a checklist work with about 25,000 names:
"PreparedStatement can have at most 65,535 parameters". This bug dates from M3: the same task had already ended
`DEAD` in the night run at 04:46. The fix, e336511, binds the salience update as arrays. The indexer was restarted with
it at 10:15, and the retried rescore succeeded.

## Reprocess (journal 59, 40 works; `REPROCESS_NAMES {"source":59,"force":true}`, 10:21 UTC)

- All 40 works were re-indexed: new `indexed_at`, 41 `FIND_NAMES`, 40 `MATCH_NAMES`, 40 `EVALUATE_HITS`.
- The count of listed works of the journal never dropped below 40 (checked every 5 s).
- One `FULLTEXT` work whose closed text had left the spool was re-fetched through a free route (one `RESOLVE_CONTENT`
  with `freeOnly`).
- No new hit (235).

## Backfill slice (`BACKFILL {"fromYear":2000,"toYear":2026,"files":3,"fromFile":1895}`, 10:22 UTC)

- **Snapshot:** 2026-10-14. The files `updated_date=2026-10-14/part_0157…0159` (about 2 GB gzip) were streamed in
  70 seconds with no local copy.

| File | Records read (= manifest) | Kept | Works created |
|---|---|---|---|
| 1895 | 316,757 | 1,259 | 1,209 |
| 1896 | 316,032 | 1,347 | 1,296 |
| 1897 | 315,805 | 1,307 | 1,242 |

- **Kept and created:** 0.41% of the records belong to registered CORE/BROAD journals, 2000 to 2026, articles and
  reviews. 166 of them were already in the index and were left untouched. New `BACKFILL` works: 3,747.
- **Spend:** OpenAlex `api_usage` for the day stayed at 7,453 calls and $0.0233 before and after. The backfill makes no
  OpenAlex API or content call.
- **Pipeline:** all 3,747 new works reached `INDEXED` by 11:50 UTC, 88 minutes after the import, about 40 works a
  minute with 8 workers:
  - Text level: 1,020 full text (Europe PMC 953, Pensoft 67), 2,726 abstract only, 1 without text.
  - 2,820 works have names.
  - OpenAlex `api_usage` stayed at 7,453 calls and $0.0233 for the day.
- **Re-run:** the second identical `BACKFILL` (run 2) read the same 3 files and created 0 works.
- **Hits:** 72 new hits, all for backfilled papers and all for the two follows that have an early `since_year`:
  Coleoptera (since 2020) 35, `RN` (since 2000) 37. ADR-0006 intends this ("a follow with an early `since_year` gets
  the backfill's papers of its taxon"). Default-window follows got none.

## Throughput measurement (2026-11-17/18)

Two more 3-file slices were run with `task.started` (V14, 0aecb41) recording each attempt's run time. Both used 8
workers and the in-process finder.

| Slice | Works | Import → all `INDEXED` | Works/min | Workers busy (avg) |
|---|---|---|---|---|
| Files 1892–1894, 2 s poll only | 3,597 | 80 min | 45 | 2.6 of 8 |
| Files 1889–1891, refill on finish (267b435) | 3,620 | 21 min | 170 | 7.8 of 8 |

Run time per task in the second slice:

| Stage | Median | p90 | Total worker time |
|---|---|---|---|
| `RESOLVE_CONTENT` | 0.68 s | 9.4 s | 151 min |
| `MATCH_NAMES` | 0.01 s | 0.22 s | 9 min |
| `FIND_NAMES` | 0.01 s | 0.12 s | 3 min |
| `EVALUATE_HITS` | <0.01 s | <0.01 s | 0.2 min |
| `BACKFILL_FILE` (about 670 MB each) | 77 s | 82 s | 4 min |

- **The first slice was slow because of the dispatcher.** It only claimed tasks in its 2-second poll, so workers sat
  idle after short tasks. Refilling a worker as soon as its task finishes (267b435) made the pipeline 3.7 times faster.
- **Content resolution is now 90% of the work.** The second slice made about 5.7 Europe PMC calls a second, which is at
  the 200 ms per-host interval. More workers would wait in that host limiter, so about 170 works a minute is the
  ceiling with the current Europe PMC interval.
- **What that means for the GBIF backfill:** about 2M works at 170 a minute take about 8 days. Going faster needs a
  shorter Europe PMC interval, which is a politeness decision, or fewer Europe PMC searches per work.
- **Follow-ups the dispatcher review raised for GBIF:**
  - The claim index does not contain `type`. Run EXPLAIN on a claim while a large backlog of a type without a handler
    sits at a higher priority.
  - Refills share the single scheduler thread with the heartbeat and the crons while the database is stalled.

## For the deployment stage (GBIF VM)

- **Backfill size:** 0.41% of the last three files were kept. Extrapolated over 465M records, the backfill is about
  2M works with the current registry, below the 4–5M estimate. The recent partitions are not representative, so the
  real number is to be measured on the VM.
- **Streaming:** 3 files in 70 s on this connection. Streaming all 1,898 files is a matter of hours.
- **Content throughput:**
  - Content resolution, not streaming, is the long part: about 40 works a minute with 8 workers and the per-host
    intervals. That is weeks for 2M works.
  - Size the workers and check the Europe PMC and Pensoft intervals before the full run.
  - Siblings share a `run_after`, so a file's works are resolved breadth-first before their names are found.
  - Closed full text waits in the spool until `FIND_NAMES`. The 14-day spool purge must not overtake that.
- **CLB load:** the pre-match of about 200k keys took 14 minutes, about 250 calls a second against the public CLB API.
  At 4–5M works, expect a few million keys.

**Verdict:**
- **M5a: met.** Two switches and a forced reprocess caused no hit, and no work left the listings.
- **M5b: met.** The slice used the free snapshot only, filtered correctly, left existing works untouched and spent
  nothing on OpenAlex. Every imported work reached `INDEXED` through free routes, and a re-run created nothing.
