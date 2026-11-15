# Finder: BOLD BIN and UNITE SH identifiers — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The finder detects BOLD Barcode Index Numbers and UNITE Species Hypothesis codes by regex and returns them as mentions of a new kind, `OTU`.

**Architecture:**
- A new `CandidateGenerator` in finder-core finds the identifiers. Its candidates skip the parser and the judge: they are certain.
- They come out as `Mention`s with `kind = OTU`, `hints.scheme` and a normalised `name`.
- The scorer keeps OTU mentions out of name precision and recall, so silver and curated baselines stay comparable, and counts them separately.

**Tech Stack:** finder-api, finder-core and eval, as in M1.

**Spec:** `docs/design.md` §4 (Name finder) and Evaluation. This plan adds Markus's idea of 2026-11-15: "Detect also bold bin numbers and unite sh codes. These are very unique and easy to spot by regex only."

**Branching:** work directly on `main`.

## Global Constraints

- **Offsets** are Unicode code points into `Block.text`, end exclusive. A mention's span is exactly the identifier text. For the UNITE DOI form, the span covers only the `SH…` part.
- **Formats:**
  - BOLD BIN: `\bBOLD:\s?[A-Z]{3}[0-9]{4}\b`. The match is case-sensitive. The normalised name drops any space: `BOLD:AAB2345`.
  - UNITE SH: `\bSH[0-9]{6,7}\.[0-9]{2}FU\b`, case-sensitive, used as the name unchanged. It is also found inside `10.15156/BIO/SH…` DOIs.
- **Contract:**
  - `NameKind` gains `OTU`, and `finder-api.yaml` adds `OTU` to the kind enum.
  - `hints.scheme` is `BOLD_BIN` or `UNITE_SH`.
  - `rank`, `authorship` and `qualifier` stay absent.
  - The change is additive, and the golden contract example stays valid.
- **Pipeline:**
  - OTU candidates are certain and get confidence 1.0, in every block type.
  - They never go to the parser, the judge, abbreviation expansion or act tagging.
  - They do not overlap name candidates: the regex excludes letters before and after the identifier.
- **configHash** changes, because the generator list changes. Add `OTU` to the default `enabledGenerators` in `pipeline-defaults.json`.
- **Eval:**
  - `Scorer` excludes predicted OTU mentions from name P/R/F1 and from main subject.
  - Reports gain `otuMentions` (count) and `otuByScheme`.
  - `eval/GUIDELINES.md` says BINs and SHs are **not** tagged as `Name`.
- **Commits:** conventional style, no attribution lines.

## Review Focus

1. **Identifiers glued to punctuation or brackets,** for example "(BOLD:AAB2345)", "BOLD:AAB2345;", "SH1556109.08FU." at a sentence end. The span excludes the punctuation. Pinned in Task 1 (`identifiersInsidePunctuation`).
2. **Look-alikes.** These must **not** match:
   - "BOLD:AA12345";
   - "bold:AAB2345";
   - "SH1556109.08";
   - "SH1556109.08FUN";
   - "XSH1556109.08FU".

   Pinned in Task 1 (`lookAlikesDoNotMatch`).
3. **The UNITE DOI** `https://doi.org/10.15156/BIO/SH1556109.08FU`: one OTU mention, spanning only `SH1556109.08FU`. Pinned in Task 1 (`uniteDoiYieldsTheShCode`).
4. **A block where a BIN and a binomial are adjacent,** for example "Carabus sp. BOLD:AAB2345". The binomial/genus handling must not change. Pinned in Task 1 (`binNextToANameLeavesTheNameAlone`).
5. **Eval comparability.** Re-running the committed Pensoft dev pipeline report gives identical name P/R/F1, unless the corpus contains identifiers. Pinned in Task 1, Step 6.

---

### Task 1: OTU identifier generator, contract kind and eval handling

**Files:**
- Modify:
  - `finder-api/src/main/java/org/catalogueoflife/nametracker/finder/api/NameKind.java`
  - `finder-api/src/main/resources/finder-api.yaml`
  - `finder-core/.../pipeline/{PipelineNameFinder.java, PipelineConfig.java (if the generator set is an enum or a list), CandidateMerger.java (only if needed)}`
  - `finder-core/src/main/resources/.../pipeline/pipeline-defaults.json`
  - `eval/.../score/{Scorer.java, EvalReport.java, ReportWriter.java}`
  - `eval/GUIDELINES.md`
  - `CLAUDE.md` (one line under the finder modules)
- Create: `finder-core/.../pipeline/OtuIdentifierGenerator.java`
- Test:
  - `finder-core/src/test/.../pipeline/OtuIdentifierGeneratorTest.java`
  - `PipelineNameFinderTest` (new cases)
  - `eval/src/test/.../score/ScorerTest` (new case)
  - `finder-api` contract test (if it enumerates kinds)

**Interfaces:**
- **Consumes:** the `CandidateGenerator` SPI and the `Generator` enum, the `Candidate` record, `CodePoints`, and the certain path in `PipelineNameFinder` (read them first).
- **Produces:**
  - `NameKind.OTU`;
  - `Generator.OTU`, which joins the generator enum and comes before `GNFINDER` in merge priority, so that its name wins on an exact span;
  - `OtuIdentifierGenerator implements CandidateGenerator`, giving `List<Candidate>` per block with the normalised name and `hints.scheme`;
  - `EvalReport.otuMentions` (int) and `otuByScheme` (`Map<String,Integer>`).

**Steps:**

- [ ] **Step 1: Write the failing tests** in `OtuIdentifierGeneratorTest`:
  - `findsBoldBin`: in "specimens of BIN BOLD:AAB2345 were", one candidate whose span is the identifier and whose name is `BOLD:AAB2345`.
  - `normalisesSpacedBin`: "BOLD: ACF1234" gives the name `BOLD:ACF1234`, and the span includes the space.
  - `findsUniteSh`: "SH1556109.08FU" gives `UNITE_SH`.
  - `identifiersInsidePunctuation`: "(BOLD:AAB2345)", "BOLD:AAB2345;" and "SH1556109.08FU." give spans without the punctuation.
  - `lookAlikesDoNotMatch`: "BOLD:AA12345", "bold:AAB2345", "SH1556109.08", "SH1556109.08FUN" and "XSH1556109.08FU" give none.
  - `uniteDoiYieldsTheShCode`: `https://doi.org/10.15156/BIO/SH1556109.08FU` gives one, spanning `SH1556109.08FU`.
  - `codePointOffsets`: a block starting with "𝔄 " (a supplementary character) gives offsets in code points.

  In `PipelineNameFinderTest`:
  - `otuMentionsAreCertainAndUnjudged`: kind OTU, confidence 1.0, `hints.scheme` set, no rank, also inside a REFERENCE block.
  - `binNextToANameLeavesTheNameAlone`: "Carabus sp. BOLD:AAB2345" still yields the Carabus mention as before, plus one OTU mention.

  In `ScorerTest`:
  - `otuMentionsAreNotScoredAsNames`: an extra predicted OTU mention leaves name precision unchanged, and `otuMentions` = 1.
- [ ] **Step 2:** Run `mvn -B -pl finder-api,finder-core,eval -am test -Dtest='OtuIdentifierGeneratorTest,PipelineNameFinderTest,ScorerTest'`. Expected: FAIL (classes and kind missing).
- [ ] **Step 3: Implement** the generator, the kind, the merge priority, the certain path, the defaults JSON, the scorer exclusion and counts, and the report fields.
  - Update `finder-api.yaml`.
  - Update `GUIDELINES.md` with one short subsection: "BOLD BINs and UNITE SH codes are identifiers, not names: do not tag them."
  - Update `CLAUDE.md`.
- [ ] **Step 4:** Run `mvn -B -pl finder-api,finder-core,finder-service,eval -am verify`. Expected: PASS, including the golden contract test and finder-service.
- [ ] **Step 5: Check the corpus.**
  - Count the identifiers in the Pensoft corpus: `zcat eval/corpus/pensoft/gold.jsonl.gz | grep -oE 'BOLD:\s?[A-Z]{3}[0-9]{4}|SH[0-9]{6,7}\.[0-9]{2}FU' | wc -l`.
  - Record the count in the report.
- [ ] **Step 6: Re-run the Pensoft dev pipeline report** in mode A STRICT with gnfinder up (`docker compose up -d gnfinder`) and `--dictionary eval/work/dictionary-316691.bin`.
  - Name P/R/F1 must equal the committed dev report's.
  - Commit the refreshed report: the config hash changes, and the report now has an OTU count.
  - Stop gnfinder afterwards.
- [ ] **Step 7:** Commit `feat(finder): BOLD BIN and UNITE SH identifiers as OTU mentions`.
