# Tuning stage hand-over (2026-11-16)

The finder judge and the salience weights are tuned on the curated gold, which Markus fixes by hand in Label Studio
first. All commands run from the repo root with JDK 25 (`source ~/.sdkman/bin/sdkman-init.sh && sdk env`); run
`mvn -B -DskipTests install` once if `exec:java` cannot resolve modules. Setup:

```
export FINDER_DICTIONARY=$PWD/eval/work/dictionary-316691.bin   # the pinned XR; rebuild with build-dictionary for a new one
docker compose up -d gnfinder                                          # needed by pipeline, gnfinder, tune-salience
```

## 1. Fix the gold in Label Studio

**Status 2026-11-18:** the 53 regions below are resolved. Every Act now has an explicit `act_of` link (37 added by
script in the Cavalier-Smith task, the replacement name below among them, 32 in 20 other tasks, and the Azaptilia link by
hand as one Act over "gen. nov. et sp. nov."), and the importer lets an Authorship
follow its Name across linked Act regions, `*`, `+` and `\`. An export of 2026-11-18 imports without errors (111
documents, 860 names, 109 acts). Still open: more main-subject marks, then the re-export below.

Source: the importer inference report and the `ls-provisional` run. On `eval/work/ls-export-curated.json` the importer
could not resolve 53 regions:

- **2 act links** (add `act_of`):
  - `pensoft:zookeys.1285.182943`: Act `sp. nov.` (region qpthsInf03) to the Name *Azaptilia azapensis* (the Act
    `gen. nov.` between them blocks the inference).
  - `doi:10.1007/s00709-019-01442-7`: Act `replacement name` (zW9Zxx9dgr) to the Name *Deinococcia* (the Act `cl. n.`
    between them blocks it).
- **51 Authorship regions after an Act** (all in `doi:10.1007/s00709-019-01442-7`, the Cavalier-Smith task, e.g.
  `Cavalier-Smith`, `Patel 2012`, `Garrity and Holt 2002`, `Huber & Stetter 2002`). Either delete them, or ask for a
  rule that lets an Authorship follow its Name across one Act region (and `**`); the importer's current rule only allows
  spaces and commas between Name and Authorship, and was not weakened.
- **More main-subject marks would help.** Only 21 labelled documents exist (dev 8 documents / 9 labels, test 12 / 12),
  so salience tuning is not meaningful yet. Mark more.

Then re-export the project to `eval/work/ls-export-curated.json`.

## 2. Import the gold

**Status 2026-11-18:** done. The export of 2026-11-18 (`eval/work/ls-export-curated.json`; the 2026-11-15 one is kept as
`ls-export-curated-2026-11-15.json`) imported without errors: 111 documents, 860 names, 109 acts, 44 context ranks, 21
main-subject names. Inferred: 64 act types (all from their markers, checked), 0 act links, 24 context ranks (the
"also a genus" conflicts all resolved to the higher rank: Hemiptera, Diptera, Siluriformes as ORDER, Ischnocera as
PARVORDER; Teleostei CLASS and Phthiraptera INFRAORDER follow the pinned COL dictionary). The main-subject marks were not
extended, so salience tuning (§5) still rests on 21 labels.

```
mvn -q -pl eval exec:java -Dexec.args="ls-import --export eval/work/ls-export-curated.json --dictionary $FINDER_DICTIONARY --out eval/corpus/curated/gold.jsonl.gz"
```

(`--strict` would infer nothing; do not use it.) All failures are reported together; fix and repeat. Then read
`eval/work/ls-import-inferred.tsv` (docId, region id, kind, text, value, gap): inferred act types, `act_of` links and
context ranks. Look at rows whose gap says `conflict: X is also a genus` (a name with a higher dictionary rank that is
also a genus; the higher rank wins) and at the `gen. nov.` link in zookeys.1285 (species vs genus target). Commit
`eval/corpus/curated/gold.jsonl.gz` (the manifest is already committed).

## 3. Curated baselines (before tuning)

Convention: STRICT is the committed default; OVERLAP reports go to `eval/reports/OVERLAP/` and are not committed.

```
for f in gnfinder pipeline; do for s in dev test; do
  mvn -q -pl eval exec:java -Dexec.args="run --finder $f --corpus eval/corpus/curated --split $s --mode A --match STRICT"
done; done
```

Add `--match OVERLAP --out eval/reports/OVERLAP` runs if wanted (`--out` is the report directory, default
`eval/reports`; without it OVERLAP reports land among the committed ones; the OVERLAP directory is not committed). Commit the new `eval/reports/*.md` and `.json` (STRICT only).
Curated reports have extra `acts`, `contexts`, `authorship` and `mainSubject` rows.

## 4. Judge tuning

```
mvn -q -pl eval exec:java -Dexec.args="tune --corpus eval/corpus/curated --split dev --modes A"
mvn -q -pl eval exec:java -Dexec.args="tune --corpus eval/corpus/pensoft --split dev --modes A,B --cache-only"
```

Mode B needs GROBID and the PDF cache (CLAUDE.md, "Mode B"). Tune on dev only, never test. Decide which corpus (or
combination) drives the choice; the grid is `--grid`, `--thresholds`, `--top`. Copy the printed overrides into
`finder-core/src/main/resources/org/catalogueoflife/nametracker/finder/pipeline/pipeline-defaults.json`. This changes
the finder `configHash`, i.e. a new finder version. Only works found after the next deploy get the tuned finder: a
repeat `FIND_NAMES` on an unchanged document is a no-op even under a new finder version (ADR-0005, design.md), so
re-finding existing works needs M5's reprocessing (the force flag). Re-run section 3
for the pipeline (dev and test), commit the reports, and quote the **test** numbers.

## 5. Salience tuning

The baseline is the `nametracker.salience` block of `indexer/src/main/resources/application.yml` (`--config` to point
elsewhere; the built-in defaults if it cannot be read; the run prints which one it used). Tune on dev, then evaluate
exactly the dev values on test without searching:

```
mvn -q -pl eval exec:java -Dexec.args="tune-salience --corpus eval/corpus/curated --split dev --report eval/work/salience-tuning.md"
mvn -q -pl eval exec:java -Dexec.args="tune-salience --corpus eval/corpus/curated --split test --eval-only --set title=0.4,abstract=0.2,act=0.35,heading=0.15,share=0.25,repeat=0.15,threshold=0.35"
```

`--set` takes `nametracker.salience.*` keys without the prefix (title, abstract, act, heading, share, repeat, ubiquity,
ubiquity-full-share, threshold) on top of the baseline and prints P/R/F1 of those values. The printed overrides carry
full precision, so what you copy is what was evaluated. Copy them into the `salience:` block of
`indexer/src/main/resources/application.yml`. Then rescore the dev run.

The dev run's `data/run/indexer.jar` predates `RESCORE_SALIENCE`, migration V6 and the salience move, so redeploy it
first (indexer 18081, web 18080; never 8080/8081):

1. `mvn -B -DskipTests clean install`.
2. Keep the old log: `mv data/run/indexer.log data/run/indexer-N.log` with the next free N.
3. Stop only the pid in `data/run/indexer.pid` (`kill $(cat data/run/indexer.pid)`), nothing else.
4. `cp indexer/target/nametracker-indexer-0.1.0-SNAPSHOT.jar data/run/indexer.jar` (likewise the web jar to
   `data/run/web.jar` if web changed; web then needs its own restart).
5. Restart with the same env as `docs/m2-dev-run.md` (`NAMETRACKER_INDEXER_PORT=18081`, ...). The weights come from the
   `application.yml` inside the jar, or from env overrides by Spring's relaxed binding of `nametracker.salience.*`:
   `NAMETRACKER_SALIENCE_TITLE`, `_ABSTRACT`, `_ACT`, `_HEADING`, `_SHARE`, `_REPEAT`, `_UBIQUITY`,
   `_UBIQUITY_FULL_SHARE`, `_THRESHOLD` (`application.yml` has no placeholders for them).

Warning: on the first deploy of V6 every `scored_share` is null, so the first nightly `REBUILD_STATS` enqueues a
targeted rescore of nearly all works. That is fine, but expect it.

`ADMIN_TOKEN` is in `.env`; never print it:

```
set -a; . ./.env; set +a          # in bash (zsh cannot source this .env)
curl -s -X POST localhost:18080/api/admin/tasks -H "X-Admin-Token: $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"RESCORE_SALIENCE","payload":{"all":true},"priority":"LIVE","dedupKey":"rescore-salience:manual"}'
```

The payload also takes `afterId` and `batch` (default 500), both optional. Continuations use the dedup key
`rescore:all:<lastId>` (or `rescore:changed:<lastId>` for targeted runs), so an all run stays an all run. Watch
`GET /api/admin/status`.

## 6. Open decisions for Markus

a. GROBID artefacts inside single text nodes (`Hohenheim , Institut`, `tray.In`) are untouched by the boundary-only
   rule of the converter: fix inside nodes too, or leave.
b. Finder false positives seen in the dev run (*Colfax*, *Microbiota*, *Karnataka*, *Prosternum*, *Meigen*): add as
   named negatives for tuning (and the judge) or accept.
c. Re-running `curated-add-acts` would change the text of 4 ACTS documents, because the converter changed since; the
   manifest text hashes would then no longer match the annotated tasks. Keep the current picks.
d. The `clb 1.7.1-SNAPSHOT` pin must be replaced by a release before any deploy.
e. A local CLB bundle for 316691 (set `NAMETRACKER_CLB_MATCH_BASE_URL`) for matching without the remote API.

## 7. Provisional wiring results (NOT results)

From `ls-provisional` (the 2 acts and 51 authorships above removed; 111 documents, 860 names, 108 acts; dev 31
documents, 6 acts), mode A, dev, STRICT. Only shows that the pieces are wired; the gold is not final, the reports were
deleted, nothing from this is to be quoted.

- names: pipeline P .918 R .896 F1 .907; gnfinder P .522 R .844 F1 .645.
- acts F1: pipeline 1.0 (6 of 6), gnfinder .600. contexts F1: pipeline .182, gnfinder 0. mainSubject F1: pipeline
  .308, gnfinder .216. The `run` report's mainSubject row is a title+act heuristic, not salience, hence .308 here
  against `tune-salience`'s .632 below.
- `tune-salience` on the provisional gold: dev F1 .632 to .706, test .667 to .889, on too few labels (21 documents);
  the weights are not committed.
