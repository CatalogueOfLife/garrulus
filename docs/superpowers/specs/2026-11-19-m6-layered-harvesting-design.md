# M6: layered harvesting, crosslinks and journal registration — design

Date: 2026-11-19. Status: draft for Markus' review.

Research input: `docs/research/2026-11-19-journal-harvesting-without-openalex.md` (live checks of 2026-11-19). The
roadmap is renumbered (Markus, 2026-11-18): M6 is reach, M7 is judging (trained and optional LLM judge, the next
tuning step), M8 is the portal Literature widget.

## Intent

Garrulus must keep finding new papers when one of the services it depends on fails, changes, or starts charging
(Markus, 2026-11-19: "built sth robust that has different layers of harvesting, so we have redundancy and fallback
options in case one of the services fails, becomes a paid service or whatever else"). OpenAlex stays: its results and
its journal classifications are good ("keep open alex in if it is a good fit"). Around it, M6 adds independent layers
for journals, works, abstracts and full text, a monitor that notices when a layer weakens, links from every journal and
work to the key services of the field, and a way for people to register journals Garrulus misses.

## Goals

1. **Layered harvest (approach A).** Several harvest layers run side by side, each on its own schedule, for every
   journal it can serve. Every layer can create a work on its own; none needs another to be up. Losing any one layer
   loses no works that another layer also sees.
2. **One work, many sightings.** Records of the same paper from different layers merge into one work through shared
   identifiers (DOI, OpenAlex id, OAI identifier, PMID, PMCID, Zenodo/BLR, …). The same identifiers are the crosslinks
   (goal 5), so matching and linking share one store.
3. **Coverage monitor.** Every layer records which works it saw. A nightly check compares the layers per journal and
   flags a layer that fails, slows, or misses works the others see, within a day, on the admin status page and in an
   e-mail to the admins.
4. **Discovery beyond OpenAlex.** Besides the OpenAlex seeding, journals come from DOAJ, the PKP Beacon list of OJS
   journals, Plazi's per-journal statistics and the references of the COL XR. They enter as `CANDIDATE`; promotion
   stays an admin decision.
5. **Crosslinks** (Markus: "links for readers, and matching works across layers"). Journals and works carry identifiers
   of IPNI, BPH, ZooBank, Plazi and the Biodiversity Literature Repository (BLR) on Zenodo, BHL, Europe PMC
   (PMID/PMCID) and Biodiversity PMC, shown as links on the work and journal pages and in the API.
6. **Journal registration** (Markus: "a way in the API and web UI to register missing journals"). A signed-in user
   registers a journal by ISSN, article DOI or web address; Garrulus identifies it and files it as a `CANDIDATE`.
7. **Abstract and full-text fallbacks.** A work without an abstract gets one from Europe PMC, the Springer Nature Meta
   API, SciELO ArticleMeta or CiNii; open full text arrives through OAI-PMH JATS where a journal offers it.
8. **University of Copenhagen access, prepared but off.** A content layer for subscription full text and abstracts
   through the publishers' text-mining APIs, reachable from the KU network, behind a switch that stays off until the
   licences are cleared (see "Institutional access").

## Non-goals

- Paid services of any kind (Markus, 2026-11-19: "strictly avoid paid services"). OpenAlex runs on its free daily
  allowance only, as today.
- Harvesting BHL content. BHL appears only as identifiers and links.
- Scraping publisher websites or PDFs; RSS/Atom feeds as a harvest layer (they carry 10–100 items and often no abstract;
  at most a later signal); crawling Chinese journal sites; J-STAGE (needs written permission) and KCI (needs a key
  application). All stay possible later behind the same layer SPI.
- The nomenclator recall monitor and the deep pre-2000 backfill from the original M6 list. Crosslinks to IPNI and
  ZooBank prepare the recall monitor; it gets its own design later.
- Judging and tuning (M7), the portal widget (M8), deployment.

## Anti-goals

- A failing layer that silently loses works, or a monitor that cries wolf every night.
- The same paper listed twice because two layers saw it with different identifiers.
- Alerts about old papers: a new layer, or a new route for a journal, must never notify for its back catalogue.
- Hammering small journal servers: one OJS install often hosts dozens of journals.
- Keeping closed full text, or showing more of it than snippets.
- A user registration that floods the harvest: nothing a user registers is harvested before an admin promotes it.

## Constraints

- Two apps, one database; all harvesting runs in the indexer as tasks of the existing queue, one page per task with
  continuation tasks as checkpoints (ADR-0004), idempotent under at-least-once delivery. Priorities as today: live
  layers `LIVE`, onboarding and reprocessing `REPROCESS`, backfill `BACKFILL`.
- Schema changes are expand-only Flyway migrations (V16 …); a running `web` keeps working through them.
- Every external host goes through `PoliteHttp` and `HostLimiter` with an identified user agent
  (`Garrulus/<version> (+https://www.catalogueoflife.org)`), and every outage defers instead of using attempts
  (`HarvestOutage`).
- Free by default: every integration has a switch; a service that needs a key (Springer Nature, CiNii, BHL, KU
  text-mining tokens) is off until its key is configured.
- Full text is kept only for open licences (`nametracker.content.open-licences`); closed text lives in the spool until
  `FIND_NAMES` and is then purged (ADR-0004, ADR-0005).

## Facts found while designing

From the research report (all checked live on 2026-11-19) and the dev DB:

- **Crossref** (layer 2, already running) limits list queries to 3 a second per `mailto` in the polite pool since
  July 2026, has no subject data since April 2024, and deposits no abstracts for Elsevier and Taylor & Francis
  (0 of 147 and 0 of 141 in 2025 samples), only open-access ones for Springer Nature. `has-license` is not "open".
  Journals must be queried by every ISSN: one journal had 144 works under its e-ISSN and 1 under its print ISSN.
- **OAI-PMH** endpoints work for Pensoft/ARPHA (`https://zookeys.pensoft.net/oai.php`, 103 sets, one per journal),
  Magnolia Press (Zootaxa, Phytotaxa, Megataxa, Bionomina, Palaeoentomology), EJT, JoTT (offers `jats` with CC BY full
  text), MDPI (sets are article types: filter by ISSN), Copernicus, DOAJ (`oai_doaj`), and the platforms Hrčak,
  DergiPark, AJOL (2,870 sets), CyberLeninka, Persée, SciELO Mexico. Quirks: MNHN Paris ignores `from` and has no sets;
  AJOL and DergiPark report today as their earliest datestamp; Magnolia gives no `dc:rights`. No endpoint publishes a
  rate limit.
- **PKP Beacon** (Harvard Dataverse, doi:10.7910/DVN/OCZNVY, CC0, v7 of 2026-07-18): 98,273 OJS journals with OAI URL,
  set, ISSN, country and 2025 record count; a keyword filter finds about 520 biodiversity journals, 313 active in 2025.
- **DOAJ** API v4: journal subjects (LCC), licence, country; article abstracts; OAI `oai_doaj`; 2 requests a second.
- **Europe PMC**: Zootaxa is in PubMed with abstracts (2,021 in 2025), ZooKeys fully with open full text, Phytotaxa
  mostly absent. PMC International today has two members, US PMC and Europe PMC; Europe PMC takes PMC's articles and
  adds preprints and more, so Europe PMC covers both.
- **Biodiversity PMC** (SIBiLS, Plazi, Pensoft; BiCIKL, launched 2024-02) indexes PMC, MEDLINE, Pensoft and Plazi
  treatments. It has no identifiers of its own: links go by DOI or PMCID.
- **Plazi / BLR**: the Zenodo community `biosyslit` grows by about 15.6k records a month; treatments (CC0) point to
  their article DOI (`isPartOf`). It is polled by `created:[x TO now]` (anonymous pages of 25) or OAI set
  `user-biosyslit`. Plazi's `srsStats` endpoint lists several hundred taxonomic journals.
- **IPNI**: JSON API `www.ipni.org/api/1/search`; publication records carry the BPH reference and BHL links. **BPH**
  has no API; its ids are reachable through IPNI publication records and Wikidata (P829). Wikidata also holds IPNI
  publication, ZooBank and BHL title ids for journals.
- **ZooBank**: JSON API at proof-of-concept level, unreliable in our probes; lookups by DOI only, best effort.
- **Unpaywall** now runs on OpenAlex's system, so it is no independent layer. **JournalTOCs** is gone. **Lens** and
  **BASE** are paid or restricted. The **bioRxiv** bulk bucket is requester-pays (the per-date API is free).
- **Springer Nature Meta API**: free Basic key, abstracts also for subscription articles; the monthly cap is
  unconfirmed. **SciELO ArticleMeta**: free, incremental by ISSN and date, abstracts in all languages. **CiNii
  Research**: free appid, abstracts with a reuse flag.
- **Dev DB** (2026-11-19): 103,884 works, 577 MB; `work` requires an OpenAlex id or a DOI (V1 CHECK); `source` keys on
  `openalex_id` (nullable, unique) and `issns`; `harvest_run.adapter` is `OPENALEX_LIVE` or `CROSSREF` so far.

## Approaches

Three ways to relate the layers were weighed (Markus picked A, 2026-11-19):

- **A. All layers always on, merged on identifiers (chosen).** Every layer harvests every journal it can serve; records
  merge through a shared identifier store; a coverage monitor compares the layers. No switching logic, real
  redundancy, and data on what each layer contributes. Costs more calls (within every free limit) and needs careful
  merging.
- **B. Primary route with ordered fallbacks.** Fewer calls, but a failure is noticed late, there is a gap while it
  switches, and fallbacks that never run rot unnoticed.
- **C. Today's two layers plus feeds only where they are missing.** Smallest change, but most journals keep exactly
  today's redundancy, and journals without DOIs depend on one layer.

For the crosslinks, one generic identifier store (rows of scheme and value) was preferred over a column per service:
new services need no migration, and the same rows serve matching.

## Design

### The layers

| Layer | Source | Journals it serves | Schedule | Gives |
|---|---|---|---|---|
| L1 OpenAlex | daily window (120 d) and weekly sweep (400 d), as today | all onboarded with an OpenAlex id | daily 02:00, Sun 04:00 | metadata, OA status, topics, inverted abstract, TEI (free allowance) |
| L2 Crossref | `from-index-date` by every ISSN, as today | all onboarded with an ISSN | daily 03:00 | metadata, JATS abstract (publisher dependent), licence URL |
| L3 OAI-PMH | per journal (OJS) or per platform set | onboarded journals with a route (below) | daily 04:30 | metadata, abstract, DOI or OAI id, sometimes JATS full text |
| L4 Europe PMC | search by ISSN and `FIRST_IDATE` | onboarded journals that PubMed/Europe PMC index | daily 05:00 | metadata, abstract, PMID, PMCID, OA full text flag |
| L5 BLR | Zenodo community `biosyslit`, `created` since last run | every journal (signal for new taxonomic DOIs) | daily 05:15 | article DOIs with treatments and their acts |

- **Every layer creates works.** L2 already creates a work from Crossref metadata when OpenAlex lacks it; it keeps that,
  but no longer waits on OpenAlex: the `/works/doi` lookup becomes an enrichment (when L1 is up) instead of the path.
  L3 and L4 create works from their own records. L5 creates a work only when the DOI's journal is onboarded; a DOI of
  an unregistered journal is a discovery signal (it feeds the candidate list, see "Discovery").
- **A layer SPI** (the `HarvestAdapter` of the design, finally): `HarvestLayer` with `layer()`, `servesSource(source)`,
  `firstPage(sourceBatch, window)` and `nextPage(position)`, each page returning `WorkRecord`s with their identifiers.
  The existing OpenAlex and Crossref handlers are wrapped, not rewritten. Each layer has its own `harvest_run.adapter`
  value (`OAI`, `EUROPEPMC`, `BLR` join `OPENALEX_LIVE`, `OPENALEX_SWEEP`, `CROSSREF`), its own dedup key prefix and
  its own switch `nametracker.layers.<layer>.enabled` (all on by default, except keyed services without a key).
- **Windows.** L3 harvests with `from` = last successful run minus 3 days; endpoints whose datestamps are unreliable
  (flag on the route, e.g. AJOL, DergiPark, MNHN) are read in full weekly instead of daily and deduplicated by
  identifier. L4 uses a 14-day window daily. Overlap heals missed days, as for L1.
- **Routes per journal.** A new table `source_route` (`source_id`, `layer`, `config` jsonb: OAI base URL, set,
  metadata prefix, unreliable-datestamp flag; `status` ACTIVE/PAUSED/BROKEN, `verified_at`, `last_ok`, `last_error`,
  `origin` SEED/DISCOVERED/ADMIN/REGISTERED). L1 and L2 need no row (OpenAlex id and ISSNs suffice); L3 needs one; L4
  gets one when a probe finds the journal in Europe PMC. `source.adapter_config` is superseded.
- **OAI politeness.** One `HostLimiter` per OAI host, default 1 request every 2 s, so a platform with 50 journals is read
  as one slow stream; platform-wide endpoints are read once per run with all their wanted sets, not once per journal.
  Prefer `jats` over `oai_dc` where an endpoint offers it.
- **A new route starts with an onboarding pass.** When a route is added to an already onboarded journal, its first run
  is an `ONBOARDING_ROUTE` pass over 120 days at `REPROCESS` (works it creates get origin `ONBOARDING`), like a new
  journal (ADR-0004); only then does it join the daily schedule. Alerts follow ADR-0006 unchanged: the 90-day window,
  not the origin, decides. A paper that only the new route finds and that is inside the window does alert, because
  it is recent and nobody has heard of it; older ones never do. (My call, from ADR-0006; the builder check pointed
  out that the first draft contradicted it.) The first M6 deployment adds the OAI and Europe PMC routes in batches of
  about 20 journals a night, so such alerts trickle in instead of arriving in one burst.

### Work identity and merging

- **`work_identifier`** (`work_id`, `scheme`, `value`, `first_layer`, `first_seen`), unique on (`scheme`, `value`).
  Schemes: `DOI`, `OPENALEX`, `OAI` (value `<repository identifier>`, e.g. `oai:zookeys.pensoft.net:12345`), `PMID`,
  `PMCID`, `BLR` (Zenodo record id), `PLAZI` (Plazi article/document id), `ZOOBANK` (reference UUID), `BHL_PART`.
  Values are normalised per scheme (DOI lower case without resolver, PMCID with `PMC`, …).
- `work.doi` and `work.openalex_id` stay as columns (much code reads them) and are mirrored into the store. The V1
  `CHECK (openalex_id IS NOT NULL OR doi IS NOT NULL)` is dropped (V16): a work needs at least one identifier row
  instead, enforced by `WorkUpsert`.
- **Matching order** in `WorkUpsert`, first hit wins: `DOI` → `OPENALEX` → any other identifier of the incoming record
  (`PMID`, `PMCID`, `OAI`, `BLR`) → a **title match**: same source, same publication year, same normalised title
  (`TitleText.clean`, case-folded, punctuation and whitespace collapsed), and the same first-author family name when
  both have authors. The title match applies in both directions (a DOI-less OAI record joining a work that has a DOI,
  and a DOI record joining a work that has none), never between two works with different DOIs, never for titles of
  fewer than four words, and never for generic titles (erratum, corrigendum, correction, editorial, book review,
  obituary, index, contents, in memoriam, and their common translations). Without both-direction matching an OAI
  record seen first and the same paper's DOI record seen later would stay two works for good. (My call; the guards
  keep false merges of notices out.)
- **Merge rule, per field by layer precedence.** With five layers seeing the same work every night, "newer non-null
  wins" would make fields flip nightly. Instead each bibliographic field is owned by the highest-ranked layer that
  supplied it, and a lower-ranked layer only fills nulls:
  - title, authors, volume, issue, pages, publication date, type: Crossref > OpenAlex > Europe PMC > OAI > BLR (the
    publisher's own deposit first);
  - OA status, licence, best OA URL: OpenAlex > Crossref > OAI (journal-level licence from DOAJ for OAI-only works);
  - abstract: replaced only by a better kind (JATS > plain text > inverted index), never by an empty one.
  
  `work.meta_layer` records which layer owns the bibliographic fields. A work's title is frozen once the finder has
  named it, except through `CLEAN_TITLES`, so name offsets never point into a changed title. `origin`,
  `first_seen_at`, `state` and `text_level` never change, as today; all incoming identifiers are added. (My call.)
- **Late duplicates.** If an identifier arrives that already belongs to another work (two works found to be one), a
  `MERGE_WORKS` task merges them: the older work survives, identifiers, contents and hits move to it (a hit that would
  duplicate one the survivor already has is dropped, so nobody is notified twice), the younger is deleted (mentions
  cascade), and the survivor gets `FIND_NAMES` at `REPROCESS` if its document changed. Merges are logged and counted
  on the status page.
- **Sightings.** `work_sighting` (`work_id`, `layer`, `source_id`, `first_seen`), one row per work and layer, written by
  every upsert. It is the input of the coverage monitor and answers "which layer found this first".

### Coverage monitor and layer health

- **`COVERAGE_CHECK`**, nightly at 06:30 UTC, after all layers ran. Per layer it computes:
  1. **Runs**: the layer's harvest runs of the last 3 days; FAILED, or DEFERRED with no later DONE, is a failure.
  2. **Yield**: works sighted in the last 7 days against the layer's 8-week weekly median.
  3. **Cross-layer misses**: for journals the layer serves, works first seen 3–17 days ago by at least one other layer
     that this layer never sighted. The miss share is counted per layer, and per journal for the journal report.
- **States** per layer: `OK`; `DEGRADED` from the first day with a failed run and no later success, a daily yield of
  zero where the median is above zero, a yield below 50% of the median, or a miss share above 30% where it used to be
  below 10%; `FAILING` when the run failures or the zero yield last 2 consecutive days. A day the OpenAlex budget runs
  out (a `DEFERRED` run that completes the next day) is not a failure. Starting thresholds; they live in properties
  and are tuned after a month of data. (My call: one bad day shows on the status page at once, but only two in a row
  send mail, so a single outage does not cry wolf.)
- Results go into `layer_health` (layer, day, state, runs, yield, median, misses) and per journal into
  `source_layer_coverage` (source, layer, 28-day sightings, misses), shown on `GET /api/admin/status` (`layers`). A
  change to `FAILING`, and the recovery to `OK`, send one e-mail to the admins; a layer that stays bad is reminded
  weekly, not nightly. The indexer only writes `layer_health`; `web`, which already sends mail, sends these from the
  rows it has not mailed yet (the indexer has no mail support). Who the admins are, and where an admin sees the layer
  page: see "Open questions". Nothing is switched off automatically: the other layers keep harvesting, which is the
  point of approach A.
- The same numbers give the **contribution report**: per layer, the works it found first and the works only it found,
  per journal and in total. That is how a layer that adds nothing, or a service turning paid, can be judged.

### Discovery and the journal registry

- **Discovery layers** produce candidates, each a monthly task under `DISCOVER_SOURCES`:
  - OpenAlex seeding (as today, origin `SEED_TOPIC`);
  - DOAJ journals by LCC subjects (Zoology, Botany, Natural history, Microbiology, Ecology, Paleontology …), origin
    `DOAJ`;
  - PKP Beacon, filtered by keywords and activity, origin `PKP_BEACON`, which also yields the OAI route;
  - Plazi `srsStats` per journal, origin `PLAZI`;
  - journal titles of the COL XR references (`3LXR`), normalised to ISSN through Crossref `/journals`, origin
    `COL_REFERENCES`;
  - DOIs of unregistered journals seen by L5, origin `BLR`.
- **One identity for journals.** Candidates are matched to existing sources by ISSN-L (the free ISSN-to-ISSN-L table,
  refreshed monthly), then by any ISSN, then by OpenAlex id; a source without an OpenAlex id is allowed (the column is
  already nullable). `source.origin` gains the new values; a source keeps the origin of its first discovery and lists
  all signals in `source_signal` (source, origin, first_seen, detail jsonb) so an admin sees why it is a candidate.
- **`IDENTIFY_SOURCE`** completes a candidate: ISSN-L and all ISSNs, title, publisher, country (ISSN table, Crossref
  `/journals`, DOAJ), OpenAlex id and topics if OpenAlex knows it, OAI route if PKP Beacon or DOAJ give one (verified
  with `Identify`), Europe PMC presence (one search), and the crosslinks of the journal (below).
- **Classification** stays OpenAlex-first: subfields and topics from OpenAlex where the journal is there (Markus likes
  the current classifications). For a journal OpenAlex does not know, discipline comes from DOAJ LCC subjects mapped to
  the harvested subfields, journal type from its act rate once indexed, taxonomic focus from the index as today.
- Promotion to CORE or BROAD stays an admin decision; it starts the usual onboarding of every route of the journal.
  Name-density feedback (proposals to promote or demote from distinct names per article and act rate) is listed on the
  admin page; it never changes a tier by itself.

### Journal registration

- **Who:** signed-in users (`/api/me` session). Admins keep `POST /api/admin/sources`.
- **Flow:** `POST /api/sources/register` with one of `issn`, `doi` (of an article) or `url`, plus an optional note.
  `web` makes no outbound calls (design rule), so it stores a `source_registration` row (user, input, note, state
  `IDENTIFYING`) and enqueues `IDENTIFY_SOURCE` at `LIVE` with the registration id, answering 202 with the id. The
  indexer identifies the journal and writes the result to the registration: the existing source if Garrulus already
  has the journal (with its tier, so the user learns it is covered or a candidate), a new candidate with title, ISSNs,
  publisher and the routes found, or "not identified" with the reason. `GET /api/me/registrations/{id}` returns it; the
  dialog polls every 2 s for up to 30 s, then says the result will appear in the user's list. If the indexer is
  stopped the registration simply waits. Nothing is harvested. (My call; the first draft identified synchronously in
  `web`, which would break the rule that `web` calls no external service.)
- The same holds for admins adding a route: `POST /api/admin/sources/{id}/routes` stores it as `PENDING` and enqueues
  `VERIFY_ROUTE`, which runs `Identify` in the indexer and sets it `ACTIVE` or `BROKEN`.
- A new source gets origin `REGISTERED`, a `source_signal` with the user id and note, and appears on the admin list
  with its signals. `GET /api/me/registrations` lists the user's registrations and their state (candidate, accepted =
  CORE/BROAD, declined = EXCLUDED). The user gets one e-mail when a registration is accepted or declined.
- Abuse limits: 20 registrations per user per day; registering an existing journal adds a signal, not a row.
- **UI:** a "Register a journal" button on the journals page opens a dialog (ISSN, DOI or address, note), shows the
  identified journal for confirmation, then the result. The account page gets a "Registered journals" list. Admin: the
  source list filters by origin and shows signals; accept/decline is the existing tier change.
- All new public endpoints go into `openapi.yaml` (`OpenApiCoverageTest`).

### Crosslinks

- **`source_identifier`** (`source_id`, `scheme`, `value`), unique on (`scheme`, `value`). Schemes: `ISSN`, `ISSN_L`,
  `OPENALEX`, `WIKIDATA`, `IPNI_PUB`, `BPH`, `ZOOBANK_PUB`, `BHL_TITLE`, `DOAJ`, `OAI`.
- **Journal links** come from `LINK_SOURCE` (on identification and monthly): Wikidata by ISSN (one SPARQL query per
  batch) gives the QID and, where present, IPNI publication (P2008), BPH (P829), ZooBank publication and BHL title ids;
  IPNI's publication search by ISSN fills IPNI and BPH where Wikidata has none.
- **Work links**: the harvest layers add `DOI`, `OPENALEX`, `OAI`, `PMID`, `PMCID`, `BLR` as they go. `LINK_WORK` runs
  for every `INDEXED` work at `BACKFILL` priority and adds, by DOI: Europe PMC (PMID, PMCID), Plazi
  (`Treatments/searchByDOI`), BLR (Zenodo records with `isPartOf` = DOI), ZooBank (`References.json` by DOI, best effort)
  and BHL (BHL API part lookup by DOI, free key). A miss is retried after 30 and 180 days (Plazi and BHL add papers
  late), then never again; a reprocess does not re-query.
- **Display:** `WorkDetail` and `SourceDetail` get `identifiers` (scheme, value, url). URL templates live in one
  registry class (`doi.org`, `openalex.org`, `europepmc.org/article/MED|PMC`, `zoobank.org/References/`,
  `tb.plazi.org`, `zenodo.org/records/`, `biodiversitylibrary.org/part|bibliography/`, `ipni.org/p/`, `wikidata.org`,
  and Biodiversity PMC by DOI or PMCID). The work and journal pages show them as a row of labelled links under the
  bibliographic block. Public `GET /api/works?identifier=<scheme>:<value>` finds a work by any identifier (OpenAPI).

### Abstract and full-text fallbacks

- `RESOLVE_CONTENT` keeps its route order for full text and adds two things:
  - **OAI JATS**: a work whose L3 record came in `jats` with an open licence has its full text already; it becomes the
    first full-text route for those journals (JoTT today).
  - **Abstract top-up** when a work still has no abstract after its routes: Europe PMC (by PMID or DOI), the Springer
    Nature Meta API (DOIs `10.1007/`, `10.1186/`, `10.1038/`; free key; off without one), SciELO ArticleMeta (SciELO
    journals), CiNii Research (Japanese journals; free appid; only abstracts flagged as reusable), then the institutional
    layer when enabled. `abstract_source` gains `EUROPEPMC`, `SPRINGER`, `SCIELO`, `CINII`, `OAI`, `INSTITUTIONAL`.
    A work whose abstract arrives later gets `FIND_NAMES` again (as a JATS abstract does today).

### Institutional access (University of Copenhagen)

Garrulus will run in the KU network as part of COL and GBIF, which reaches many subscribed journals. M6 builds the
route, off by default:

- **Route `INSTITUTIONAL`**, after every open route and only for works with no open full text: the publishers'
  text-mining APIs for subscribers (Elsevier TDM API, Wiley TDM API, Springer Nature subscription content via the KU IP
  range, others on an allow-list), each with its institutional token or IP authorisation, its own `HostLimiter` and a
  daily cap. No scraping of publisher web pages, no PDF downloads from article pages in M6.
- Text obtained this way is closed: it goes to the spool, the finder runs, the spool is purged; only mentions, offsets,
  ≤ 200-character snippets, hashes and the URL are kept, as for every closed text. Abstracts obtained this way are
  stored and shown only if the licence allows display; otherwise they are used for name finding only.
- Every fetch is logged with work, publisher API and date (an audit table), so usage can be reported to KU.
- **Switch** `nametracker.content.institutional.enabled=false` plus a per-publisher allow-list. It may be switched on
  only after the KU library confirms in writing which licences permit text and data mining for this use, and whether
  Garrulus counts as research by a research organisation under the EU text-and-data-mining exception (DSM Directive
  Art. 3, implemented in Danish copyright law). That confirmation is Markus' to obtain; the design does not assume it.

### Tasks

New task types (all idempotent, continuation pages, outage deferral as in ADR-0004):
`HARVEST_OAI` (route or platform, position = resumption token), `HARVEST_EUROPEPMC` (ISSN batch, cursor),
`HARVEST_BLR` (since, page), `DISCOVER_SOURCES` (origin), `IDENTIFY_SOURCE` (`sourceId` or `registrationId`),
`VERIFY_ROUTE` (`routeId`), `LINK_SOURCE` (`sourceId`), `LINK_WORK` (`workId`), `COVERAGE_CHECK` (`day`),
`MERGE_WORKS` (`survivor`, `other`), `ONBOARD_ROUTE` (`routeId`, the 120-day pass of a new route). Admin:
`POST /api/admin/sources/{id}/routes` to add or pause a route (stored `PENDING`, verified by `VERIFY_ROUTE`).

## Open questions for Markus

These change scope, so the builder must not guess them (from the builder check, 2026-11-19):

1. **Admins and an admin UI.** Today admin access is the `X-Admin-Token` header only (ADR-0006); `app_user` has no
   role and there are no admin pages, although design.md §8 planned admins named by ORCID. M6 needs somewhere to send
   layer mails and somewhere to accept registrations and read layer health.
2. **History for journals OpenAlex does not have.** The backfill (M5b) reads only the OpenAlex snapshot, so a journal
   that came from PKP Beacon, DOAJ or a registration starts with 120 days. Whether M6 backfills such journals through
   their own routes, and how old a work that L5 or a full OAI read brings in may be, is open.
3. **Ranking candidates.** Discovery may add thousands of candidates, but nothing is harvested before promotion, so
   name density can rank only journals already harvested. Whether candidates get a small hidden sample harvest is open.

### Storage

`work_identifier` about 4–7 rows per work and `work_sighting` about 2–4: at 5M works roughly 50M rows, 5–7 GB with
indexes, on top of the 150–200 GB of the full backfill. `source_*` tables are small.

## Done when

- The five layers run daily on the dev DB for a week next to each other; `layer_health` is `OK` for all of them, and
  the contribution report shows what each found first and alone.
- Blocking `api.openalex.org` (a host override) still brings new works the next day through the other layers, with no
  duplicate works; `COVERAGE_CHECK` reports L1 `DEGRADED` after the first day and `FAILING` after the second, and one
  admin e-mail is sent; unblocking returns it to `OK` (one recovery mail) and L1 back-fills through its window without
  new hits for the works others already brought.
- OAI routes are active for at least the Pensoft journals, Magnolia Press, EJT, JoTT and two platforms, and their first
  passes ran as `ONBOARDING`.
- A user registers a journal by ISSN and by article DOI; both appear as candidates with their signals and identified
  routes; registering a covered journal reports it as covered.
- A ZooKeys work shows DOI, OpenAlex, PMID/PMCID, BLR, Plazi and Biodiversity PMC links; Phytotaxa shows IPNI and BPH
  on its journal page; `GET /api/works?identifier=PMCID:…` finds the work.
- Discovery adds candidates from DOAJ, PKP Beacon, Plazi and COL references, each with its signal.
- The institutional route exists, is covered by tests against a stub, and is off.
- No call to a paid service in the logs of the week.

## Left to the builder

- Exact thresholds of the coverage monitor (start values above), schedules within the night, page sizes.
- The DOAJ LCC-to-subfield mapping and the keyword filter for PKP Beacon (start from the research report's filter).
- The Wikidata query shape and batch size; IPNI date filters are not needed (lookups by ISSN only).
- URL templates: verify each against a live example before shipping; drop a scheme whose service does not answer.
- Whether `LINK_WORK` runs per work or in batches per service (Europe PMC and Zenodo accept batched queries).
- The admin layer page's layout, within the brand tokens.
