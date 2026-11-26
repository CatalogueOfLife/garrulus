# ADR-0010: Crosslinks, languages and English titles, fallbacks, mentioned-in, institutional access

Date: 2026-11-21. Status: accepted.

## Context

M6c is the last part of M6. It links works and journals to the other services that know them, decides the language of
every work and gives non-English titles an English version, adds abstract and full-text fallbacks, exposes where a
name occurs (title, abstract, body), and builds the University of Copenhagen (KU) route, off. The design is
`docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md` (M6c sections); this ADR records what was built and
the rulings made on the way, which override the spec and the plan where they differ. Configuration, task types and
endpoints are in CLAUDE.md and are not repeated here. The dev check is `docs/m6c-dev-check.md`.

Binding constraints: **free only** (keyed services are off until their free key is set), every handler idempotent,
every outage defers without using an attempt (ADR-0004), **`web` makes no outbound call**, **a machine translation is
never presented as the original and never given to the finder**, **the KU route is off and its text closed**,
expand-only migrations: **V21 to V24**.

## Decisions

### Migrations (V21 to V24)

- **V21** (one file): work language columns (`language_source`, `language_decided_at`, `title_lang`, `abstract_lang`,
  `title_en`, `title_en_kind`), the widened `abstract_source` and `content.route` checks (`OAI_JATS`, `INSTITUTIONAL`),
  `work.fts` regenerated with `title_en`, and the tables `work_text`, `work_language_evidence` (with a `LEGACY` row per
  work that had a language, source `V21`), `source_identifier`, `link_attempt`, `text_lookup`, `institutional_fetch`,
  `layer_contribution_language`, plus `source.languages` and `subscription.mentioned_in`.
- **V22** widens `content.kind` with `ELSEVIER_XML` (Elsevier's TDM answer is neither JATS nor TEI). V21 was not edited
  because it might already be applied somewhere.
- **V23** adds `taxon_language_stats` and `language_stats` (below). It was edited in place during the build, which was
  safe because it had not been applied anywhere.
- **V24** adds `job_cursor` (name, position): where a recurring job that walks a table by id stopped. Its first user is
  the link sweep's walk over works no lookup has seen (below). No existing table keeps such state, and the task rows
  that could carry it are purged after 14 days. Expand-only: one new table.
- **V21 to V24 apply together at the M6c deploy.** V21's `work.fts` `SET EXPRESSION` rewrites the whole `work` table
  under an ACCESS EXCLUSIVE lock: instant on the empty GBIF database, blocking `web` for the whole rewrite on a populated
  one. So they are applied before the full backfill (same rule as V16, ADR-0008). Cost if wrong: a maintenance window.

### Crosslinks: schemes are a registry

- **A scheme is data, not schema.** `IdScheme` (store) holds code, label, entities (work, journal or both), a value
  normaliser, a URL template per entity and a sort order; `IdentifierRegistry` collects the built-ins plus any
  `IdScheme` bean. The `scheme` columns are plain text. Adding a source is one `IdScheme` and, if no harvest layer
  delivers its ids, one `IdentifierLookup`; no migration, no API change. `LinkWorkHandlerIT.newSchemeAndLookupNeedNoMigration`
  proves it (the Flyway history is unchanged across the test). A new lookup back-fills with
  `LINK_WORK {"afterId":0,"schemes":[…]}`.
- **Templates are verified against a live example** (2026-11-19, `curl --max-time 30 --retry 2`, Garrulus user agent).
  **`PMID`, `PMCID` and `DOAJ` are pending a browser check**: europepmc.org and doaj.org answer 403 to scripted clients
  (bot filters). `define` accepts only `http(s)://` templates, and a host placeholder renders only for a plain host name
  (an `@` would move the authority). The frontend renders only `http(s)` URLs as links (`rel="noopener noreferrer"`),
  anything else as text.

  | Scheme | Entity | URL template | Evidence |
  |---|---|---|---|
  | `DOI` | work | `https://doi.org/{v}` | 302 to the publisher |
  | `CROSSREF` | work | `https://api.crossref.org/works/{v}` | 200 JSON (Crossref's record) |
  | `OPENALEX` | work / journal | `https://openalex.org/works/{v}` / `https://openalex.org/sources/{v}` | `openalex.org/W…`, `/S…` answer 301 to these |
  | `PMID` | work | `https://europepmc.org/article/MED/{v}` | europepmc.org answers 403 to scripts: browser check pending; fallback `https://pubmed.ncbi.nlm.nih.gov/{v}/` (203) |
  | `PMCID` | work | `https://europepmc.org/articles/{v}` | the URL Europe PMC's REST API returns in `fullTextUrlList`; browser check pending |
  | `BIODIVERSITY_PMC` | work | `https://biodiversitypmc.sibils.org/{v}` (v = `pmc/PMC…` or `medline/<pmid>`) | SPA route `/:collection/:id`; `/api/fetch?ids=…&col=` confirms the document |
  | `BLR` | work | `https://zenodo.org/records/{v}` | 200 |
  | `PLAZI` | work | `https://tb.plazi.org/GgServer/summary/{v}` | 200, article page (id from srsStats `DocArticleUuid`) |
  | `BHL_PART` | work | `https://www.biodiversitylibrary.org/part/{v}` | doi.org's redirect target for `10.5962/bhl.part.14315` |
  | `MAGTECH` | work | `https://{0}/EN/abstract/abstract{1}.shtml` (value `host:id`) | 200 |
  | `ISSN`, `ISSN_L` | journal | `https://portal.issn.org/resource/ISSN/{v}` | 200 |
  | `CROSSREF_JOURNAL` | journal | `https://api.crossref.org/journals/{v}` | 200 JSON |
  | `WIKIDATA` | journal | `https://www.wikidata.org/wiki/{v}` | 200 |
  | `IPNI_PUB` | journal | `https://www.ipni.org/p/{v}` | 200 (Phytotaxa `20011462-1`) |
  | `BHL_TITLE` | journal | `https://www.biodiversitylibrary.org/bibliography/{v}` | doi.org's redirect target for `10.5962/bhl.title.39620` |
  | `DOAJ` | journal | `https://doaj.org/toc/{v}` | Wikidata P5115 formatter; doaj.org answers 403 to scripts: browser check pending |
  | `BPH` | journal | none (shown as text) | no per-record page; Wikidata P4569 has no formatter URL |
  | `OAI` | both | none, not shown | an endpoint, not a page |
- **ZooBank is out** (Markus, 2026-11-20: "ignore zoobank for now, dont record their work ids"): no ZooBank scheme, no
  lookup and no stored id, **including the ZooBank ids that Plazi and BLR records carry** (the Wikidata query asks no
  P2007). Every probe of ZooBank got a human-verification page or a 404. `NoZooBankLookupIT` guards it. Returns as one
  registry entry and one lookup if Markus asks.
- **The BHL API lookup is dropped** (it needs a key and its documentation answers 403 to scripts). `BHL_PART` comes
  only from BHL's own `10.5962/bhl.part.<id>` DOIs (`bhl-doi`, no call).
- **BPH is shown as text** (no link): it has no per-record page. The spec's Wikidata property P829 is OEIS; BPH is
  **P4569**, which has no formatter URL.
- **`BIODIVERSITY_PMC`** has no identifiers of its own: a row is `<collection>/<id>` (`pmc/PMC…` or `medline/<pmid>`),
  written only when its lookup confirms the document, and links to the SPA route of that id.
- **Journal identity is not mirrored** (pre-flight H6): `ISSN`, `ISSN_L` and `OPENALEX` of a journal are read from the
  source columns at read time; `source_identifier` holds only lookup schemes (`WIKIDATA`, `IPNI_PUB`, `BPH`,
  `BHL_TITLE`, `DOAJ`, `CROSSREF_JOURNAL`). A second copy of the identity would have to follow every fold, admin edit
  and ISSN normalisation of M6b. `SourceFold` moves the `source_identifier` rows and unions `languages`.
- **The Crossref layer stores `CROSSREF:<doi>`** on every work it writes (one more `work_identifier` row per Crossref
  work), so the link exists without a lookup.

### `LINK_WORK` and `LINK_SOURCE`

- **When:** `LINK_WORK {"workId"}` at `BACKFILL` when a work is indexed for the first time (`MATCH_NAMES` sees
  `first_indexed_at` null; a re-index does not query again), **except for works of the snapshot backfill (origin
  `BACKFILL`) and while linking is off** (the master switch, or no work lookup enabled: `LinkGate`), so no task waits
  for a handler that is off; batched (`{"afterId","batch","schemes"}`) for back-fills. `LINK_SOURCE` at the end of a
  journal's completion (with the fold survivor's id) and monthly for every journal. **Origin `BACKFILL` is wider than the
  snapshot backfill:** route backfills (`ROUTE_BACKFILL`), the Crossref history (`HARVEST_CROSSREF_HISTORY`) and every
  layer's records older than the live window (120 days) are stored as `BACKFILL` too. One task per such work would turn
  the batched lookups (Crossref 20 DOIs, OpenAlex 50, Europe PMC 25, Zenodo 100 a call) into one call per work, about
  5M Crossref and 5M Europe PMC calls at the GBIF backfill instead of about 250k and 200k. **They, and works indexed
  while linking was off, are caught up by the daily sweep** (below), in batches. The spec's "for every INDEXED work" is
  met that way; a paged `LINK_WORK {"afterId":0}` (dev check) is an optional speed-up, e.g. right after the deploy or
  the full backfill. Cost if wrong: such works wait for the sweep (at most `retry-per-day` a day).
- **Attempts:** a miss is asked again after 30 days, then after 180 days, then never (Plazi and BHL add papers late); a
  hit is never asked again. A daily sweep enqueues the subjects with due rows of enabled lookups and listed works
  (sources not CEASED), in id order, as `{"ids":[…]}` tasks of 5000, **up to `garrulus.links.retry-per-day`
  (100,000) per entity and day** (the rest waits for the next day: the misses of a 5M-work backfill come due together,
  and 5000 a day would lag by years), and deletes rows of deleted subjects; a due row a lookup no longer applies to is
  cleared, so skipped rows cannot starve the sweep.
- **Never-tried works.** Within what the retries leave of that budget, the sweep queues listed works without any
  `link_attempt` row (key `link-untried:<day>:<first id>`), walking `work` by id from a cursor kept in `job_cursor`
  (`link-untried:W`); at the end it starts over at 0 the next day, so works indexed late below the old position are
  found on the next pass. A work that went through every enabled lookup with none applying gets a marker row (lookup
  `none`, no retry), so it is not queued again; "seen" means any row, so a work tried only by a lookup now switched off
  is not re-queued (a new or re-enabled lookup back-fills with `{"schemes":[…]}`). The query walks `work_pkey` and
  probes `link_attempt`'s primary key. Nothing while no work lookup is enabled. Before (fix wave I2 alone) such works
  were never linked.
- **A blocked lookup does not stop the others** (final review I1). When a lookup's service is down or its daily cap is
  spent, its remaining due targets of the run are put off to the exception's `retryAt` without counting a try (a new
  `link_attempt` row has `attempts` 0, an existing one only a new `next_try`); the next lookup and, in a paged run, the
  next page go on, and the sweep picks the put-off targets up. Only when no lookup could ask anything is the task
  deferred as a whole. Before, one lookup (Biodiversity PMC at its cap, the OpenAlex budget, a host down for days)
  stalled every later lookup and the whole paged back-fill, and capped tasks herded at 00:05 UTC.
- **Lookups** (each `garrulus.links.<name>.enabled`): works: Crossref (batched `filter=doi:`), OpenAlex (through
  `OpenAlexBudget`), Europe PMC (PMID, PMCID), Biodiversity PMC, BLR (Zenodo), Plazi; journals: Wikidata (one SPARQL
  query per batch of ISSNs: QID, IPNI publication P2008, BPH P4569, BHL title P4327, DOAJ P5115), IPNI publication
  search by ISSN, Crossref journals. An id another work holds enqueues `MERGE_WORKS`; one another journal holds is
  skipped (WARN). Wikidata: an item shared by two journals, or two items for one journal, gives nothing (WARN);
  several values of a property give the smallest.
- **Hostile DOI characters** (pre-flight M13): only DOIs of `[A-Za-z0-9._/-]` are batched; others are asked alone and
  URL-encoded, and a 400 on a batch re-asks one by one, so one SICI DOI cannot kill a batch.
- **Cost gates.** Plazi and BLR are asked only for works with nomenclatural acts or from a Plazi/BLR journal. **Biodiversity
  PMC** answers about 860 KB per document and has no field filter: same gate, batches of 10, and a cap of
  `garrulus.links.biodiversitypmc.max-requests-per-day` (200, about 1.7 GB) reserved atomically before each send
  (a batch reserves both its calls); a spent cap puts the lookup's targets off to 00:05 UTC. **The cap counts logical
  requests:** one reservation covers `PoliteHttp`'s own retries of that request, and a task retry after a failed send
  reserves again, so on a struggling day the documents actually downloaded can exceed the cap by a few (a logical
  request is the unit of the ~860 KB cost). Cost if wrong: fewer Biodiversity PMC links outside taxonomic works, or a
  few uncounted hits. Zenodo asks pages of 100 and re-asks singly the DOIs a truncated page did not return.

### Languages

- **Codes:** `LanguageCodes.normalise` gives ISO 639-1, else 639-3, from 639-1/2T/2B codes, English names and BCP 47
  tags; `und`, `mul`, `zxx` and the like are null; `nb`, `nn`, `no` are `no`. Every layer stores its language through it,
  **and so do the detector** (its Bokmål is `no`, so `LanguageDetector.supports` agrees with the decided codes) **and
  the translator** (LibreTranslate's `/languages` codes such as `zh-Hans` and `nb` are compared as `zh` and `no`; the
  service's own code is sent back on `/translate`; the plain code wins over a `Hans` variant, which wins over the rest).
- **Evidence** (`work_language_evidence`, one row per work, kind and source): `FULLTEXT` (detected on the body the finder
  read), `DECLARED` (OAI `dc:language` and JATS `xml:lang`, Europe PMC `language`, SciELO's original language, Magtech),
  `DETECTED` (title, abstract), `OPENALEX`, `LEGACY` (V21's copy of the old value). `raw` holds at most a declared code,
  never text. Crossref declares no language. A journal's languages are `source.languages` (DOAJ), not evidence rows.
- **Magtech's language comes from the `xml:lang` of a non-English `citation_title`**, not from `citation_language`,
  which the English article page gives as `en` for a Chinese article too (spec deviation).
- **Decision order**, first that applies: FULLTEXT; DECLARED (Magtech, SciELO, OAI, Europe PMC, then others); DETECTED
  (title before abstract, whatever the margin); OPENALEX or LEGACY (both reported `OPENALEX`: the legacy values came
  from the first layer, almost always OpenAlex); JOURNAL (exactly one DOAJ language); else `UNDECIDED`, no language.
  **An OpenAlex language outside the detector's 40 languages is kept only when the journal lists it**; otherwise the
  journal's single language, else undecided. This is what re-decides the dev DB's 148 "Uzbek" works. Cost if wrong: a
  few real rare-language works lose OpenAlex' value until a source declares it.
- **`LanguageService.decide` is the only writer** of `work.language` and `language_source`; insert, merge and update
  no longer write it. At harvest the decision uses declared and OpenAlex evidence; detection runs after `FIND_NAMES`
  (title, abstract, and the full text actually read). A merge moves evidence and `work_text`. `DETECT_LANGUAGE` reads
  by default the works whose `language_source` is null (legacy rows) or `UNDECIDED`; `"all":true` re-decides everything
  after a tuning change. Stale evidence is removed (an OpenAlex record without a language, a full text no longer kept).
- **Detector: Lingua 1.2.2** (Apache-2.0, offline), 40 languages, **low-accuracy mode by default** (+52 MB heap against
  +971 MB for high accuracy, next to the 1.4 GB dictionary build; `garrulus.language.high-accuracy` switches it).
  Lingua's confidence is **max-normalised** (the top language is always about 1.0), so `min-confidence` (0.80) does not
  discriminate; the gate is the **margin** over the second language, `garrulus.language.min-margin` **0.10** (start
  value; measured margins of real titles 0.04 to 0.44; the planned 0.30 rejected almost all). DETECTED evidence stores
  the margin as its confidence. `min-title-words` (3) counts only words that are not binomial-shaped, author/year or
  numbers, so a title of Latin names stays undecided. Cost if wrong: wrong detections pass, or good ones are dropped
  (tuned in the dev check).
- **Rule 5 uses DOAJ only:** there is no free ISSN record service for a journal's language.

### Texts and English titles

- **`work_text`** holds every version of title and abstract: `ORIGINAL`, `PARALLEL`, `MACHINE`, with its source and
  model. `work.title` and the abstract columns stay what the finder reads; `work_text` is never a finder input.
- **Where the main title is a translation, the original is `ORIGINAL`:** Magtech's `work.title` is the English feed
  title (`title_lang = en`) and the Chinese `citation_title` is stored ORIGINAL; Crossref's `original-title` is
  ORIGINAL, dropped when the language of the title is not confidently detected or equals the original's. OAI and JATS
  versions in other `xml:lang` are PARALLEL. **A PARALLEL version in the language of the work's own text of that field
  is no translation and is dropped** (an OAI record's second `dc:description` is often an English rights statement on
  an English article); the own text's language is the layer's label, else the stored one, else a confident detection,
  else unknown (nothing dropped).
- **Precedence:** a `work_text` row is replaced only by a layer of equal or better bibliographic rank (ADR-0008);
  top-up parallels rank below every layer. `title_lang`/`abstract_lang` are set only by the layer whose title or
  abstract the store kept.
- **`title_en` and `title_en_kind` are denormalised on `work`** (PARALLEL before MACHINE, none when the title is
  English) for lists, digests, feeds and `work.fts`, so an English query finds a Chinese paper. Every language decision
  refreshes them (the refresh writes only a change), since they depend on `title_lang` too, which paths other than a
  decision set. Display labels:
  "English title", "Parallel title (<language>)", "Original title", "Machine translation".

### Machine translation

- **Titles only, into English only** (spec option C): abstracts are never translated on the server, so no
  translation of a copyrighted abstract is stored. `TRANSLATE_TITLE` writes a MACHINE row (engine and model kept) and
  refreshes `title_en`; never `work.title`.
- **Start choice: LibreTranslate 1.9.6 with Argos models**, local on the VM like gnfinder, through its own JDK client
  (configured URL only, no redirects, a timeout, answers capped at 64 KB); not through `PoliteHttp`, whose public-host
  rule refuses loopback by design (ADR-0009). Off unless `garrulus.translation.enabled` and the URL are set. If the
  dev check finds more than 20 % of the titles misleading, OPUS-MT on CTranslate2 goes in behind `TitleTranslator`.
- **Only after naming:** nothing is written for a work the finder has not named yet; the naming path enqueues it for a
  non-English work without `title_en`. **A translation that loses a name is dropped:** blank, an echo of the title, or
  missing a Latin-script name mentioned in the title (word boundaries, NFC) is not stored, **and the drop is remembered
  per model** (`text_lookup` service `TRANSLATE:<model>`): neither a later decision nor `TRANSLATE_TITLE` asks again
  for that model unless `force` (a new model asks again). A 4xx skips that title and the batch goes on; an outage (also
  a stalled body) defers.
- **Abstracts are translated in the reader's browser** (Chrome's on-device Translator API); no text goes to a server
  and `web` makes no call. The result is labelled "Machine translation, done in your browser". **Every machine
  translation is labelled** in API (`kind`) and UI.

### Abstract and full-text fallbacks

- **OAI JATS** is the first full-text route (`OAI_JATS`) for a work with an OAI identifier and a VERIFIED or ACTIVE OAI
  route with prefix `jats` (JoTT): one `GetRecord` per work on the OAI channel, full text only with body paragraphs,
  licence from the record (kept only under an open licence; `OaiJatsRouteIT` checks a non-commercial and a missing
  licence). It asks nothing while the OAI layer is off (`garrulus.layers.oai.enabled=false`, the way to silence a
  misbehaving repository) or for a CEASED journal. The spec has the full text come "already" with the
  harvested record; the harvest keeps no record body, so the route asks again per work (spec deviation, one request a
  work at the OAI spacing).
- **Abstract top-up** when a work has no abstract after its routes, in order: Europe PMC (keyless, on by default,
  `garrulus.content.topup.europepmc.enabled`), Springer Nature Meta
  (DOIs `10.1007/`, `10.1186/`, `10.1038/`; free key, off without it; 100 requests a day reserved before each send; a
  401 or 403 switches it off), SciELO ArticleMeta (a DOI-prefix map to collections, `10.1590/=scl`; the PID comes from the
  `alternative-id` of the DOI's Crossref record, because ArticleMeta's identifiers listing has no DOI; the article
  is taken only when its own DOI equals the work's; also asked when an
  abstract exists, for its parallel texts and declared language), CiNii Research (Japanese journals; free appid; **only
  abstracts flagged `allow`**, matched by the item's own DOI field exactly). `fillAbstract` never replaces an abstract;
  the handler re-reads the work so the ABSTRACT row and `FIND_NAMES` follow.
- **180-day memory** (`text_lookup`): a service is asked at most once in 180 days per work, and only a definite found or
  miss is remembered (never an outage, a spent cap or a refused key). An outage defers only a reprocess run; a NO_TEXT
  work whose top-up met an outage or a spent daily cap gets the delayed recheck.
- **Display rights assumption:** Springer and Europe PMC abstracts are stored and shown **without a licence check**, as
  the Crossref and OpenAlex abstracts already are: both services hand abstracts out openly through their APIs. CiNii is
  the exception with an explicit per-abstract flag, and only `allow` is taken. Cost if wrong: delete them by
  `abstract_source` (`SPRINGER`, `EUROPEPMC`) and re-run `FIND_NAMES`.
- **`abstract_source` `INSTITUTIONAL` is reserved and never written** (the spec lists it; the KU route stores no abstract).
- **TEI re-check** (the M6a gap): when OpenAlex newly reports a TEI for a work already resolved without full text,
  `RESOLVE_CONTENT {"tei":true}` runs at `REPROCESS`, only when the TEI route would take the work (CORE source,
  non-BACKFILL origin, OpenAlex id, API key), at most `garrulus.content.tei-recheck-per-day` (200) a day, the rest
  the next day; a spent OpenAlex budget defers it to the reset.

### Institutional route (KU)

- **Off** (`garrulus.content.institutional.enabled=false`, empty publisher allow-list). **It may be switched on only
  after the KU library confirms in writing** which licences permit text and data mining for this use, and whether
  Garrulus counts as research under the EU TDM exception (DSM Art. 3). Markus obtains that; nothing assumes it.
- **Publishers:** Elsevier TDM (`10.1016/`, XML, content kind `ELSEVIER_XML`, parser hardened: a DOCTYPE is refused) and
  Wiley TDM (`10.1002/`, `10.1111/`, PDF read by a local GROBID through its own client; without a GROBID URL Wiley is
  not loaded). **Springer subscription content is left out:** KU's Springer access is by IP range on article pages, not
  a TDM API, and the spec excludes PDF downloads from article pages (pre-flight M5).
- **Last resort:** runs only after every open route answered (not after one was down or waiting for its budget), only
  for an allowed DOI without an open licence and without other full text, not when the publisher answered within 365
  days (30 days after a FAILED fetch); a reprocess asks again. `freeOnly` does not skip it (no cost per call).
- **Elsevier DOIs with a `.` or `..` path segment are refused** (not claimed by the Elsevier TDM client): the DOI is the
  API's URL path, and such a segment would leave it.
- **Requests:** single attempt (`PoliteHttp.getOnce`), every request audited in `institutional_fetch` and counted
  against the daily cap (500 per publisher); 429, 5xx, I/O or GROBID down defer 30 minutes, a full cap to 00:05 UTC,
  a busy publisher lock 5 minutes. A 401, or **5 consecutive 403s** (`max-consecutive-403`; those rows become
  `REFUSED_SYSTEMIC`, which block no work), switch the publisher off until restart. Tokens are headers that
  `PoliteHttp` sends only to the start authority (pre-flight H2).
- **Closed text:** spool, finder, purge; only mentions, offsets, short snippets, hashes and the URL are kept. **Its
  abstracts are never stored** (stricter than the spec's "stored if the licence allows").

### Mentioned-in filter

- `mentionedIn` (repeatable `TITLE`, `ABSTRACT`, `BODY`; any of them suffices; empty = anywhere) on `/api/works`, the
  taxon works, the public feeds and follows (`subscription.mentioned_in`). `BODY` is `body_count > 0` or `in_heading`.
  A reference-only name never counts, also for a follow with `includeReferences`.
- With a taxon or name filter it applies to the names that filter selects. **With a taxgroup and `mentionedIn` the
  group (and its descendants), `mainSubjectOnly` and the part are tested on the same `work_name` row** (the work-level
  `work.taxgroups` predicate is dropped), in the search and in `SubscriptionMatcher` alike; `MentionedInAgreementIT`
  runs the same fixtures through both. A PATCH keeps a follow's value; a new follow starts with the page's filter.
- **Decided 2026-11-24 (Markus): taxgroup follows count main subjects only.** The taxgroup criterion means "a main-subject
  name of that group (or a descendant group)" everywhere, with and without `mentionedIn`: the works search
  (`/api/works?taxgroup=`), the public taxgroup feed and TAXGROUP follows and hits list the same works. A TAXGROUP follow
  ignores its `mainSubjectOnly` flag (the UI shows it as always on); with `mentionedIn` the row must be a main-subject
  name of the group and in the part. Taxon, name and source semantics are unchanged.

### Languages in the API and statistics

- `language` filter (repeatable) and facet on works: **`language=none` selects undecided works** (an empty value binds
  to an empty list in Spring), **the facet shows them as `''`**, the admin Layers view as `UNDECIDED`. The filter is
  `w.language IN (…)`, plus `OR w.language IS NULL` only with `none`, so V21's `work_language_idx` serves it.
- **Precomputed, never a live GROUP BY on a public request** (pre-flight M6): V23's `taxon_language_stats` and
  `language_stats`, rebuilt by `REBUILD_STATS` in one transaction with `taxon_stats`. **`language_stats` stores one
  dimension per row** (`dim` none, taxgroup, code or country, plus its value), about 2.5 rows per work instead of a
  cube of about 12; so **`/api/stats/languages` accepts at most one of `taxgroup`, `code`, `country`** (400 otherwise).
  Cost if wrong: a combined filter needs another table. A journal's breakdown is a live count bounded by its `source_id`.
- **Feeds and follows do not filter by language.** While a language filter is active the UI hides the feed link and
  Follow with a note. Cost if wrong: no language feed until the backend adds one.
- The coverage check writes the contribution by work language (`layer_contribution_language`).

## Consequences / known limits

- **Phytotaxa has no BPH id** in IPNI or Wikidata (checked 2026-11-19), so the spec's "Phytotaxa shows … BPH" cannot
  pass until one of them adds it.
- **Short titles stay undecided** ("Las aves de Chile": margin 0.008); they fall to OpenAlex or the journal.
- **Biodiversity PMC covers what SIBiLS indexes**, under a daily cap.
- **Existing Magtech works** get `title_lang` and their Chinese original only on re-harvest.
- **The Magtech Chinese ORIGINAL title is not in `work.fts`** (title, `title_en`, abstract only).
- **Crossref's `original-title`** is not carried by the BLR layer's Crossref items nor by the JATS-only branch for
  known works; texts of the OpenAlex-enriched path keep source CROSSREF.
- **Identifier rows of an unregistered scheme** are listed publicly with their raw code (whether to hide them is open).
- **The `language_stats` rebuild** at 5M works is not measured yet (dev check).
- Thresholds are start values: `min-margin`, `min-title-words`, the Biodiversity PMC cap, the Springer cap, the
  institutional caps and the 403 breaker.
