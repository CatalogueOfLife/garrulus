# Finder-tuning preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare the final finder-tuning stage so that it can run as soon as Markus has fixed the curated gold by hand. Everything that needs no correct gold is done now:
- a provisional gold copy, for wiring only;
- the importer's genus-rank fix;
- the TEI tokenisation clean-up, measured on Pensoft mode B;
- a salience RESCORE task in the indexer;
- a salience tuning command in eval.

**Architecture:**
- **Salience moves to finder-core.** The pure salience scoring, and the aggregation of a name's mentions into salience inputs, move from `indexer` to `finder-core` (package `org.catalogueoflife.nametracker.finder.salience`). The indexer and eval then share one implementation.
- **The provisional gold lives only in `eval/work/`** and is never committed.

**Tech Stack:** Java 25, Maven, Spring Boot 4.1 (indexer), picocli (eval), JUnit 5, Testcontainers.

**Spec:**
- `docs/design.md`, §7 (salience) and the Evaluation section.
- ADR-0002 (GROBID TEI has no italics) and ADR-0005 (ubiquity cold start, RESCORE).
- Markus' instructions of 2026-11-16:
  - He fixes the gold import by hand tomorrow.
  - Proceed on other tasks.
  - For wiring tests, copy his broken export and remove the broken acts.

**Branching:** work directly on `main`, as Markus asked.

## Global Constraints

- **Never commit** `eval/work/`, `eval/reports/OVERLAP/`, provisional gold files, or reports computed on the provisional gold. Reports on the Pensoft corpus are committed as usual (`eval/reports/`).
- **Do not write `eval/corpus/curated/gold.jsonl.gz`.** Markus writes it after his manual fixes.
- **No tuned values are committed** for salience weights or threshold. Tuning on the real gold is the final stage, and Markus decides.
- **Never touch local ports 8080/8081** or the dev run's processes (18080/18081) unless a task says so.
- **Commits:** conventional style, explicit paths, no tool attribution lines.

## Review Focus

1. **Moving Salience changes no score.** The indexer's existing `SalienceTest` and `WorkIndexerIT` must pass unchanged, with only imports updated. Pinned in Task 3.
2. **The TEI clean-up only removes GROBID artefacts.** It never joins two real words or splits a name. Pinned in Task 2 by tests on real TEI snippets, and by the mode B report.
3. **RESCORE is idempotent and does not re-match.** It changes salience, main_subject and the work filter columns only, and makes no CLB call. Pinned in Task 4.

---

### Task 1: Genus rank fix in ls-import, and the provisional gold

**Files:**
- Modify: `eval/.../curated/LabelStudioImporter.java` (the context ranks inference) and its tests.
- Create: `eval/src/main/java/.../curated/ProvisionalGold.java` plus a CLI subcommand `ls-provisional`.

**Steps:**
- [ ] **Genus first.** When inferring context ranks, look the uninomial up as a genus in the dictionary's genus set before the uninomial-by-rank lookup and the suffix rules. "Nitrospinaceae: Nitrospina" must give FAMILY and GENUS. Test `genusFromDictionaryBeatsSuffix`.
- [ ] **The `ls-provisional` command.** It takes `--export`, `--out-export` (default `eval/work/ls-export-provisional.json`), `--dictionary` and `--out` (default `eval/work/curated-provisional/gold.jsonl.gz`).
  - It copies the export and removes every Act region (with its choices and relations) that inference cannot resolve.
  - It removes every Authorship region the authorship rule rejects.
  - It then runs the normal import with inference into `--out`, and copies `manifest.tsv` next to it, so `eval/work/curated-provisional/` is a corpus directory.
  - It prints what it removed.
  - Test it on a small synthetic export.
- [ ] **Run it** on `eval/work/ls-export-curated.json`. Then run `run --finder gnfinder` and `run --finder pipeline --corpus eval/work/curated-provisional --split dev --mode A` to show the curated path works end to end. Delete the reports these runs write. Do not commit them.
- [ ] **Commit** the code and tests only: `feat(eval): ls-import ranks genera from the dictionary; ls-provisional for wiring tests`.

### Task 2: TEI tokenisation clean-up

This was carried from M2: GROBID puts a space before commas (`Hohenheim , Institut`) and leaves none after some full stops (`tray.In`).

**Files:** `finder-core/.../doc/TeiConverter.java` and its tests.

**Steps:**
- [ ] **Space before punctuation.** Remove a space before `,` `;` `:` `)` and `.` when it was introduced by GROBID's token joining, that is, between two separate TEI text nodes or `<lb/>` boundaries. Never remove a space inside the source text of a single node.
- [ ] **Missing space after a full stop.** Insert a space after a sentence-ending `.` that is directly followed by an upper-case letter starting a word, but only at node boundaries. Do not touch abbreviations such as "U.S." or "e.g." when they sit inside one node.
  - Tests use real snippets from cached TEIs in `eval/work/pdf/` (copy small excerpts into test resources).
  - Cover a binomial with an authorship, "Aus bus L., 1758", and a figure reference.
- [ ] **Stray backslashes (Markus, 2026-11-16).** Some names carry stray backslashes from GROBID, for example "Themus \ (Telephorops) \ coelestis" in doi:10.3897/zookeys.340.5470 (the TEI is cached under `eval/work/acts-tei/` or `eval/work/pdf/`).
  - Find where they come from in the TEI: a literal `\`, an escaped character, or a font-mapping artefact.
  - Remove a lone backslash token surrounded by whitespace. Remove a backslash next to a name part when it is a GROBID artefact.
  - The example must come out as "Themus (Telephorops) coelestis".
  - Count how often this happens across the cached TEIs, before and after, and put the counts in the report.
  - Add a test with that snippet.
- [ ] **Measure mode B.** Run `run --finder pipeline --corpus eval/corpus/pensoft --split dev --mode B --cache-only`, plus the gnfinder baseline, before and after the change.
  - Commit the after-reports.
  - Put the before/after F1 in the commit message body.
  - If precision or recall drops by more than 0.5 points, stop and report instead of committing.
- [ ] **Commit:** `fix(finder-core): undo GROBID tokenisation artefacts in the TEI converter`.

### Task 3: Move salience to finder-core

**Files:**
- Move `indexer/.../index/Salience.java` and `SalienceProperties.java` to `finder-core/.../finder/salience/`.
- Create `finder-core/.../finder/salience/SalienceInputs.java`. It is the pure aggregation `static Map<String, Salience.Inputs> of(List<MentionView> mentions, boolean abstractOnly, Map<String, Double> ubiquity)`, keyed by name. `MentionView(String name, String blockType, @Nullable String actType)` is a small record.
- Modify `indexer/.../index/WorkIndexer.java` to use it.

**Steps:**
- [ ] **Move the code without changing behaviour:**
  - The body counts and flags exclude REFERENCE.
  - abstract-only means a non-reference mention.
  - Only body acts give the act weight.
  - maxBodyCount is taken over the work.
  - `SalienceProperties` stays bound through the indexer's `@Value` bean.
- [ ] **Tests:**
  - `SalienceTest` moves with the class.
  - New `SalienceInputsTest` covers the aggregation rules.
  - `WorkIndexerIT` passes unchanged.
- [ ] **Commit:** `refactor: salience scoring and inputs in finder-core, shared by indexer and eval`.

### Task 4: RESCORE_SALIENCE task

**Files:**
- `indexer/.../index/RescoreHandler.java`
- `IndexMapper` (a query for the work's mentions with block types, and a batch update of `work_name` salience/main_subject)
- `RebuildStatsHandler` (enqueue)
- CLAUDE.md and ADR-0005

**Steps:**
- [ ] **Task type `RESCORE_SALIENCE`:**
  - Payload `{"afterId":n,"batch":500}`.
  - It recomputes salience and main_subject of every `work_name` of INDEXED or NOTIFIED works, in id order and in batches, from the stored mentions and the current `name_ubiquity`. It also recomputes `work.codes`, `taxgroups` and `has_acts`.
  - One transaction per work, with `lockWork` first.
  - No CLB call and no change to placements.
  - It enqueues its own continuation with the next `afterId` (dedup key `rescore:<afterId>`) at REPROCESS.
- [ ] **When it runs:**
  - It can be started through the admin task endpoint.
  - REBUILD_STATS enqueues `{"afterId":0}` (dedup `rescore:<date>`) after the ubiquity rebuild, but only when the ubiquity changed. Use a simple check: a row count or checksum compared with the previous run, stored in a small `job_state` key/value table (V6 migration) or in an existing table.
- [ ] **Tests:**
  - `rescoreAppliesNewUbiquity`: a lab organism becomes non-main after ubiquity appears.
  - `rescoreIsIdempotent`
  - `rescoreDoesNotTouchPlacements`
  - `rescoreContinuesInBatches`
- [ ] **Docs:** CLAUDE.md, and ADR-0005 (the cold start is now handled).
- [ ] **Commit:** `feat(indexer): RESCORE_SALIENCE recomputes salience from stored mentions`.

### Task 5: `tune-salience` in eval

**Files:**
- `eval/.../tune/SalienceTuner.java`
- the `tune-salience` CLI subcommand
- tests

**Steps:**
- [ ] **Inputs and objective:**
  - For each document of a curated corpus split, run the pipeline finder (mode A) and build the salience inputs with `SalienceInputs.of` (ubiquity 0, since eval has no index).
  - Grid-search the weights `title`, `abstract`, `act`, `heading`, `share` and `repeat`, and the threshold. Keep `ubiquity` at its configured value; it cannot be tuned without the index.
  - The objective is micro F1 of main-subject names against `GoldDocument.mainSubjectNames()`, after normalising the names as the scorer does.
  - Only documents with at least one main-subject label count.
- [ ] **Output:**
  - Print the best values as `application.yml` overrides, with P/R/F1 and the number of labelled documents.
  - Write a report to `--report` (default `eval/work/salience-tuning.md`, not committed).
- [ ] **Tests:** use a tiny synthetic corpus whose optimum is known.
- [ ] **Wiring check:** run it on `eval/work/curated-provisional` with `--split dev`. It must work and print the counts. Do not commit any values.
- [ ] **Commit:** `feat(eval): tune-salience grid search on curated main-subject labels`.

### Task 6: Hand-over note

- [ ] **Write the hand-over.** Add a "Tuning stage, ready to run" section to `docs/m3-dev-check.md` (or a new `docs/tuning-stage.md`). It gives the exact commands, in order, once Markus has fixed the gold:
  1. `ls-import --out eval/corpus/curated/gold.jsonl.gz`
  2. curated baselines (gnfinder, pipeline; dev and test)
  3. `tune` on curated plus Pensoft
  4. `tune-salience`
  5. copy the overrides
  6. RESCORE in the dev run

  Also list what the provisional run showed, as wiring only.
- [ ] **Commit:** `docs: tuning stage hand-over`.
