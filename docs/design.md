# Garrulus — system design (v0.1, 2026-11-14)

> **Naming (decided 2026-11-14).** The public name is **Garrulus**, after the European jay, *Garrulus glandarius*.
> It is used for the UI, logo, page titles, emails, feeds, public URLs and the outgoing User-Agent.
>
> The internal id stays `nametracker`:
> - Java packages `org.catalogueoflife.nametracker.*`
> - Maven artifactIds `nametracker-*`
> - the database
> - systemd units `col-nametracker-*`
> - the repository
>
> The brand (logo, jay colour palette, Mantine theme) is in `docs/brand/README.md`.

## Context

uBio's uBioRSS once followed journal feeds, found scientific names in new articles and let people subscribe to taxa. Nothing like it runs today. ChecklistBank has no free-text name finding at all (the backend has matching, parsing and a Crossref DOI resolver, but no text mining). **Garrulus** (internally `nametracker`) is a new, standalone CoL application that:

1. continuously follows the journals of biodiversity science,
2. fetches article metadata and abstracts, and open-access full text where available,
3. finds every scientific name in that text, together with nomenclatural acts and the article's own (verbatim) classification,
4. organises those names on the latest COL eXtended Release (XR),
5. lets users follow taxa, journals or names and be notified of new hits,
6. builds a lasting, public **name–literature index**.

This document is the approved design (2026-11-14). Later decisions go into `docs/adr/`. The first implementation plan covers milestones M0–M1 (see *Getting started*).

## Goals, non-goals, anti-goals

**Goals (both equally important, decided by Markus)**
- **Alerts for researchers:** follow a COL taxon (including all descendants), a journal or a name, and get email digests or Atom feeds of new articles. New names (sp. nov. and the like) in a followed group are flagged as a special kind of hit.
- **Name–literature index:** for every name, show where and when it appears, linked to its COL XR taxon, while keeping the article's verbatim name and classification. The index is public and needs no login.

**Scope (decided)**
- Literature: **biodiversity science broadly**. That is about 2,000 journals and about 300k articles a year across taxonomy, systematics, ecology, zoology, botany, mycology, microbiology, parasitology and paleontology. Clinical medicine and agronomy are excluded unless a journal proves dense in names.
- Backfill: **from 2000 to now** at launch. The backfill must be a re-runnable, year-range-parameterised job, so that a later run can go back further (pre-2000, possibly with BHL).

**Later goal (confirmed):** a portal-components "Literature" widget on COL taxon pages, fed by the index (M6).

**Non-goals (for now)**
- Editor work queues or gap analysis for COL. New names and acts are recorded, so this stays possible later, but no curator tooling is built.
- Being a nomenclator. ZooBank, IPNI and Index Fungorum remain authoritative for nomenclatural acts.
- Crawling closed-access publisher websites.

**Anti-goals (failure even if it "works")**
- Alerts full of noise: hits from reference lists, common-word "genera" ("Major", "Are"), or every *E. coli* mention.
- An LLM inventing or "modernising" names. Every stored name must point at an exact stretch of source text.
- Redistributing copyrighted full text.
- Re-notifying users about old papers after an XR or finder update, or about backfilled papers.

## Constraints

- **Free by default (decided by Markus).** Nothing may depend on a paid service, unless the full backfill would cost less than $100 in total.
  - Every paid or metered integration has its own config switch, so it can be turned off. With all switches off, the system still works end to end.
  - **Paid or metered integrations and their defaults:**
    - OpenAlex calls beyond the free daily allowance: off. A hard cap sits at the free allowance (`openalex.maxDailyUsd`, default = free allowance).
    - OpenAlex content (TEI/PDF) downloads: on, but only from whatever is left of the free allowance after harvesting. `openalex.content.enabled` turns them off.
    - LLM judge: off (`finder.judge.llm.enabled=false`).
    - Crossref Plus and OpenAlex paid plans: not used.
  - **Backfill cost:** $0. The OpenAlex snapshot and the PMC bucket are free AWS open data; Europe PMC and Pensoft are free.
- JDK 25 everywhere (required by `name-parser-rust` via FFM).
- Runs on GBIF infrastructure like Blixa. No secrets in the repo.

## Decisions taken in the conversation

| Topic | Decision |
|---|---|
| Purpose | Alerts for researchers + public name–literature index |
| Breadth | Biodiversity broadly, tiered (core taxonomy journals get the most full-text effort) |
| Backfill | 2000→now; job re-runnable for earlier years |
| Architecture | **A: standalone on Blixa's stack.** Spring Boot 4.1, Java 25, MyBatis, Flyway, Postgres 17, React + Mantine. gnfinder and GROBID run as helper services; matching via the ChecklistBank (CLB) API. |
| App split | **Two Java apps sharing one database:** `web` (user-facing, mostly read, always on) and `indexer` (harvesting, analysis, matching; can be stopped separately and more often for maintenance) |
| Finder | **gnfinder + own stages**, behind a clean abstraction so an alternative implementation can be developed and swapped in |
| Search filters | From the start: nomenclatural code, taxonomic group (CLB `TaxGroup` vocabulary), journal categorisation (see §1, §5, §9) |
| Gold standard | About 100 hand-checked documents (machine pre-tagged, corrected by a person); Pensoft TaxPub as a secondary silver set |
| Paid services | Not relied on unless the full backfill would cost < $100 total. Every metered integration has a switch to turn it off; the default stays within free allowances (see *Constraints*). |

**Approaches considered**
- B (module inside the CLB backend) was rejected: it would tie a crawler and user alerts to CLB's blue-green release cycle and production DB.
- C (Python pipeline plus web app) was rejected: a new stack with little reuse. If ML work later needs Python, only the judge becomes a Python helper service.
- For the finder, "own from scratch" and "LLM-first" were rejected. The first rebuilds what gnfinder already does well. The second invents names, is not reproducible and would cost about $6k/yr plus the backfill.

## System overview

```
                 ┌────────────── Source registry (journals, tiers) ◄── OpenAlex sources/topics, curation, discovery
                 ▼
 Harvesters ──► Works (metadata, abstract) ──► Content acquisition ──► Document model (blocks + italics + offsets)
 OpenAlex daily window            │            Pensoft TaxPub XML                 │
 Crossref index-date              │            Europe PMC / PMC JATS              ▼
 Snapshot backfill                │            OpenAlex TEI ($) / local GROBID   NameFinder (SPI)
                                  │            abstract fallback                  │  default: PipelineNameFinder
                                  │                                               │  (gnfinder + dict + rules + acts + context + judge)
                                  ▼                                               ▼
                     Postgres 17: works, mentions, name strings, contexts, matches, users, subscriptions, hits, tasks
                                  ▲                                               │
                     COL XR: release tracker + Matcher (CLB API / bulk job) ◄─────┘ placement → taxon_path[]
                                  │
              Hit evaluator → email digests, Atom feeds     REST API + React UI (search, taxon/name/article/journal pages)
```

### Two applications, one database (decided by Markus)

The Java side is split into **two Spring Boot applications** that share one Postgres database. They never call each other; they communicate only through the database.

```
┌─────────────── indexer  (stoppable any time: maintenance, backfills, finder/XR upgrades) ───────────────┐
│ source registry · harvesters · content acquisition · document model · NameFinder (in-process or remote) │
│ matcher (CLB) · placement, code, TaxGroup, salience · hit evaluator · XR lifecycle · backfill ·         │
│ reprocessing · nightly stats (taxon_stats, name_ubiquity, journal categorisation) · task workers        │
└───────────────────────────────┬────────────────────────────────────────────────────────────────────────┘
        writes index + hits,    │    consumes `task` rows
                     Postgres 17  — one schema, defined once in the shared `store` module
        reads index,            │    writes users, subscriptions, deliveries, enqueues `task` rows
┌───────────────────────────────┴────────────────────────────────────────────────────────────────────────┐
│ web  (always on, mostly read): public REST API · Atom feeds · React UI · ORCID/magic-link login ·      │
│ subscriptions · digest delivery (email) · admin screens (pipeline status, source tiers → enqueue tasks)│
└────────────────────────────────────────────────────────────────────────────────────────────────────────┘
helper services (gnfinder, GROBID) are used by the indexer only
```

- **`web`** keeps working while `indexer` is down. The index simply stops growing.
  - Digests still go out with whatever hits exist.
  - Subscriptions can still be created.
  - Admin actions (promote a source, start a backfill, reprocess) are written as `task` rows and wait until the indexer runs again.
- **`indexer`** has no public HTTP, only actuator and health endpoints. It can be redeployed, restarted or stopped on its own schedule. All its work is queued and resumable, so stopping it mid-run loses nothing: a task that was running is retried.
- **Table ownership:**
  - The indexer writes the index tables (`source` metadata, `work`, `content`, `mention`, `work_name`, `name_*`, `classification_context`, `xr_release`, stats) and `hit`.
  - `web` writes `app_user`, `subscription`, delivery state, `source` tier/curation fields, and inserts `task` rows.
  - Each table has one writer per column group.
- **Shared `store` module:** Flyway migrations, MyBatis mappers and model classes, used by both apps.
  - The indexer applies migrations on startup, since it is the app restarted most often. `web` only validates the schema.
  - Migrations must be backward compatible (expand/contract), so a running `web` keeps working while the indexer migrates.
  - Builder's call: this could instead be an explicit Flyway deploy step.
- **Scheduled jobs** run in a single instance, with ShedLock on Postgres.
  - indexer: harvest, sweeps, discovery, XR check, nightly stats
  - web: hourly digest delivery
- **Later, if indexer writes slow down reads:** point `web`'s index reads at the Postgres standby, as ChecklistBank does.

At about 1,000 new articles a day plus a backfill batch, both apps and the helper services fit on one VM. Every external dependency sits behind an interface with rate limiting.

## Components

### 1. Source registry (journals)

- Table `source`:
  - identity: OpenAlex id, ISSN-L, ISSNs, title, publisher, `is_oa`, DOAJ flag, host
  - classification: tier, origin, adapter config (jsonb), active flag
  - stats: name density, act rate, articles per year
- **Tiers**
  - `CORE`: taxonomy journals. All full-text routes are used, including paid OpenAlex TEI.
  - `BROAD`: abstracts plus free full text.
  - `CANDIDATE`: discovered, monitored, not yet alerting.
  - `EXCLUDED`
- **Seeding** uses OpenAlex `works` with `group_by=primary_location.source.id`, filtered to `type:article` and `primary_location.source.type:journal`, last 3 years. It selects by:
  - subfields 1105, 1109, 1103, 1104, 1110, 1911, 2404, 2405, 2303
  - taxonomy topics T14399, T11494, T12132, T12606, T11974, T12447, T13269, T12361, T12568, T14049, T11836, T11528, T12668, T11879, T11354, T10332, T12319

  Sources dominated by the taxonomy topics are seeded `CORE`, the rest `BROAD` above a threshold. Admins curate the result.
  - **Pitfall:** DiSSCo specimen and dataset "works" flood subfields 1105 and 1109. Always filter by type.
- **Journal categorisation** (a search filter from the start, decided by Markus). Each source carries:
  - **Discipline:** the OpenAlex subfields of the source (`topics`/`topic_share`), limited to the harvested vocabulary listed above, plus the primary subfield.
  - **Journal type:**
    - `TAXONOMIC`: publishes nomenclatural acts regularly (act rate above a threshold, or CORE taxonomy topics)
    - `GENERAL`
  - **Taxonomic focus:** the `TaxGroup`s that hold at least 20% of the journal's main-subject names, with their shares, plus the nomenclatural codes involved. These are derived nightly from the index; until enough articles are indexed, they are seeded from OpenAlex topics.
  - **OA model** (diamond/gold/hybrid/closed, DOAJ), country, publisher.
- **Discovery (weekly):** the same group-by over recent works finds unregistered sources and adds them as `CANDIDATE`.
- **Name-density feedback:** after N processed articles, propose promotion or demotion from distinct names per article and from the act rate. Admins confirm.

### 2. Harvesters (`HarvestAdapter` SPI)

Each adapter implements `fetch(sourceBatch, window) → Stream<WorkRecord>`. Works are upserted by OpenAlex id or DOI. `first_seen_at` is recorded once and never changes.

**MVP adapters**
- **OpenAlex daily window** (works on the free tier):
  - Query: `filter=primary_location.source.id:S1|…|S100,from_publication_date:{today-120d},type:article|review`, plus `corpus=all`, cursor paging.
  - About 1,000 list calls a day ≈ $0.10/day, within the free $1/day per API key.
  - A weekly sweep with a 400-day window catches late-indexed works.
  - Paid-plan filters (`from_created_date`, daily snapshots) can be added later behind the same adapter.
- **Crossref change detector** (free, polite pool via `mailto`):
  - Daily `from-index-date:{yesterday}`, with ISSN filters ORed in batches.
  - DOIs not yet known are fetched from OpenAlex `/works/doi:{doi}` (single lookups are free). If OpenAlex doesn't have the work yet, it is created from Crossref metadata and re-checked later.
  - Crossref JATS abstracts keep italics, so they are preferred over OpenAlex's inverted index.
- **Snapshot backfill:**
  - Stream the free quarterly OpenAlex snapshot (`s3://openalex`, no sign-in) and filter by registered source ids and a year range.
  - Parameters: `fromYear`, `toYear`, source set.
  - Runs at low priority and never notifies.

**Later adapters:** OAI-PMH for OJS journals without DOIs (Zootaxa's OAI responds), Pensoft OAI, bioRxiv API, Plazi TreatmentBank feed, RSS/Atom, and BHL for the deep backfill.

**Cost guard:** an OpenAlex spend meter with a hard cap at the free daily allowance by default (`openalex.maxDailyUsd`).
- Harvest calls have priority. Content downloads only use what is left that day.
- When the cap is reached, OpenAlex calls are deferred to the next day.
- Single-record lookups are free and are not counted.

### 3. Content acquisition and document model

**Route order**, stopping at the first route that succeeds:

| # | Route | Notes |
|---|---|---|
| 1 | Pensoft TaxPub XML `{journal}.pensoft.net/article/{id}/download/xml/` | Names pre-tagged. CC-BY. |
| 2 | Europe PMC `fullTextXML` | JATS |
| 3 | PMC OA AWS bucket `pmc-oa-opendata` | JATS |
| 4 | OpenAlex `content.openalex.org/works/{id}.grobid-xml` | TEI, $0.01 each. **Only from the leftover free allowance**, CORE tier first. Switch: `openalex.content.enabled`. Never used for backfill. |
| 5 | Local GROBID on `best_oa_location.pdf_url` | Only with an open licence and robots.txt allowing it. Polite and identified user agent. Skip hosts that block bots. |
| 6 | Abstract only | Crossref JATS abstract, else OpenAlex inverted index |

- **Converters** (JATS, TaxPub, TEI, abstract) all produce the internal **Document model**:
  - `blocks[]`, each with `id`, `type` (TITLE, ABSTRACT, HEADING(level), PARAGRAPH, CAPTION, TABLE_CELL, FOOTNOTE, REFERENCE), `text` and `sectionPath`
  - style spans: ITALIC, BOLD, SMALLCAPS
  - document language
- **Normalisation:** Unicode NFC, ligatures, soft hyphens, line-end de-hyphenation (rejoin only if the joined form is in the dictionary), whitespace. Offsets are relative to normalised block text.
- **Storage:** documents are stored as gzipped JSON in a content-addressed blob store (filesystem first, behind an S3-ready interface). Raw XML is kept as well; PDFs are not retained.
- **Licence and retention policy:**
  - Open licences (CC-BY, CC-BY-SA, CC0 and similar): retain the full text and show highlighted text in the UI.
  - Anything else: retain derived data only (mentions, offsets, snippets of at most about 200 characters, content hash plus URL), and re-fetch when reprocessing.
  - Abstracts are stored and shown with a source link.

### 4. Name finder (the core research piece)

**Abstraction (module `finder-api`, no Spring):**

```java
public interface NameFinder {
  FinderInfo info();                       // name, version, configHash
  FinderResult find(Document doc, FinderOptions opts);
}
// FinderResult: mentions[], acts[], classificationContexts[], diagnostics
// Mention: blockId, start, end, verbatim, name (normalised/expanded), type (UNINOMIAL|BINOMIAL|TRINOMIAL|
//          ABBREVIATED|…), rankGuess, authorship, qualifier (cf./aff./sp.), confidence, evidence{generators[], judge}, actRef, contextRef
// Act: type (SP_NOV, GEN_NOV, SUBGEN_NOV, SSP_NOV, COMB_NOV, STAT_NOV, STAT_REV, SYN_NOV, NOM_NOV, TYPE_DESIGNATION), marker, mentionRef, confidence
// ClassificationContext: id, verbatim, ranks[(rank,name)], scope (DOCUMENT|SECTION|TREATMENT), blockRange
```

- The same model is published as a **JSON/HTTP contract** (OpenAPI `finder-api.yaml`).
- `RemoteNameFinder` implements `NameFinder` over that contract, so an alternative finder (in Rust, Python or anything else) can be swapped in by configuration.
- `finder-service` exposes the default finder over HTTP, so others (CLB, Blixa) can use it.
- Every result carries `FinderInfo`, and the index stores which finder version produced each work's names. A finder upgrade triggers background reprocessing.

**Default implementation: `PipelineNameFinder`, with pluggable stages**

1. **Candidate generators** (`CandidateGenerator` SPI, results merged):
   - **gnfinder** helper service (HTTP `/api/v1/find`):
     - ambiguous uninomials on (`-A`), odds details on, built-in verification off
     - run on text blocks and mapped back to block offsets
     - v1.4+ abbreviation expansion and "nov." annotations are used as evidence
   - **COL XR word dictionary:**
     - built at each XR release from `download.checklistbank.org/col/xr_latest_coldp.zip` (about 1.4 GB)
     - sets: genus, epithet, and uninomial-by-rank
     - capitalised-token lookup plus Genus–epithet patterns
     - OCR-tolerant edit-distance-1 lookups only for capitalised tokens (later)
   - **Rank-suffix rules** for higher taxa, per nomenclatural code (-idae, -inae, -ini, -oidea, -aceae, -oideae, -eae, -ales, -mycetes …).
   - **Markup:** italic spans, and for Pensoft `tp:taxon-name` tags (high confidence).
2. **Merge and span resolution:** overlaps are resolved, then trailing authorship ("(Linnaeus, 1758)", "L.") and qualifiers are attached.
3. **Document-wide abbreviation expansion:** "P. leo" and "L. g. confertiflora" are resolved from full names elsewhere in the document. Ambiguous expansions stay unexpanded.
4. **Parse** each candidate with `name-parser-rust` (`org.gbif.nameparser:name-parser-rust` plus `name-parser-api`, versions via `clb-bom`). This gives canonical form, rank and authorship. Unparsable candidates are dropped.
5. **Act tagger:**
   - a regex catalogue: sp./gen./subgen./ssp. nov. or n., comb./stat./syn./nom. nov., stat. rev., "new combination", type designations
   - multilingual: n. sp., "nueva especie", "nouvelle espèce", "neue Art" …
   - each act is attached to the nearest preceding mention
   - for Pensoft, `tp:taxon-status` is used
6. **Classification-context extractor:**
   - title chains such as "(Coleoptera: Carabidae)", which are very common and also work for abstract-only papers
   - colon or comma chains
   - rank-word headings, multilingual: Family / Familia / Famille / Familie …
   - TaxPub treatment hierarchy

   A chain is accepted only if every element is a known or suffix-valid uninomial. Scope: a title chain applies to the DOCUMENT; a heading applies to its SECTION until the next heading at the same or higher level.
7. **Judge** (`CandidateJudge` SPI), used only for uncertain candidates: single words, ambiguous genus/common-word cases, fuzzy matches.
   - v1 `FeatureJudge`: a logistic-regression or GBDT model trained on silver data from TaxPub. Features:
     - italics
     - nearby rank word
     - gnfinder odds
     - dictionary hit type
     - document name density
     - block type and position
     - author-year that follows
   - Optional `LlmJudge`: **opt-in and off by default** (`finder.judge.llm.enabled`), budget-capped, Haiku-class model via Batch API. It sees about ±60 words of context and may only accept, reject or correct the spelling of an existing span. It never adds a name. The pipeline and its eval targets must be met without it.
8. **Output:** mentions with confidence and evidence. Mentions in REFERENCE blocks are kept for the index but never trigger alerts by default.

**Known limits to state in the UI:**
- gnfinder's Bayes stage covers only English and German; other languages rely on the dictionary, italics and the judge.
- Scanned PDFs: GROBID does no OCR.
- GROBID TEI keeps no italics (verified in M1, ADR-0002).

### 5. Matching to COL XR

- **`XrReleaseService`:**
  - resolves `GET /dataset/3LXR` daily and pins the integer key (rcol's `col_key()` pattern)
  - keeps an `xr_release` table
  - a new key starts the *XR release* workflow
- **`Matcher` SPI:**
  - `ClbApiMatcher`:
    - `GET /dataset/{key}/match/nameusage`, sending `scientificName`, `authorship`, `rank`, `code` and higher-taxon hints (`kingdom`…`genus`) taken from the mention's classification context
    - `mode=RELAXED`, so that unknown species fall back to their genus (`type=HIGHERRANK`)
    - throttled to about 10 requests a second
  - `ClbBulkMatcher`: `POST /dataset/{key}/match/nameusage/job` (TSV upload, poll, download the zip) for the backfill and XR re-matches. It needs a CLB service account.
  - Later, optionally: a local `WsMatchingServer` helper built from the release bundle.
- **Match cache:** `name_match` keyed by (`name_string_id`, `hint_key`, `release_key`). It stores usage id, accepted id, status, match type, issues, alternatives and `taxon_path text[]` (all ancestor ids plus the accepted and usage ids, from the response's `classification[]`).
- **Placement** of each name in a work, recorded in `placement_method`:
  - `MATCH`: the name matched directly.
  - `GENUS`: the name is unmatched (e.g. sp. nov.) and its genus matched.
  - `VERBATIM_CONTEXT`: the lowest matched taxon of the article's classification context is used.
  - `NONE`

  The higher taxa in each verbatim classification are themselves matched and stored. This allows a later "how does the literature classify X" view.
- **Nomenclatural code and taxonomic group** of each name in a work. These are search filters from the start.
  - **Vocabularies:** `org.gbif.nameparser.api.NomCode` and `life.catalogue.api.vocab.TaxGroup`. `TaxGroup` comes from the CLB `vocab` module, via `clb-bom` as in Blixa. It is hierarchical, from Viruses / Prokaryotes / Eukaryotes down to Coleoptera, Gastropods and so on, maps each group to its codes, and has PhyloPic icons at `api.checklistbank.org/vocab/taxgroup/{name}/icon.svg`.
  - **Matched names:** code and group are taken from the matched usage (`SimpleName.code` / `group`). If the group is missing from the response, it is computed with `TaxGroupAnalyzer.analyze(name, classification)` from the CLB `nomenclature` module.
  - **Unmatched names:** `TaxGroupAnalyzer` runs on the article's **verbatim classification context**. The code is taken from the group's codes when they are unambiguous.
  - **Other uses:** the code is passed to the matcher, and selects the finder's code-specific suffix rules.

### 6. Index and storage (Postgres 17)

**Main tables:**

| Table | Contents |
|---|---|
| `source`, `work` | Metadata, abstract, OA status/licence, `publication_date`, `first_seen_at`, **`origin`** (`LIVE` = daily window or Crossref, `SWEEP` = weekly wide sweep, `ONBOARDING` = first 120-day import when a source is added or promoted, `BACKFILL` = snapshot import), pipeline state |
| `content` | Route, kind, licence, blob refs, retained flag |
| `finder_run` | `FinderInfo` per run; `work.current_finder_run` points at the run in use |
| `mention` | Per span: block type, offsets, verbatim, normalised name, `name_string_id`, act, context, confidence, evidence, snippet. Large; partition by work year. |
| `work_name` | Aggregate per (work, name string): count, sections, in title/abstract, act flags, best snippet, `placement_method`, **`taxon_path text[]` (GIN index)**, **`salience` (0–1) and `main_subject` flag** (see §7). Drives search, taxon pages and alerts. |
| `name_ubiquity` | Per name string: share of indexed works from the last 5 years that mention it. Rebuilt nightly and used in `salience`. |
| Filter columns (from M0) | `work_name.code`, `work_name.taxgroup`. On `work`: `codes[]`, `taxgroups[]` (aggregated over **main-subject** names and expanded to all ancestor groups, so a filter on Insects is a simple array contains) and `has_acts`; GIN-indexed. On `source`: `subfields[]`, `journal_type`, `taxgroups[]` + shares (jsonb), `codes[]`, `oa_model`, `country`. |
| `name_string` | Unique normalised string plus parsed fields |
| `classification_context` | Verbatim text, parsed ranks, scope, block range |
| `xr_release`, `name_match` | See §5 |
| `taxon_stats` | Works per COL taxon, including descendants, per year. Rebuilt nightly. |
| `app_user`, `subscription`, `hit` | See §7 |
| `task` | Pipeline queue |

- **Search** starts in Postgres: `pg_trgm` for name strings, `tsvector` for titles and abstracts. Elasticsearch only if needed later.
- **Size estimate:**
  - backfill: about 4–5M works, measured first with a group-by count
  - mentions: on the order of 10^8 rows over time
  - blob store: about 100 GB for backfilled open full text, plus about 10 GB a year

### 7. Subscriptions and notifications

- **Subscription types**, each with options (new names only, include abstract-only hits, minimum confidence, include REFERENCE-block mentions, default off):
  - `TAXON`: a COL XR id; hits come from `taxon_id = ANY(work_name.taxon_path)`.
  - `SOURCE`: a journal.
  - `NAME`: a name string or canonical name.
  - `TAXGROUP`: a `TaxGroup`, e.g. "Gastropods". A convenient preset that also covers paraphyletic groups such as Algae or Protists, which have no single COL taxon.
  - Any subscription can be narrowed by the same filters as search: code, taxonomic group, journal type or discipline.
  - later: `AUTHOR` (ORCID) and saved searches.
- **Salience (against "every *E. coli* mention" noise).** Computed in the indexer, not the finder, when a work is indexed.
  - **Score inputs:**
    - in the title (strong signal)
    - in the abstract
    - carries a nomenclatural act
    - appears in a heading or treatment
    - share of the work's mentions, and repeated body mentions
    - minus a ubiquity penalty from `name_ubiquity`. Lab and model organisms such as *E. coli*, *Mus musculus*, *Homo sapiens* and *Arabidopsis* are penalised automatically, with no curated list.
  - `main_subject = salience ≥ threshold`. The threshold is tuned on the main-subject labels of the hand-checked set (see *Evaluation*).
  - **Defaults:**
    - `TAXON` subscriptions alert on main-subject names only.
    - `NAME` and `SOURCE` subscriptions alert on all mentions outside reference blocks.
    - Both can be switched per subscription.
  - The finder contract stays role-free. `Mention` carries an optional, extensible `hints` map, so a future finder can supply roles such as host or model organism without a contract change.
- **What counts as new (hit evaluator, runs when a work reaches `INDEXED`):**
  - A work can produce hits only if all of these hold:
    - its `origin` is `LIVE` or `SWEEP`
    - it was published within the last 60 days (or, with no date, first seen within the last 60 days)
    - it was indexed after the subscription was created
  - Adding or promoting a journal imports its recent works as `ONBOARDING`, so it never floods inboxes.
  - `BACKFILL`, finder reprocessing and XR re-placement never produce `ARTICLE` hits.
  - Hits are unique per (subscription, work, kind):
    - `ARTICLE`: the normal hit.
    - `NEW_NAMES`: a follow-up when full text arriving within 90 days of `first_seen_at` reveals new names (acts) in a followed group that the earlier abstract-based hit didn't have. CORE journals therefore don't miss the sp. nov. hits that matter most.
- **Delivery:**
  - **Email digests:** daily or weekly in the user's time zone, grouped by subscription, new names highlighted, one-click unsubscribe (List-Unsubscribe).
  - **Private Atom feed** per subscription (secret-token URL).
  - **Public Atom feeds** per taxon, journal and name that need no account: the uBioRSS idea.
  - An in-app "My hits" page.
- **Taxa removed from a new XR:** the subscription is flagged, a replacement is tried via the names index or the same name, and the user is told.

### 8. Accounts

- **ORCID OIDC login**, copying Blixa's `auth/SecurityConfig.java` and `user/OrcidUserService.java`.
- **Passwordless email magic-link login** for people without ORCID.
- Open sign-up. Blixa's approval filter is not used.
- An email address is required and verified before digests are sent.
- Admins are named by ORCID in an environment variable (Blixa pattern).
- Account deletion and data export, for GDPR.

### 9. Public web UI and API

**REST under `/api`, documented with OpenAPI:**
- **Search filters and facets from the start**, shared by works, names, taxon pages and subscriptions. Facets return counts.
  - `code`: nomenclatural code
  - `taxgroup`: hierarchical; selecting a group includes its subgroups
  - journal categorisation: `subfield`, `journalType`, `tier`, `oa`, `country`, `source`
  - `year`/date range, `taxon` (COL id, includes descendants), `name`, `hasActs`/`actType`, `mainSubjectOnly`
- `works` (search with the filters above), `works/{id}`
- `names?q=`, `names/{id}` (match, timeline, first appearance)
- `taxa/{colId}/works`, `taxa/{colId}/stats`
- `sources`, `sources/{id}`
- `me/subscriptions`, `me/hits`
- `/feeds/...atom`

**React + Mantine pages:**
- **Search** with a filter sidebar: code, taxonomic group (with the CLB PhyloPic icons), journal categories, year, acts
- **Taxon page:** papers per year, recent papers, new names, verbatim classifications used.
- **Name page**
- **Article page:**
  - names grouped by COL placement
  - verbatim classification shown next to the COL one
  - highlighted text only for open-licensed works
- **Journal page**
- **My subscriptions** / **My hits**
- **Admin:** source tiers, pipeline status, budgets, eval metrics

The taxon picker uses the CLB suggest API on the pinned XR key.

**Later (confirmed by Markus):** a portal-components **"Literature" widget for COL taxon pages**, fed by this index, and links from ChecklistBank.
- The taxon endpoints (`taxa/{colId}/works`, `taxa/{colId}/stats`) are designed for the widget from the start: compact, stable JSON, paging, and CORS for catalogueoflife.org. The widget then needs no API changes.

### 10. Orchestration

- **Work state machine:** `DISCOVERED → CONTENT_RESOLVED (FULLTEXT|ABSTRACT_ONLY|NO_TEXT) → NAMES_FOUND → MATCHED → INDEXED → NOTIFIED`.
- **Queue:** a Postgres `task` table with `FOR UPDATE SKIP LOCKED` workers per stage.
  - priorities: LIVE > REPROCESS > BACKFILL
  - retries with backoff, and a dead-letter state
  - per-service rate limiters: OpenAlex, Crossref, Europe PMC, CLB, gnfinder, GROBID
- **Periodic jobs** (single instance, Spring `@Scheduled` + ShedLock):
  - **indexer:**
    - daily: harvest, XR check
    - weekly: wide sweep, discovery
    - nightly: `taxon_stats`, `name_ubiquity`, journal categorisation
  - **web:** hourly delivery of due digests
- The pipeline (task workers) runs only in the **indexer**. `web` only enqueues tasks.

## Workflows

1. **Seed and discover journals:** OpenAlex group-by → `source` rows by tier → admin curation. Weekly discovery adds candidates, and name density proposes promotions. Adding or promoting a source triggers an `ONBOARDING` import of its last 120 days, which never notifies.
2. **Daily harvest:** OpenAlex window plus Crossref index-date → upsert works → queue content tasks.
3. **Content:** route chain → document model → blob store, with licence-based retention.
4. **Find names:** `NameFinder.find` → mentions, acts, contexts → `name_string` upserts → `work_name` aggregates.
5. **Match and place:** cache lookup, otherwise matcher → placement → `taxon_path` → `INDEXED`.
6. **Notify:** hit evaluator → hits → digest job, emails and feeds.
7. **New XR release:**
   - rebuild the dictionary
   - bulk re-match all distinct (name, hint) keys
   - batch-update `work_name.taxon_path`
   - switch `current_release`
   - check subscription taxa

   No notifications are sent.
8. **Finder upgrade:** a new `FinderInfo` queues REPROCESS tasks for all works (re-fetching non-retained text where needed). The work pointer is switched atomically per work.
9. **Backfill(fromYear, toYear):** snapshot stream → works (`origin=BACKFILL`) → free routes only (Pensoft, Europe PMC, PMC bucket; no OpenAlex content, no LLM) → find → bulk match. Never notifies. Costs $0. First run 2000→now; the same job can go further back later.
10. **Evaluation:** see below. It runs in CI on every finder change and nightly on fresh Pensoft articles.

## Evaluation (built in M0/M1, before tuning)

- **Primary gold standard: a hand-checked set of about 100 documents** (decided by Markus). On 2026-11-15 an ACTS stratum
  of 12 act-rich documents (three CC-BY PDF excerpts, nine abstracts from nine taxonomy journals) was added, because the
  first 100 had acts only in their Pensoft excerpts; act type `HIGHER_NOV` covers acts above the family group.
  - **Contents:**
    - about 50 abstracts from `BROAD` journals (ecology, microbiology, parasitology), which is where the noise lives
    - about 20 non-English papers (Spanish, Portuguese, French, German)
    - about 30 Pensoft full texts, which also measure how reliable Pensoft's own tags are
  - **Pre-tagging:** the union of all candidate generators (high recall).
  - **Correction:** a person corrects the pre-tags in an annotation tool (builder's call; default: open-source Label Studio in Docker, with import/export converters in `eval/`, so no UI has to be built). Roughly 1–2 days of someone's time.
  - **Guidelines** (`eval/GUIDELINES.md`): what counts as a name (every rank, abbreviated forms, qualifiers kept separate) and what doesn't (common names, species-group names, gene or strain names, virus names in v1). The guidelines also cover how to tag acts and classification contexts, and a per-document **main-subject** mark on names, which is used to tune salience.
  - The set and the guidelines are checked into `eval/gold/curated/` (only open-licensed texts as full text; for the rest, store the IDs plus annotations and re-fetch the text).
- **Secondary, larger silver standard:** Pensoft TaxPub XML (ZooKeys, PhytoKeys, MycoKeys, BDJ), which is CC-BY.
  - Names come from `tp:taxon-name`; acts come from `tp:taxon-status`.
  - **Mode A (clean text):** strip the tags, run the finder, compare spans.
  - **Mode B (PDF path):** run the same article's PDF through local GROBID (free; OpenAlex TEI optional), run the finder, compare unique names per document. This measures de-hyphenation, captions and tables.
  - Document what TaxPub doesn't tag (e.g. species groups) and exclude it from scoring.
- **Fixed held-out set:** about 300 articles checked into `eval/` (CC-BY, so allowed). Metrics are reported strict and overlap, per category (binomial, abbreviated, uninomial by rank, caption/table), for acts, for context extraction, and for final COL match correctness. CI fails on regression beyond a threshold.
- **Later corpora:**
  - COPIOUS (OCR text, CC-BY)
  - S1000 (abstracts)
  - Plazi treatments (non-English)
  - in production, a recall monitor comparing detected new names against ZooBank, IPNI and Index Fungorum feeds

## Non-functional

- **Scale:** about 1,000 new works a day. gnfinder handles about 15M pages an hour, so finding names is not a bottleneck; matching is cached.
- **Costs per year (estimates):**

| Item | Default cost |
|---|---|
| OpenAlex harvesting | $0, within the free $1/day per key (about $0.10–0.20/day used) |
| OpenAlex TEI for CORE gaps | $0, only from the leftover free allowance (about 80/day); can be switched off |
| Backfill 2000→now | $0, free open-data sources only |
| LLM judge | $0, off by default; opt-in with a budget cap |

- **Deployment** follows Blixa (`deploy/blixa/`, Blixa's `Jenkinsfile`):
  - Jenkins → two jars → two systemd units, `col-nametracker-web` and `col-nametracker-indexer`, on a GBIF VM. Each is deployed and restarted independently.
  - Public URL: proposed `garrulus.catalogueoflife.org`, to be confirmed when deployment is set up in M2.
  - Apache vhost serving the SPA and proxying `/api`, `/oauth2`, `/login`, `/feeds` to `web` only
  - native Postgres 17
  - gnfinder as a systemd-run Go binary; GROBID as a Docker or Java service
  - secrets in an EnvironmentFile kept in the deploy repo: OpenAlex key, Crossref mailto, CLB service account, SMTP, ORCID client
  - running inside GBIF infrastructure avoids Varnish blocking of cloud IP ranges
- **Testing:**
  - unit tests
  - `*IT` with a Testcontainers `postgres:17` singleton (Blixa's `AbstractPostgresIT`) and a gnfinder container
  - WireMock fixtures for OpenAlex, Crossref, Europe PMC and CLB
  - the eval harness as its own CI job

## Repo layout and stack

```
nametracker/                       (new repo github.com/CatalogueOfLife/nametracker, git init)
  pom.xml                          Spring Boot 4.1 parent, java 25, imports clb-bom via clb.version (Blixa pattern),
                                   GBIF repository blocks copied (add nametracker to the "must change together" list in ~/.claude/CLAUDE.md)
  finder-api/                      document model + NameFinder SPI + JSON contract (no Spring)
  finder-core/                     PipelineNameFinder, generators (gnfinder client, COL dictionary, suffix, markup), stages, judge SPI
  finder-service/                  thin HTTP wrapper exposing /find (reusable by CLB/Blixa)
  store/                           shared schema: Flyway migrations, MyBatis mappers + model, task-queue access (no Spring Boot app)
  indexer/                         Spring Boot app #1: registry, harvest, content, pipeline workers, finder, matching, XR lifecycle,
                                   backfill, reprocessing, hit evaluation, nightly stats — stoppable at any time
  web/                             Spring Boot app #2: public REST API, feeds, auth, subscriptions, digest delivery, admin endpoints
  eval/                            gold-set fetcher (Pensoft TaxPub), scorer, CLI, fixed held-out set
  frontend/                        Vite + React 18 + TypeScript + Mantine + TanStack Query (vitest + MSW)
  docker-compose.yml               postgres:17 (port 5434), gnfinder, grobid
  docs/design.md                   this document; docs/adr/ for later decisions
```

Packages are `org.catalogueoflife.nametracker.*`. Configuration is through environment variables, migrations are Flyway `V<n>__*.sql`, and Java is JDK 25 (`.sdkmanrc` as in Blixa).

**Code to reuse or copy:**
- Blixa:
  - `auth/SecurityConfig.java`, `user/OrcidUserService.java`
  - `notify/EmailService.java`
  - `discussion/DiscussionFollowMapper.java` / `DiscussionNotifier.java` (follow-and-notify pattern)
  - `name/CrossrefClient.java` (polite pool)
  - `bhl/BhlClient.java`
  - `editor/parse/NameParserService.java`
  - `support/AbstractPostgresIT.java`
  - the `vocab` + `nomenclature` dependencies in `backend/pom.xml`
- CLB modules (via `clb-bom`):
  - `vocab/.../api/vocab/TaxGroup.java`
  - `nomenclature/.../matching/TaxGroupAnalyzer.java` (`analyze(SimpleName, Collection<SimpleName>)`, `lowestCommonGroup`)
- CLB backend docs:
  - `docs/GBIF-MATCH.md` (matching semantics)
  - `ColReleaseExportJob.java` (XR export URLs)
  - `AbstractMatchingJob.java` (bulk-match output columns)
- rcol: `R/col.R` `col_key()` (key pinning)

## Roadmap

| Milestone | Content | Done when |
|---|---|---|
| **M0 Foundations** | Repo scaffold with modules `finder-api`, `finder-core`, `finder-service`, `store`, `indexer`, `web` (the two apps as bootable skeletons sharing `store`), `eval`, `frontend`; CI, docker-compose, Flyway v1 schema (source incl. categorisation columns, work incl. `origin` and filter columns, content, task), `finder-api` model + JSON contract (incl. `hints`), document model + JATS/TaxPub converter, eval harness: Pensoft silver fetcher, curated-set selection + pre-tagging + Label Studio import/export, `GUIDELINES.md` | `mvn verify` green; eval CLI scores a trivial finder on 50 Pensoft articles; ~100 curated docs exported for hand-checking |
| **M1 Finder v1** | gnfinder generator, COL dictionary builder, suffix rules, markup generator, merge, abbreviation expansion, parser stage, act tagger, context extractor; FeatureJudge stub (thresholds). TEI converter and a local GROBID path pulled forward from M2 so that mode B can be measured. | P/R/F1 reports committed on the hand-checked set (primary) and on Pensoft (secondary, mode A and B). Beats gnfinder alone on F1 on the hand-checked set, with no regression in precision on the `BROAD` abstracts. |
| M2 Harvest & content | Source seeding from OpenAlex, daily OpenAlex + Crossref harvest, abstracts, content routes 1–4 + TEI converter, cost meter | A week of live harvesting runs unattended into the dev DB |
| M3 Match & index | XR service, CLB matcher + cache, placement, code + `TaxGroup` per name (match or `TaxGroupAnalyzer` on verbatim context), `work_name` + salience/`name_ubiquity` (threshold tuned on curated main-subject labels), journal categorisation job, `taxon_stats`, REST API with filters and facets, search/taxon/name/article/journal pages | Taxon page for Carabidae shows this week's papers with correct placements |
| M4 Accounts & alerts | ORCID + magic link, subscriptions, hit evaluator, digests, Atom feeds | Test user receives a correct digest; no backfill hits |
| M5 Backfill & lifecycle | Snapshot backfill 2000→now, bulk matching, XR re-match, finder reprocessing | Index holds about 4–5M works; XR switch causes zero notifications |
| M6 Quality & reach | Trained judge, optional LLM judge, discovery/promotion, OAI-PMH/bioRxiv/Plazi adapters, nomenclator recall monitor, portal widget, deeper backfill incl. BHL | — |

M1 and M2 can run in parallel after M0.

## Getting started (after approval)

1. `git init` `~/code/col/nametracker`. Save this design as `docs/design.md`, and add a `CLAUDE.md` with commands and conventions.
2. Invoke `superpowers:writing-plans` to write the detailed implementation plan for **M0 + M1**, built test-first (the eval harness first, so finder work is measured from day one).

## Verification (for M0/M1)

- `mvn verify` passes: unit tests plus `*IT` against Testcontainers Postgres and a gnfinder container.
- Both app skeletons start against the same dev database. `indexer` applies the migrations and `web` validates them. Stopping `indexer` leaves `web` healthy (`/actuator/health`), and a task enqueued by `web` while `indexer` is down is picked up after it restarts.
- `docker compose up` starts postgres, gnfinder and grobid. The finder service answers `POST /find` for a ZooKeys article and returns mentions, acts and contexts with offsets that match the source text.
- `eval` CLI: fetch 300 Pensoft articles → run the finder in mode A and mode B → write a P/R/F1 report per category. Compare against a gnfinder-only baseline run.
- Spot-check by hand: 5 articles (zoology, botany, mycology, a Spanish-language paper, a title chain "(Coleoptera: Carabidae)").

## Open items

- **Builder's calls** (sensible defaults above; change if needed; record in `docs/adr/`): queue implementation details, `mention` partitioning, the dictionary format (FST vs hash set), the exact judge model, frontend page details, the dev Postgres port, the CI platform (Jenkins as in Blixa).
- **Dictionary rebuilds:** a rebuilt dictionary for a new XR is recorded on each finder run (dictionary release key), but it does **not** count as a finder upgrade. Only a change to the finder's code or config triggers reprocessing, which avoids reprocessing millions of works every month.
- **To verify while building:**
  - whether gnfinder's HTTP API returns offsets per block as expected, and its hyphenation handling
  - whether GROBID TEI keeps italics: verified in M1, it does not (ADR-0002)
  - whether CLB's `classification[]` for synonyms holds the accepted lineage: verified 2026-11-15, it does (*Felis leo*
    gives `classification[0]` = accepted *Panthera leo* `4CGXP`, then its ancestors up to Felidae `623RM`; ADR-0005)
  - that Crossref ORs repeated `issn:` filters: verified 2026-11-15, they are ORed (ZooKeys 8,542 + Zootaxa 50,404 = 58,946; ADR-0004)
  - how many works the backfill really is (group-by count)
  - OpenAlex snapshot size and layout
- **M3 calls** (plan `docs/superpowers/plans/2026-11-15-m3-match-and-index.md`, details in ADR-0005):
  - Tuning moved out of M3: the salience weights and threshold ship as untuned defaults in `nametracker.salience.*` and
    are tuned on the curated main-subject labels in the final finder-tuning stage, with the finder itself and the
    `TeiConverter` tokenisation clean-up (Markus, 2026-11-15).
  - Closed full text waits in a transient spool (`data/spool`) until `FIND_NAMES` has run, for at most 14 days; it never
    enters the blob store.
  - `mention` is not partitioned yet (revisit with the M5 backfill).
  - The XR is pinned at the first key `XR_CHECK` sees (316691, "COL26.10 XR"); later keys are only recorded, switching is
    M5. `ClbBulkMatcher` is not built (M5). The matcher does not send `code` yet.
  - Placement: `higherrank` counts as `GENUS` only when it lands on a genus for a binomial, trinomial or infrageneric
    name; `higherrank` to any other rank, `none` and `ambiguous` fall back to the lowest matched context taxon, else
    `NONE`. OTUs are never matched. Every context element is matched on its own, without hints.
  - CLB's match response carries no `group` today; `TaxGroupAnalyzer` computes it from the usage's classification.
  - A repeat `FIND_NAMES` for an already named document is a no-op, also under a new finder version; M5's reprocessing
    needs a force flag.
  - Journal categorisation recomputes a source only from 20 indexed works in the current and the two previous years;
    there is no topic-based group seeding.
  - The taxon filter and taxon pages ignore reference-only names; the name filter keeps them (the name–literature index
    lists every occurrence).
  - The OpenAPI file is written by hand and checked against the mappings by `OpenApiCoverageTest`.
  - The browser calls CLB directly for the taxon picker and the taxon page header; `web` calls no external service.
  - Task priorities: `REBUILD_STATS` runs at `REPROCESS`; follow-up tasks inherit their task's priority.
