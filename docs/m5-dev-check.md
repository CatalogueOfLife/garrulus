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
- **Pipeline:** the new works entered the content queue at `BACKFILL` priority. 144 were resolved through free routes
  during the check: 26 full text, 118 abstract only. The rest wait behind an older content backlog of the same
  priority, about 19,600 tasks at about 40 a minute. Their indexing is to be confirmed at the M2 dev-run check, about
  2026-11-22.
- **Re-run:** a second identical `BACKFILL` was enqueued. It sits behind the same backlog, because the queue orders by
  priority and then `run_after`. That a re-run creates nothing is covered by `BackfillIT.rerunImportsNothingNew`. The
  dev re-run will confirm it once the queue reaches it.

## For the deployment stage (GBIF VM)

- **Backfill size:** 0.41% of the last three files were kept. Extrapolated over 465M records, the backfill is about
  2M works with the current registry, below the 4–5M estimate. The recent partitions are not representative, so the
  real number is to be measured on the VM.
- **Streaming:** 3 files in 70 s on this connection. Streaming all 1,898 files is a matter of hours.
- **Content throughput:**
  - Content resolution, not streaming, is the long part: about 40 works a minute with 8 workers and the per-host
    intervals. That is weeks for 2M works.
  - Size the workers and check the Europe PMC and Pensoft intervals before the full run.
  - Breadth-first ordering is a second reason: siblings share a `run_after`, so the follow-ups of a file wait behind all
    content tasks of the backfill.
  - Closed full text waits in the spool until `FIND_NAMES`. The 14-day spool purge must not overtake that.
- **CLB load:** the pre-match of about 200k keys took 14 minutes, about 250 calls a second against the public CLB API.
  At 4–5M works, expect a few million keys.

**Verdict:**
- **M5a: met.** Two switches and a forced reprocess caused no hit, and no work left the listings.
- **M5b: met for the import.** Free snapshot only, correct filter, no OpenAlex spend, existing works untouched. The
  slice's indexing finishes with the content backlog and is to be confirmed later.
