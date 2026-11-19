# M6: layered harvesting, crosslinks and journal registration — design

Date: 2026-11-19. Status: draft for Markus' review (decisions of 2026-11-19 included: approach A, admins by ORCID with admin pages, route backfill, candidate sampling).

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
8. **Admins and admin pages.** Admins are named by ORCID; they get admin pages for journals (candidates,
   registrations, routes), layer health and status, and the layer and registration mails.
9. **History and ranking for the long tail.** Journals OpenAlex does not have get their history back to 2000 through
   their own routes, and candidates are ranked by a small hidden sample of their recent papers.
10. **University of Copenhagen access, prepared but off.** A content layer for subscription full text and abstracts
   through the publishers' text-mining APIs, reachable from the KU network, behind a switch that stays off until the
   licences are cleared (see "Institutional access").
11. **Languages and English titles** (Markus, 2026-11-19: option C, and "capture the language of works so we can also
   filter on them and do metrics"). Every work has a reliable language, usable as a filter, a facet and a metric; the
   English title and abstract a source supplies are kept; titles with no English version get a stored machine
   translation; abstracts are translated in the reader's browser on request.

## Non-goals

- Paid services of any kind (Markus, 2026-11-19: "strictly avoid paid services"). OpenAlex runs on its free daily
  allowance only, as today.
- Harvesting BHL content. BHL appears only as identifiers and links.
- Scraping publisher websites or PDFs; generic publisher RSS/Atom feeds as a harvest layer (they carry 10–100 items and
  often no abstract; at most a later signal; the Magtech feeds of Chinese journals are the exception, see L3); crawling
  journal sites that are not on Magtech (e.g. Zoological Systematics' own site); J-STAGE (needs written permission) and
  KCI (needs a key application). All stay possible later behind the same layer SPI.
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
| L3 Journal feeds | OAI-PMH per journal (OJS) or per platform set; Magtech RSS for Chinese journals | onboarded journals with a route (below) | daily 04:30 | metadata, abstract, DOI or OAI id / Magtech article id, sometimes JATS full text |
| L4 Europe PMC | search by ISSN and `FIRST_IDATE` | onboarded journals that PubMed/Europe PMC index | daily 05:00 | metadata, abstract, PMID, PMCID, OA full text flag |
| L5 BLR | Zenodo community `biosyslit`, `created` since last run | every journal (signal for new taxonomic DOIs) | daily 05:15 | article DOIs with treatments and their acts |

- **Every layer creates works.** L2 already creates a work from Crossref metadata when OpenAlex lacks it; it keeps that,
  but no longer waits on OpenAlex: the `/works/doi` lookup becomes an enrichment (when L1 is up) instead of the path.
  L3 and L4 create works from their own records. L5 creates a work only when the DOI's journal is onboarded; a DOI of
  an unregistered journal is a discovery signal (it feeds the candidate list, see "Discovery").
- **A layer SPI** (the `HarvestAdapter` of the design, finally): `HarvestLayer` with `layer()`, `servesSource(source)`,
  `firstPage(sourceBatch, window)` and `nextPage(position)`, each page returning `WorkRecord`s with their identifiers.
  The existing OpenAlex and Crossref handlers are wrapped, not rewritten. Each layer has its own `harvest_run.adapter`
  value (`OAI`, `MAGTECH`, `EUROPEPMC`, `BLR` join `OPENALEX_LIVE`, `OPENALEX_SWEEP`, `CROSSREF`), its own dedup key prefix and
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
- **Magtech RSS route** (Markus, 2026-11-19: "Chinese and Indian scientists do a major if not the majority of current
  taxonomic work, so capturing them properly is important"). Many Chinese journals run on the Beijing Magtech platform,
  whose DOIs are registered with CNKI or ISTIC rather than Crossref, so L1 and L2 miss them, and which has no OAI-PMH.
  Its RSS feeds are structured, though: `<site>/EN/rss_dqml_<ISSN>.xml` (current issue), `rss_first_<ISSN>.xml`
  (online first) and per-section feeds, listed on `<site>/EN/rss/showRssInfo.do`. Checked 2026-11-19 on Biodiversity
  Science: 20 items, each with the English title and an English abstract (about 2,600 characters, as HTML keeping the
  `<i>` of the names), authors and the article page link, but no DOI, and a `pubDate` that did not look current;
  Acta Entomologica Sinica's listed feeds answered with a 404 page. Hence:
  - a `MAGTECH` route per journal (site and ISSN), polled daily for current issue and online first, verified by
    `VERIFY_ROUTE` (a feed with items, else `BROKEN`);
  - identity by the new scheme `MAGTECH` (host plus the numeric article id of the link), plus the DOI read from the
    article page's `citation_doi` meta tag: one polite request per new article, the only page Garrulus reads there
    (metadata only, no full text: the sites say "All Rights Reserved");
  - the publication date from the page's `citation_publication_date` where the feed's is missing or stale;
  - the abstract (HTML with italics) as an abstract of kind `MAGTECH`, converted like a JATS abstract;
  - the CNKI/ISTIC DOI as an ordinary `DOI` identifier (doi.org resolves it), so a later Crossref or OpenAlex record
    of the same paper merges.
  Discovery finds Magtech journals from the COL references, Plazi statistics and the "Powered by Beijing Magtech"
  footer of a registered journal's site; a journal also on another layer simply has both.
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
  `PMCID`, `BLR` (Zenodo record id), `PLAZI` (Plazi article/document id), `ZOOBANK` (reference UUID), `BHL_PART`, `MAGTECH` (host and article id).
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
  rows it has not mailed yet (the indexer has no mail support), to the admins (see "Admins and admin pages"), whose
  Layers page shows the same numbers. Nothing is switched off automatically: the other layers keep harvesting, which
  is the point of approach A.
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
- **Chinese and Indian journals** get explicit attention, since much of today's taxonomic work comes from them. Chinese
  journals on Magtech get the Magtech route (L3). Indian taxonomy journals mostly publish in English and sit in
  Crossref (e.g. JoTT) or on OJS, which the PKP Beacon list and OAI cover. The journal's country is part of every
  coverage figure (see "Done when"), so gaps in either country show up.
- **Classification** stays OpenAlex-first: subfields and topics from OpenAlex where the journal is there (Markus likes
  the current classifications). For a journal OpenAlex does not know, discipline comes from DOAJ LCC subjects mapped to
  the harvested subfields, journal type from its act rate once indexed, taxonomic focus from the index as today.
- Promotion to CORE or BROAD stays an admin decision; it starts the usual onboarding of every route of the journal,
  and for a journal OpenAlex does not have, its route backfill. Candidates are ranked by their sample and signals
  ("Candidate sampling"); harvested journals by name-density feedback (proposals to promote or demote from distinct
  names per article and act rate). Both are shown on the admin journal page; neither changes a tier by itself.

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

- **The list of identifier sources will grow** (Markus, 2026-11-19). A scheme is therefore data, not schema: one
  `IdentifierScheme` registry in `store` (code, label, entity: work, journal or both, value normaliser, URL template,
  sort order on the page) holds every scheme, and the `scheme` columns are plain text. Adding a source is one registry
  entry plus, if its ids are not delivered by a harvest layer, one `IdentifierLookup` (an SPI with `scheme()`,
  `appliesTo(work|source)`, `lookup(…)`) that `LINK_WORK` or `LINK_SOURCE` picks up; no migration, no API change (the
  API lists whatever identifiers a work or journal has). A new lookup back-fills by enqueuing `LINK_*` for existing
  works and journals at `BACKFILL` with `{"schemes":[…]}`.
- **Crossref and OpenAlex stay among the links** (Markus, 2026-11-19), for works and journals alike: `OPENALEX` (W… and
  S… ids, linking to openalex.org), `DOI` (doi.org) plus a `CROSSREF` link to Crossref's record of the DOI, and for
  journals `CROSSREF_JOURNAL` (Crossref's record of the journal by ISSN). Both are filled by their harvest layers, and
  `LINK_*` fills them for works and journals that arrived only through other layers.
- **`source_identifier`** (`source_id`, `scheme`, `value`), unique on (`scheme`, `value`). Initial schemes: `ISSN`,
  `ISSN_L`, `OPENALEX`, `CROSSREF_JOURNAL`, `WIKIDATA`, `IPNI_PUB`, `BPH`, `ZOOBANK_PUB`, `BHL_TITLE`, `DOAJ`, `OAI`.
- **Journal links** come from `LINK_SOURCE` (on identification and monthly): Wikidata by ISSN (one SPARQL query per
  batch) gives the QID and, where present, IPNI publication (P2008), BPH (P829), ZooBank publication and BHL title ids;
  IPNI's publication search by ISSN fills IPNI and BPH where Wikidata has none.
- **Work links**: the harvest layers add `DOI` (with its `CROSSREF` link), `OPENALEX`, `OAI`, `PMID`, `PMCID`, `BLR`
  as they go. `LINK_WORK` runs
  for every `INDEXED` work at `BACKFILL` priority and adds, by DOI: Europe PMC (PMID, PMCID), Plazi
  (`Treatments/searchByDOI`), BLR (Zenodo records with `isPartOf` = DOI), ZooBank (`References.json` by DOI, best effort)
  and BHL (BHL API part lookup by DOI, free key). A miss is retried after 30 and 180 days (Plazi and BHL add papers
  late), then never again; a reprocess does not re-query.
- **Display:** `WorkDetail` and `SourceDetail` get `identifiers` (scheme, label, value, url). URL templates live in
  the scheme registry (`doi.org`, Crossref's record of a DOI or ISSN, `openalex.org`, `europepmc.org/article/MED|PMC`, `zoobank.org/References/`,
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
    layer when enabled. `abstract_source` gains `EUROPEPMC`, `SPRINGER`, `SCIELO`, `CINII`, `OAI`, `MAGTECH`, `INSTITUTIONAL`.
    A work whose abstract arrives later gets `FIND_NAMES` again (as a JATS abstract does today).

### Languages and English titles

Option C, decided by Markus on 2026-11-19: capture what the sources give in English, machine-translate and store
titles only, translate abstracts in the browser. Paid translation services stay out.

- **The language of a work** (`work.language`, ISO 639-1, else 639-3) becomes a decided value instead of OpenAlex's
  guess (the dev DB has 148 works OpenAlex calls Uzbek, mostly mis-detections). It is decided in this order, first
  that applies:
  1. the language of the full-text document the finder read (detected on its body text);
  2. the article language a source declares for the article: OAI `dc:language`, the `citation_language` meta tag of a
     Magtech page, SciELO's original language, Europe PMC's `language`;
  3. detection on the original title and abstract (a local detector library such as Lingua, Apache-2.0, offline),
     ignoring any parallel English text, accepted only above a confidence threshold;
  4. OpenAlex's `language`;
  5. the journal's single publication language, if DOAJ or the ISSN record gives exactly one.

  `work.language_source` records which rule decided it. `DETECT_LANGUAGE` re-decides it for existing works once (at
  `BACKFILL`, batched) and whenever new evidence arrives (a full text, a declared language).
- **`work_text`** (`work_id`, `field` TITLE or ABSTRACT, `lang`, `text`, `kind` ORIGINAL, PARALLEL or MACHINE,
  `source` layer or engine, `model`, `created`) holds every language version of title and abstract. `work.title` and
  the abstract columns stay the original (the finder's offsets point into them).
  - **PARALLEL**: an English (or other) version the source supplies. Magtech feeds give English title and abstract;
    SciELO, J-STAGE/CiNii, OAI records with several `dc:title`/`dc:description` in different `xml:lang`, and Crossref
    `original-title` vs `title` often do too. Every layer stores these when it sees them.
  - **MACHINE**: only for **titles**, only when the work's language is not English and no English PARALLEL title
    exists. `TRANSLATE_TITLE` (enqueued after the language is decided, `BACKFILL` priority) calls a `TitleTranslator`
    SPI; the default implementation calls a self-hosted open machine-translation service with open models (for
    example LibreTranslate with Argos models, or OPUS-MT on CTranslate2), running natively on the VM like gnfinder.
    Engine and model version are stored; a model upgrade can re-translate with `{"all":true}`. Off
    (`nametracker.translation.enabled=false`) until the service is installed; then non-Latin scripts first (Chinese,
    Japanese, Korean, Cyrillic, Arabic, Persian, Thai …), Latin-script languages after.
  - Abstracts are never machine-translated on the server: storing translations of copyrighted abstracts is avoided.
- **Display.** Lists, digests, feeds and the API show the original title plus the English one when it differs, labelled
  "English title" (parallel) or "Machine translation" (machine). On the work page, title and abstract carry their
  `lang` attribute; a "Translate" button next to a non-English abstract (and a non-English title without an English
  version) uses the browser's on-device translator where available (Chrome's built-in Translator API) and otherwise
  points to the browser's own page translation. The original keeps its name highlighting; a translation is shown
  below it as plain text.
- **Search.** `work.fts` also covers the English title from `work_text` (parallel or machine), so an English query finds
  a Chinese paper by its English title.
- **Filters and metrics.**
  - `/api/works` gains `language` (repeatable) and the facet `language`, also on the taxon, name and journal pages'
    lists; `/api/works/{id}` returns `language`, `languageSource` and `titles`/`abstracts` with their kind.
  - `/api/taxa/{colId}/stats` and `/api/sources/{id}` gain a breakdown by language (works per language, per year).
  - The admin Layers page and the contribution report break coverage down by language as well as country.
  - A public `GET /api/stats/languages` gives works per language and year, filterable by `taxgroup`, `code`,
    `country` and `yearFrom`/`yearTo`, for metrics on who publishes taxonomy in which language. All in the OpenAPI.

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
`MERGE_WORKS` (`survivor`, `other`), `ONBOARD_ROUTE` (`routeId`, the 120-day pass of a new route), `ROUTE_BACKFILL`
(`sourceId`, `fromYear`, position per route), `SAMPLE_SOURCE` (`sourceId`), `DETECT_LANGUAGE` (`afterId`, `batch`), `TRANSLATE_TITLE` (`workId`; or `{"all":true}`). Admin:
`POST /api/admin/sources/{id}/routes` to add or pause a route (stored `PENDING`, verified by `VERIFY_ROUTE`).

### Admins and admin pages (Markus, 2026-11-19: 1a)

- **Admins are named by ORCID** in `NAMETRACKER_ADMIN_ORCIDS` (comma-separated iDs), as design.md §8 and Blixa do. An
  account signed in through ORCID whose iD is listed gets the role `ADMIN` in its session; e-mail sign-in alone never
  makes an admin. The list is read at start, so a change needs a restart.
- `/api/admin/**` accepts either the `X-Admin-Token` header (scripts, as today; still exempt from the XSRF check) or an
  `ADMIN` session (the browser; XSRF required like every other session write). ADR-0006 gets an amendment saying so.
- **Admin pages** in the SPA under `/admin`, shown in the menu only to admins:
  - **Journals:** candidates and registrations, filterable by origin and tier, sorted by the candidate score (below),
    with their signals, sample results and routes; accept (CORE or BROAD) and decline (EXCLUDED) are the existing tier
    change; routes can be added or paused there.
  - **Layers:** `layer_health` per layer and day, the contribution report and the per-journal coverage.
  - **Status:** today's `GET /api/admin/status` rendered (tasks, works, runs, XR, backfill, rollouts).
- **Admin mail** (layer alerts, a daily note when new registrations wait) goes to the verified e-mail addresses of the
  admin accounts; with no admin e-mail known it is logged as suppressed, like other mails without SMTP.

### Route backfill for journals OpenAlex does not have (Markus, 2026-11-19: 2a)

- **`ROUTE_BACKFILL`** (`sourceId`, `fromYear` 2000) runs the journal's own routes back to 2000 at `BACKFILL` priority:
  OAI `ListRecords` without `from` (most endpoints serve their whole history), Crossref by every ISSN with
  `from-pub-date:2000`, Europe PMC by ISSN and publication year. Works get origin `BACKFILL` and go through content,
  finder and matching at `BACKFILL`, as the snapshot backfill's do.
- It runs once after onboarding for every journal that has no OpenAlex id or that the snapshot backfill did not cover,
  and an admin can start it for any journal (the dedup key holds one run per journal).
- **Age rule for every layer:** a record published before 2000 is skipped and counted (pre-2000 stays a non-goal); a
  record older than the layer's window gets origin `BACKFILL` (it is outside the alert window anyway). This covers L5
  DOIs of old papers and the weekly full reads of endpoints with unreliable datestamps.

### Candidate sampling (Markus, 2026-11-19: 3b)

- **`SAMPLE_SOURCE`** (`sourceId`) ranks a candidate by what its papers contain, without listing or alerting anything:
  it fetches up to 20 of the journal's most recent articles (last 2 years) through the cheapest route that serves it
  (Crossref by ISSN, then OAI, then Europe PMC, then OpenAlex by source id), runs the finder over title and abstract,
  and stores the result in `source_sample` (source, sampled_at, articles, articles with abstract, distinct names per
  article, share of articles with an act, the top taxonomic groups by the COL dictionary's classification).
- Sample papers are never written to `work`: they live only inside the task, so they can never be listed, matched or
  alerted. Only the aggregate row is kept.
- A **candidate score** combines the sample (names per article, act share) with the signals (number and kind, Plazi
  article count, COL reference count); the admin journal list sorts by it. Sampling runs for new candidates and
  registrations, at most 200 a night at `BACKFILL` priority, and again after a year; a journal without any reachable
  route keeps a score from its signals only.

### Storage

`work_identifier` about 4–7 rows per work and `work_sighting` about 2–4: at 5M works roughly 50M rows, 5–7 GB with
indexes, on top of the 150–200 GB of the full backfill. `source_*` tables are small. Route backfills add the long
tail's history (expected to be small next to the OpenAlex snapshot backfill; measured on the first runs).

## Done when

- The five layers run daily on the dev DB for a week next to each other; `layer_health` is `OK` for all of them, and
  the contribution report shows what each found first and alone.
- Blocking `api.openalex.org` (a host override) still brings new works the next day through the other layers, with no
  duplicate works; `COVERAGE_CHECK` reports L1 `DEGRADED` after the first day and `FAILING` after the second, and one
  admin e-mail is sent; unblocking returns it to `OK` (one recovery mail) and L1 back-fills through its window without
  new hits for the works others already brought.
- Magtech routes are active for at least five Chinese taxonomy journals (Biodiversity Science among them); their works
  carry English titles and abstracts with italics and, where the page has one, their CNKI/ISTIC DOI.
- The contribution report and the admin journal list break coverage down by the journal's country, so the share of
  Chinese and Indian journals harvested, and through which layers, is visible.
- OAI routes are active for at least the Pensoft journals, Magnolia Press, EJT, JoTT and two platforms, and their first
  passes ran as `ONBOARDING`.
- A user registers a journal by ISSN and by article DOI; both appear as candidates with their signals and identified
  routes; registering a covered journal reports it as covered.
- A ZooKeys work shows DOI, Crossref, OpenAlex, PMID/PMCID, BLR, Plazi and Biodiversity PMC links; Phytotaxa shows
  OpenAlex, Crossref, IPNI and BPH on its journal page; `GET /api/works?identifier=PMCID:…` finds the work.
- A test adds a scheme and its lookup without a migration, and its links appear on the pages after the back-fill.
- Discovery adds candidates from DOAJ, PKP Beacon, Plazi and COL references, each with its signal; candidates are
  sampled and sorted by their score on the admin journal page, and no sample paper appears in `work`.
- An account whose ORCID is in `NAMETRACKER_ADMIN_ORCIDS` sees the admin pages, accepts a registered journal from
  there, and receives the layer mail of the OpenAlex block test; other accounts get 403 on `/api/admin/**`.
- A promoted journal without an OpenAlex id gets its history back to 2000 through its routes, at `BACKFILL`, with no
  hits; records before 2000 are skipped and counted.
- The institutional route exists, is covered by tests against a stub, and is off.
- Every work has a decided language with its source; the 148 "Uzbek" works of the dev DB are re-decided; `/api/works`
  filters and facets by language, and `GET /api/stats/languages` answers per year and taxgroup.
- Magtech and SciELO works carry their parallel English titles and abstracts; with the translation service running,
  non-English titles without an English version get a machine translation, labelled as such, and are found by an
  English search; abstracts are translated only in the browser.
- No call to a paid service in the logs of the week.

## Left to the builder

- Exact thresholds of the coverage monitor (start values above), schedules within the night, page sizes.
- The DOAJ LCC-to-subfield mapping and the keyword filter for PKP Beacon (start from the research report's filter).
- The Wikidata query shape and batch size; IPNI date filters are not needed (lookups by ISSN only).
- URL templates: verify each against a live example before shipping; drop a scheme whose service does not answer.
- Whether `LINK_WORK` runs per work or in batches per service (Europe PMC and Zenodo accept batched queries).
- The admin pages' layout, within the brand tokens.
- The exact candidate score formula (start: names per article and act share of the sample, weighted with Plazi and COL
  reference counts), tuned once a few hundred candidates are sampled.
- The machine-translation runtime and models (LibreTranslate/Argos or OPUS-MT/CTranslate2): pick by licence, quality on
  a sample of Chinese, Japanese and Russian taxonomic titles, and memory on the VM; and the language detector's
  confidence threshold.
