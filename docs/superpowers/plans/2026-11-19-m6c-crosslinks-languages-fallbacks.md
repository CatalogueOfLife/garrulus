# M6c: crosslinks, languages and English titles, fallbacks, mentioned-in, institutional access — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Works and journals carry identifiers of the key services of the field as links (and are found by them);
every work has a decided language with its source, filterable and countable; English titles and abstracts a source
supplies are kept, non-English titles without one get a labelled machine translation, abstracts are translated only in
the reader's browser; works without an abstract get one from free fallbacks and open JATS full text comes straight from
OAI; a `mentionedIn` filter works on searches and follows; a University of Copenhagen route exists, tested and off.

**Architecture:** One V20 migration adds `work_text`, `work_language_evidence`, `source_identifier`, `link_attempt`,
`text_lookup`, `institutional_fetch`, `layer_contribution_language` and a few columns. The identifier scheme becomes a
registry (`IdScheme` with label, entities, URL templates, order) that a bean can extend; `LINK_WORK`/`LINK_SOURCE` run
an `IdentifierLookup` SPI. Languages are decided by a pure `LanguageDecider` from stored evidence (full text, declared,
detected by Lingua, OpenAlex, journal); `TRANSLATE_TITLE` calls a local LibreTranslate behind a `TitleTranslator` SPI.
`RESOLVE_CONTENT` gains the OAI JATS route first, an abstract top-up chain after the routes, and the institutional
route last (off). `web` stays call-free: it only reads the new rows.

**Tech Stack:** JDK 25, Spring Boot 4.1, MyBatis, Flyway, Postgres 17, Testcontainers, WireMock, JUnit 5, AssertJ,
Lingua 1.2.2 (`com.github.pemistahl:lingua`, Apache-2.0, offline); LibreTranslate 1.9.6 (AGPL-3.0, run natively as a
separate local service, never linked or modified) with Argos models; React, Mantine, TanStack Query, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md` (approved 2026-11-19). M6c covers its
sections "Crosslinks", "Abstract and full-text fallbacks", "Languages and English titles", "Mentioned-in filter" and
"Institutional access (University of Copenhagen)", and the matching "Done when" lines. M6a (built, ADR-0008) and M6b
(`docs/superpowers/plans/2026-11-19-m6b-registry-discovery-admins.md`, built before M6c: V19, admins, `admin_notice`,
disciplines, ceased journals, the admin Layers page) are not planned again.

## Global Constraints

- **Free only, no paid service.** Every integration has a switch; keyed services are off until their key is set
  (`SPRINGER_API_KEY`, `CINII_APPID`, the KU tokens). OpenAlex calls go through `OpenAlexBudget` (free allowance).
- **The translation service is local and self-hosted** (LibreTranslate on the VM, like gnfinder); off unless
  `nametracker.translation.enabled=true` **and** `NAMETRACKER_TRANSLATION_URL` is set. Only **titles** are translated on
  the server; abstracts never are (storing translations of copyrighted abstracts is avoided).
- **Never present a machine translation as the original.** `work.title` and the abstract columns stay what the finder
  read; every other version lives in `work_text` with its `kind` (ORIGINAL, PARALLEL, MACHINE), and the API and UI label
  MACHINE as "Machine translation". Translated text is never given to the finder.
- **Never store a name that does not point at an exact span of source text**: the finder reads only `work.title`, the
  abstract columns and content documents, as today; `work_text` is never a finder input.
- **The UCPH route is off by default** (`nametracker.content.institutional.enabled=false`, empty publisher allow-list)
  and never used for redistribution: its text is closed (spool, finder, purge; only mentions, offsets, ≤ 200-character
  snippets, hashes and the URL are kept), its abstracts are never written to `work`, every fetch is audited.
- Every task handler is idempotent under at-least-once delivery; an outage defers (`ServiceOutageException` /
  `HarvestOutage`) without using an attempt. Batched handlers continue with continuation tasks (ADR-0004).
- Flyway migrations are expand-only and start at `V20__…` (V19 is M6b's); only the indexer migrates, `web` validates.
  Widening a CHECK and redefining the generated `work.fts` expression (as V15 did) count as expand.
- Every external call goes through `PoliteHttp` and its per-host `HostLimiter`, user agent
  `Garrulus/<version> (+https://www.catalogueoflife.org)`. New host intervals: `query.wikidata.org`, `www.ipni.org`,
  `api.plazi.org`, `biodiversitypmc.sibils.org`, `api.springernature.com`, `articlemeta.scielo.org`, `cir.nii.ac.jp`,
  `api.elsevier.com` 1 s; `api.wiley.com` 10 s.
- **`web` makes no outbound calls.** Abstract translation runs in the reader's browser (Chrome's on-device Translator
  API) and never through `web`; identifier links are plain anchors.
- Every new public GET under `/api` and every new parameter goes into `web/src/main/resources/static/api/openapi.yaml`
  (`OpenApiCoverageTest`).
- Colours only from the brand tokens (`frontend/src/styles/tokens.css`: `--gr-paper`, `--gr-ink`, `--gr-grey`,
  `--gr-rule-soft`, `--gr-red`); never a new colour.
- Scheduled crons are UTC and sit behind `nametracker.harvest.enabled`; `DETECT_LANGUAGE` and `TRANSLATE_TITLE` are
  enqueued by events or by hand, not by cron.
- Work and commit directly on `main`. Commit messages never mention Claude or AI and carry no attribution trailer.
- Commands: `JAVA_HOME=~/.sdkman/candidates/java/25.0.3-librca`; one IT: `mvn -B -pl <module> -am verify
  -Dit.test=<IT>`; unit tests: `mvn -B -pl <module> test -Dtest=<Test>`; frontend: `cd frontend && npx vitest run
  <file>`. Run `mvn -B -DskipTests install` once after Task 1 so `-pl` builds see the new store.
- Parked by M6a, decided here: language codes normalised to one form (Task 6); a newly set `openalex_has_tei` re-resolves
  a resolved work (Task 10); the dev DB's 148 "Uzbek" works are re-decided by `DETECT_LANGUAGE` (Task 7, dev check).

### Pinned URL templates (live checks of 2026-11-19, `curl --max-time 30 --retry 2`, Garrulus user agent)

| Scheme | Entity | URL template | Evidence |
|---|---|---|---|
| `DOI` | work | `https://doi.org/{v}` | 302 to the publisher |
| `CROSSREF` | work | `https://api.crossref.org/works/{v}` | 200 JSON (Crossref's record) |
| `OPENALEX` | work / journal | `https://openalex.org/works/{v}` / `https://openalex.org/sources/{v}` | `openalex.org/W…`, `/S…` answer 301 to these |
| `PMID` | work | `https://europepmc.org/article/MED/{v}` | spec; europepmc.org answers 403 to scripted clients (bot filter): browser check in the dev check, fallback `https://pubmed.ncbi.nlm.nih.gov/{v}/` (203) |
| `PMCID` | work | `https://europepmc.org/articles/{v}` | the URL Europe PMC's own REST API returns in `fullTextUrlList`; browser check as above |
| `BIODIVERSITY_PMC` | work | `https://biodiversitypmc.sibils.org/{v}` (v = `pmc/PMC…` or `medline/<pmid>`) | SPA route `/:collection/:id` from its bundle; `/api/fetch?ids=…&col=` confirms the document |
| `BLR` | work | `https://zenodo.org/records/{v}` | 200 |
| `PLAZI` | work | `https://tb.plazi.org/GgServer/summary/{v}` | 200, article page (id from srsStats `DocArticleUuid`) |
| `BHL_PART` | work | `https://www.biodiversitylibrary.org/part/{v}` | doi.org's own redirect target for `10.5962/bhl.part.14315` |
| `MAGTECH` | work | `https://{0}/EN/abstract/abstract{1}.shtml` (value `host:id`) | 200 |
| `ISSN`, `ISSN_L` | journal | `https://portal.issn.org/resource/ISSN/{v}` | 200 |
| `CROSSREF_JOURNAL` | journal | `https://api.crossref.org/journals/{v}` | 200 JSON |
| `WIKIDATA` | journal | `https://www.wikidata.org/wiki/{v}` | 200 |
| `IPNI_PUB` | journal | `https://www.ipni.org/p/{v}` | 200 (Phytotaxa `20011462-1`) |
| `BHL_TITLE` | journal | `https://www.biodiversitylibrary.org/bibliography/{v}` | doi.org redirect target for `10.5962/bhl.title.39620` |
| `DOAJ` | journal | `https://doaj.org/toc/{v}` | Wikidata P5115 formatter; doaj.org answers 403 to scripts: browser check |
| `BPH` | journal | none (shown as text) | no per-record page; Wikidata P4569 has no formatter URL |
| `OAI` | both | none, not shown | an endpoint, not a page |

Dropped: **ZooBank** (`ZOOBANK`, `ZOOBANK_PUB`): every probe got a human-verification page or 404 (`/References/<uuid>`,
`/References.json`), so neither lookup nor link can be verified. **BHL API lookup**: needs a key and its documentation
answers 403 to scripts; `BHL_PART` comes only from BHL's own `10.5962/bhl.part.<id>` DOIs. Both return as one registry
entry plus one lookup once verified (that is exactly the extension path Task 3 tests).

## Review Focus

1. **DOIs with URL-hostile characters** (SICI DOIs such as `10.1002/(SICI)1097-4687(199601)227:1<1::AID-JMOR1>3.0.CO;2-#`)
   must give a working link (`<`, `>`, `#`, space, `"` percent-encoded; `/`, `:`, `;`, `(`, `)` kept), and
   `identifier=DOI:<that DOI>` must find the work (split at the first colon only). → Task 2 (`siciDoiLinkIsEncoded`),
   Task 5 (`identifierSearchSplitsAtFirstColon`).
2. **Language codes as the layers spell them**: `eng`, `English`, `en_US`, `EN`, `ger`, `fre`, `chi`, `dut`, `cze`,
   `zh-Hans`, `pt-BR`, `und`, `mul`, `zxx`, `""` → `en, en, en, en, de, fr, zh, nl, cs, zh, pt, null, null, null, null`.
   → Task 6 (`LanguageCodesTest`).
3. **A non-English paper whose only abstract is English** (Spanish title, English abstract; Chinese title, English
   summary): detection must decide by the title, not call it English. → Task 6 (`titleBeatsParallelEnglishAbstract`).
4. **A machine translation that mangles the paper**: the engine translates a genus in the title, echoes the input, or
   returns an empty string: nothing is stored and the drop is counted. → Task 9 (`translationLosingANameIsDropped`,
   `echoAndEmptyAreDropped`).
5. **Existing follows after V20** (`mentioned_in` = `{}`) behave exactly as before, including SOURCE follows; a SOURCE
   follow with `mentioned_in = {TITLE}` gets no hit for a paper with no name in its title. → Task 12
   (`emptyMentionedInIsUnchanged`, `sourceFollowWithTitleOnlyNeedsATitleName`).

---

### Task 1: V20 schema and store mappers

**Files:**
- Create: `store/src/main/resources/db/migration/V20__crosslinks_languages_fallbacks.sql`
- Create in `store/src/main/java/org/catalogueoflife/nametracker/store/`: `ident/SourceIdentifierMapper.java`,
  `ident/LinkAttemptMapper.java`, `lang/WorkText.java`, `lang/WorkTextMapper.java`, `lang/LanguageEvidence.java`,
  `lang/LanguageEvidenceMapper.java`, `content/TextLookupMapper.java`, `content/InstitutionalFetchMapper.java` (+ XML
  under `store/src/main/resources/org/catalogueoflife/nametracker/store/…`)
- Modify: `store/.../work/Work.java`, `WorkMapper.xml` (result map: new columns read-only), `store/.../account/
  Subscription.java`, `SubscriptionMapper.xml`
- Test: `store/src/test/java/.../store/lang/LanguageMappersIT.java`, `.../store/ident/SourceIdentifierMapperIT.java`

**Interfaces:**
- Produces:
  - `Work` gains at the end: `languageSource, titleLang, abstractLang, titleEn, titleEnKind` (Strings, nullable). They
    are never written by `WorkMapper.insert`/`update` (only by the mappers below), so `WorkMerger` ignores them.
  - `record WorkText(long workId, String field /*TITLE|ABSTRACT*/, String lang, String kind
    /*ORIGINAL|PARALLEL|MACHINE*/, String text, String source, @Nullable String model, Instant created)`;
    `WorkTextMapper`: `int upsert(WorkText t)` (PK work, field, lang, kind; text, source, model replaced),
    `List<WorkText> forWork(long workId)`, `List<WorkText> forWorks(Collection<Long> ids)`, `int deleteMachine(long
    workId, String field)`, `int refreshTitleEn(long workId)` (sets `work.title_en`/`title_en_kind` from the PARALLEL
    `en` title, else the MACHINE `en` title, else null; null too when `coalesce(title_lang, language) = 'en'`),
    `int moveAll(long from, long to)` (rows the survivor has are dropped).
  - `record LanguageEvidence(long workId, String kind /*FULLTEXT|DECLARED|DETECTED|OPENALEX|JOURNAL|LEGACY*/, String
    source, @Nullable String lang, @Nullable Float confidence, @Nullable String raw)`; `LanguageEvidenceMapper`:
    `int upsert(LanguageEvidence e)`, `List<LanguageEvidence> forWork(long workId)`, `int setDecision(long workId,
    @Nullable String lang, String source)` (writes `language`, `language_source`, `language_decided_at = now()`),
    `int moveAll(long from, long to)`.
  - `SourceIdentifierMapper`: `int insertAll(long sourceId, List<Identifier> ids, String origin)` (ON CONFLICT
    (scheme, value) DO NOTHING), `List<Identifier> list(long sourceId)`, `@Nullable Long findSource(String scheme,
    String value)`, `int mirror(long sourceId)` (inserts `ISSN` per `source.issns`, `ISSN_L`, `OPENALEX` from the
    columns, origin `MIRROR`).
  - `LinkAttemptMapper`: `@Nullable LinkAttempt get(char entity, long subjectId, String lookup)`, `int record(char
    entity, long subjectId, String lookup, boolean found, @Nullable Instant nextTry)` (attempts + 1),
    `List<LinkAttempt> due(char entity, Instant now, int limit)`; `record LinkAttempt(char entity, long subjectId,
    String lookup, int attempts, Instant lastTried, @Nullable Instant nextTry, boolean found)`.
  - `TextLookupMapper`: `boolean triedSince(long workId, String service, Instant since)`, `int record(long workId,
    String service, boolean found)`.
  - `InstitutionalFetchMapper`: `long insert(@Nullable Long workId, String doi, String publisher, String api, int
    status, int bytes, String outcome)`, `int countSince(String publisher, Instant since)`.
  - `Subscription` gains `List<String> mentionedIn` (insert and update write it; the 14-argument convenience
    constructor passes `List.of()`).

- [ ] **Step 1: Write the migration** `V20__crosslinks_languages_fallbacks.sql`:
  - `work`: `language_source text CHECK IN ('FULLTEXT','DECLARED','DETECTED','OPENALEX','JOURNAL','UNDECIDED')`,
    `language_decided_at timestamptz`, `title_lang text`, `abstract_lang text`, `title_en text`, `title_en_kind text
    CHECK IN ('PARALLEL','MACHINE')`; partial index `work (language) WHERE state IN ('INDEXED','NOTIFIED')`.
  - `abstract_source` CHECK widened with `'SPRINGER','SCIELO','CINII','INSTITUTIONAL'` (look up the name with `\d work`).
  - `work.fts` redefined as V15's expression with `coalesce(title_en, '')` added after the title.
  - `work_text(work_id bigint REFERENCES work ON DELETE CASCADE, field text CHECK IN ('TITLE','ABSTRACT'), lang text NOT
    NULL, kind text CHECK IN ('ORIGINAL','PARALLEL','MACHINE'), text text NOT NULL, source text NOT NULL, model text,
    created timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (work_id, field, lang, kind))`.
  - `work_language_evidence(work_id … CASCADE, kind text CHECK IN (… six kinds), source text NOT NULL, lang text,
    confidence real, raw text, created timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (work_id, kind, source))`;
    back-fill one `LEGACY` row (source `V20`, raw = lang = `work.language`) per work with a language.
  - `source_identifier(source_id bigint REFERENCES source ON DELETE CASCADE, scheme text NOT NULL, value text NOT NULL,
    origin text NOT NULL, first_seen timestamptz NOT NULL DEFAULT now(), UNIQUE (scheme, value))`, index on
    `source_id`; back-fill the mirror rows (lowest source id wins a shared ISSN).
  - `link_attempt(entity char(1) CHECK IN ('W','S'), subject_id bigint, lookup text, attempts smallint NOT NULL,
    last_tried timestamptz NOT NULL, next_try timestamptz, found boolean NOT NULL, PRIMARY KEY (entity, subject_id,
    lookup))`, partial index on `next_try WHERE next_try IS NOT NULL` (no FK: subjects of two tables; Task 3 sweeps rows
    of deleted subjects).
  - `subscription.mentioned_in text[] NOT NULL DEFAULT '{}' CHECK (mentioned_in <@ ARRAY['TITLE','ABSTRACT','BODY'])`.
  - `text_lookup(work_id … CASCADE, service text, tried_at timestamptz NOT NULL DEFAULT now(), found boolean NOT NULL,
    PRIMARY KEY (work_id, service))`.
  - `institutional_fetch(id bigserial PK, work_id bigint REFERENCES work ON DELETE SET NULL, doi text NOT NULL,
    publisher text NOT NULL, api text NOT NULL, fetched_at timestamptz NOT NULL DEFAULT now(), status int NOT NULL,
    bytes int NOT NULL, outcome text NOT NULL)`, index `(publisher, fetched_at)`.
  - `source.languages text[] NOT NULL DEFAULT '{}'`.
  - `layer_contribution_language` like V18's `layer_contribution` with `language text NOT NULL DEFAULT ''` instead of
    `country`.
- [ ] **Step 2: Write the tests.** `LanguageMappersIT`: `legacyEvidenceBackfilled` (a work inserted with language
  `uz` before migrating has one LEGACY row), `refreshTitleEnPrefersParallel` (MACHINE and PARALLEL `en` rows →
  `title_en` = parallel text, kind PARALLEL; delete the parallel → MACHINE), `titleEnNullWhenTitleIsEnglish`,
  `ftsFindsEnglishTitle` (a `zh` work with a MACHINE title "New species of Carabus" matches `fts @@
  websearch_to_tsquery('simple','carabus')`), `moveAllKeepsSurvivorRows`. `SourceIdentifierMapperIT`:
  `mirrorWritesIssnIssnlOpenalex`, `sharedIssnStaysWithLowerId`. `AccountMappersIT` (extend):
  `mentionedInRoundTrips`, `existingRowsReadEmpty`.
- [ ] **Step 3: Run** `mvn -B -pl store -am verify -Dit.test='LanguageMappersIT,SourceIdentifierMapperIT,AccountMappersIT'` → FAIL.
- [ ] **Step 4: Implement** the migration, records and mappers (XML in the style of `WorkIdentifierMapper.xml`).
- [ ] **Step 5: Run** them, then `mvn -B -pl store -am verify` → PASS; then `mvn -B -DskipTests install`.
- [ ] **Step 6: Commit** `feat(store): V20 texts, language evidence, journal identifiers, link attempts`

---

### Task 2: the identifier scheme registry

**Files:**
- Modify: `store/src/main/java/org/catalogueoflife/nametracker/store/ident/IdScheme.java`
- Create: `store/.../ident/IdentifierRegistry.java`, `store/.../ident/IdentifierLink.java`
- Test: `store/src/test/java/.../store/ident/IdSchemeTest.java` (extend), `.../ident/IdentifierRegistryTest.java`

**Interfaces:**
- Produces:
  - `enum IdScheme.Entity { WORK, SOURCE }`. `IdScheme` gains `label()`, `entities() -> Set<Entity>`, `sortOrder()`,
    `shown()`, `url(Entity e, String value) -> @Nullable String`, and a public factory `static IdScheme define(String
    code, String label, Set<Entity> entities, Function<String, @Nullable String> normaliser, Map<Entity, String>
    urlTemplates, int sortOrder, boolean shown)`. Templates use `{v}` for the value, or `{0}`/`{1}` for the parts of
    a value split at its last `:` (MAGTECH). The value is encoded with `IdScheme.encodePathValue` (RFC 3986 path:
    letters, digits, `-._~!$&'()*+,;=:@/` kept, everything else as UTF-8 percent escapes).
  - New constants with the table of "Pinned URL templates": `CROSSREF` (value = normalised DOI), `PLAZI` (32 hex,
    upper case), `BIODIVERSITY_PMC` (`pmc/PMC\d+` or `medline/\d+`), `BHL_PART` (digits), `ISSN`, `ISSN_L` (through
    M6b's `Issn.normalise`), `CROSSREF_JOURNAL` (ISSN), `WIKIDATA` (`Q\d+`), `IPNI_PUB` (`\d+-\d`), `BPH`
    (digits), `BHL_TITLE` (digits), `DOAJ` (DOAJ id: 32 hex lower case, or an ISSN as DOAJ's TOC accepts). `OPENALEX`
    entities WORK and SOURCE; `OAI` shown false. Sort order on the page: DOI 10, CROSSREF 20, OPENALEX 30, PMID 40,
    PMCID 50, BIODIVERSITY_PMC 60, BLR 70, PLAZI 80, BHL_PART 90, MAGTECH 100; ISSN 10, ISSN_L 15, OPENALEX 30,
    CROSSREF_JOURNAL 40, WIKIDATA 50, IPNI_PUB 60, BPH 70, BHL_TITLE 80, DOAJ 90.
  - `IdentifierRegistry(Collection<IdScheme> extra)` (built-ins plus extras; a duplicate code is an
    `IllegalStateException`): `@Nullable IdScheme find(String code)`, `List<IdentifierLink> links(Entity e,
    List<Identifier> ids)` (shown schemes of the entity, sorted by order then value; an unknown code is listed with
    its code as label and url null); `@Nullable Identifier parse(String schemeColonValue)` (split at the first `:`,
    scheme code case-insensitive, value normalised; null when either fails).
  - `record IdentifierLink(String scheme, String label, String value, @Nullable String url)`.
  - Both apps declare `@Bean IdentifierRegistry identifierRegistry(ObjectProvider<IdScheme> extra)`.

- [ ] **Step 1: Write the tests.** `IdentifierRegistryTest`:
```java
var reg = new IdentifierRegistry(List.of());
assertThat(reg.links(WORK, List.of(new Identifier("DOI", "10.3897/zookeys.1187.113403"))).getFirst().url())
    .isEqualTo("https://doi.org/10.3897/zookeys.1187.113403");
assertThat(IdScheme.MAGTECH.url(WORK, "www.biodiversity-science.net:92977"))
    .isEqualTo("https://www.biodiversity-science.net/EN/abstract/abstract92977.shtml");
assertThat(IdScheme.OPENALEX.url(SOURCE, "S199213172")).isEqualTo("https://openalex.org/sources/S199213172");
assertThat(IdScheme.BIODIVERSITY_PMC.url(WORK, "pmc/PMC10768028"))
    .isEqualTo("https://biodiversitypmc.sibils.org/pmc/PMC10768028");
assertThat(IdScheme.BPH.url(SOURCE, "1234")).isNull();
// siciDoiLinkIsEncoded
assertThat(IdScheme.DOI.url(WORK, "10.1002/(sici)1097-4687(199601)227:1<1::aid-jmor1>3.0.co;2-#"))
    .isEqualTo("https://doi.org/10.1002/(sici)1097-4687(199601)227:1%3C1::aid-jmor1%3E3.0.co;2-%23");
assertThat(reg.parse("pmcid:10768028")).isEqualTo(new Identifier("PMCID", "PMC10768028"));
assertThat(reg.parse("DOI:10.1002/(SICI)1097-4687(199601)227:1<1::AID-JMOR1>3.0.CO;2-#").value())
    .startsWith("10.1002/(sici)");
assertThat(reg.parse("NOPE:1")).isNull();
```
  plus `linksSortedAndOaiHidden`, `unknownCodeListedWithoutUrl`, `extraSchemeIsFound` (a `define("TESTDB", …)` passed
  as extra), `duplicateCodeRejected`. `IdSchemeTest`: normalisers of each new scheme (one good, one bad value each).
- [ ] **Step 2: Run** `mvn -B -pl store test -Dtest='IdSchemeTest,IdentifierRegistryTest'` → FAIL.
- [ ] **Step 3: Implement**; the bean in `IndexerConfig` and in `web`'s config.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(store): identifier schemes as a registry with labels and link templates`

---

### Task 3: `LINK_WORK` and `LINK_SOURCE` with the lookup SPI

**Files:**
- Create in `indexer/src/main/java/org/catalogueoflife/nametracker/indexer/link/`: `IdentifierLookup.java`,
  `LinkTarget.java`, `LinkWorkHandler.java`, `LinkSourceHandler.java`, `LinkScheduler.java`, `LinkProperties.java`,
  `BhlDoiLookup.java`
- Modify: `indexer/.../match/MatchNamesHandler.java` (enqueue on first indexing), `indexer/.../registry/
  IdentifySourceHandler.java` (M6b; enqueue `LINK_SOURCE`), `indexer/.../crossref/CrossrefWorks.java` and
  `layer/crossref/*` (the Crossref layer adds `CROSSREF` with every record it stores), `store/.../source/SourceMapper.xml`
  (`updateIdentity` and the seeder call `SourceIdentifierMapper.mirror`), `indexer/src/main/resources/application.yml`
- Test: `indexer/src/test/java/.../link/LinkWorkHandlerIT.java`, `.../link/LinkSourceHandlerIT.java`

**Interfaces:**
- Consumes: Task 1 mappers, `IdentifierRegistry` (Task 2), M6b `Source.publicationStatus`.
- Produces:
  - `record LinkTarget(IdScheme.Entity entity, long id, @Nullable String doi, List<String> issns, Map<String,
    List<String>> ids /*scheme → values*/, boolean hasActs, @Nullable Long sourceId)`.
  - `interface IdentifierLookup { String name(); List<String> schemes(); IdScheme.Entity appliesTo(); boolean
    enabled(); default int order() { return 100; } default int batchSize() { return 1; } boolean applies(LinkTarget t);
    Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) throws IOException; }`. A target missing from the map
    is a miss; a retryable `HttpProblem` or `ServiceOutageException` is an outage; `BudgetExhaustedException` defers
    the task to `retryAt` without an attempt and records nothing.
  - `LINK_WORK` (`LinkWorkHandler.TYPE`): payload `{"workId":n}` or `{"afterId":n,"batch":200,"schemes":[…]|null}`.
    Loads the `INDEXED`/`NOTIFIED` targets of the page, runs the enabled lookups in `order()`, each over the targets
    it `applies` to and that have no `link_attempt` row for it (or one that is due), in chunks of `batchSize()`,
    reloading identifiers between lookups (Biodiversity PMC needs Europe PMC's PMCID). Found ids are inserted with
    `first_layer` = `LINK:<name>`; a `MERGE_WORKS` is enqueued when an id belongs to another work (as `WorksPager`
    does). Attempts: found → `found` true, `next_try` null; miss 1 → `next_try` + 30 days; miss 2 → + 180 days; miss
    3 → null (never again). With `schemes`, only lookups producing one of them run (the back-fill of a new lookup).
    A batch page continues as `{"afterId":last,…}` at its priority and `run_after`, key `link-works:<sha12 of
    payload>`.
  - `LINK_SOURCE` (`LinkSourceHandler.TYPE`): `{"sourceId":n}` or `{"afterId":n,"batch":100,"schemes":…}`; same rules
    for sources; mirrors ISSN/ISSN_L/OPENALEX first; skips CEASED sources.
  - Enqueuing: `MatchNamesHandler` reads `first_indexed_at` before `indexing.index` and, when it was null and the
    result is `INDEXED`, enqueues `LINK_WORK {"workId"}` at `BACKFILL`, key `link:w<id>` (a re-index never does, so a
    reprocess does not re-query). `IdentifySourceHandler` enqueues `LINK_SOURCE {"sourceId"}` at `BACKFILL`, key
    `link:s<id>:<yyyy-mm>`. `LinkScheduler`: monthly `nametracker.links.source-cron` (`0 45 1 2 * *`) enqueues
    `LINK_SOURCE {"afterId":0}` (key `link-sources:<yyyy-mm>`); daily `nametracker.links.retry-cron` (`0 15 6 * * *`)
    enqueues one batched task per entity for up to 5000 due `link_attempt` rows and deletes rows whose subject is gone.
    Both behind the harvest switch; every lookup off with `nametracker.links.<name>.enabled` (default true), all off
    with `nametracker.links.enabled`.
  - `BhlDoiLookup` (no network, order 10): a DOI `10.5962/bhl.part.<n>` gives `BHL_PART` `<n>`.

- [ ] **Step 1: Write the tests.** `LinkWorkHandlerIT` (WireMock where a lookup calls out; test lookups as
  `@TestConfiguration` beans): `newSchemeAndLookupNeedNoMigration` (an extra `IdScheme.define("TESTDB", …)` bean and a
  lookup returning `TESTDB:42` for one work; `LINK_WORK {"afterId":0,"schemes":["TESTDB"]}` → one `work_identifier`
  row `TESTDB/42`, first_layer `LINK:test`; no Flyway change), `missRetriesAfter30Then180ThenNever`,
  `foundIsNotQueriedAgain`, `reindexDoesNotEnqueue` (two `MATCH_NAMES` of one work → one `LINK_WORK`),
  `outageDefersWithoutAttempt`, `idOfAnotherWorkEnqueuesMerge`, `bhlPartFromBhlDoi`, `batchContinues`.
  `LinkSourceHandlerIT`: `mirrorsColumns`, `ceasedSourceSkipped`, `identifyEnqueuesLinkSource`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='LinkWorkHandlerIT,LinkSourceHandlerIT'` → FAIL.
- [ ] **Step 3: Implement** handlers, scheduler, properties (`nametracker.links.*` in `application.yml`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): LINK_WORK and LINK_SOURCE run identifier lookups with retries`

---

### Task 4: the built-in lookups for works and journals

**Files:**
- Create in `indexer/.../link/work/`: `CrossrefLinkLookup.java`, `OpenAlexLinkLookup.java`, `EuropePmcLinkLookup.java`,
  `BiodiversityPmcLookup.java`, `ZenodoBlrLookup.java`, `PlaziLookup.java`
- Create in `indexer/.../link/source/`: `WikidataSourceLookup.java`, `IpniSourceLookup.java`,
  `CrossrefJournalLookup.java`
- Modify: `indexer/.../http/PoliteHttp.java` (`defaultIntervals`: the hosts of Global Constraints),
  `indexer/.../layer/blr/ZenodoClient.java` (`byDois`), `indexer/.../crossref/CrossrefClient.java` (`existingDois`)
- Test: `indexer/src/test/java/.../link/work/*LookupIT.java`, `.../link/source/*LookupIT.java` (WireMock, fixtures
  under `indexer/src/test/resources/link/`, saved from the live answers of 2026-11-19 for ZooKeys
  `10.3897/zookeys.1187.113403` and Phytotaxa `1179-3155`)

**Interfaces:**
- Consumes: `IdentifierLookup`, `LinkTarget` (Task 3), `EuropePmcSearchClient.search`, `OpenAlexClient` + budget.
- Produces (name, order, batch, applies, call; values through the scheme normalisers):
  - `crossref` (20, 20, DOI and no `CROSSREF`): `GET /works?filter=doi:a,doi:b,…&rows=20&select=DOI` → `CROSSREF`.
    `CrossrefClient.existingDois(List<String>) -> Set<String>`.
  - `openalex` (20, 50, DOI and no `OPENALEX`): `GET /works?filter=doi:a|b|…&select=id,doi&per-page=50` through the
    budget → `OPENALEX`.
  - `europepmc` (30, 25, DOI and no `PMID`): query `DOI:"a" OR DOI:"b"`, `resultType=lite` → `PMID`, `PMCID`.
  - `biodiversitypmc` (40, 50, a `PMCID` or `PMID`): `GET https://biodiversitypmc.sibils.org/api/fetch?ids=…&col=pmc`
    (PMCIDs) then `col=medline` (PMIDs of targets still without one) → `BIODIVERSITY_PMC` `pmc/<_id>` or
    `medline/<_id>` for each `_id` in `sibils_article_set`; one value per work, `pmc` preferred.
  - `zenodo-blr` (50, 25, DOI and no `BLR`, and `hasActs` or the source has a `PLAZI` signal or a `BLR` sighting):
    `GET /api/records?communities=biosyslit&type=publication&q=doi:("a" OR "b")&size=25`; a record whose `doi`
    equals a target DOI → `BLR` (its id). `ZenodoClient.byDois(List<String>) -> List<JsonNode>`.
  - `plazi` (60, 1, same filter as zenodo-blr): `GET https://tb.plazi.org/GgServer/srsStats/stats?outputFields=
    doc.articleUuid+pubLnk.articleDoi&groupingFields=doc.articleUuid+pubLnk.articleDoi&FP-pubLnk.articleDoi=
    "<doi>"&format=JSON` → `PLAZI` `DocArticleUuid` of the row whose `PubLnkArticleDoi` equals the DOI.
  - `wikidata` (source, 20, 50 sources): one SPARQL `GET https://query.wikidata.org/sparql` with `VALUES ?issn
    {…}` over `wdt:P236|wdt:P7363` and OPTIONALs P2008 (IPNI publication), P4569 (BPH journal; the spec's P829 is
    the OEIS id), P4327 (BHL bibliography), P5115 (DOAJ) → `WIKIDATA`, `IPNI_PUB`, `BPH`, `BHL_TITLE`, `DOAJ`; an item
    matching two sources is used for neither (WARN).
  - `ipni` (source, 30, 1, no `IPNI_PUB` after wikidata): `GET https://www.ipni.org/api/1/search?q=issn:<issn>` per
    ISSN; only results with `recordType` `publication` and `issn` equal to the ISSN (the search is fuzzy: `issn:1179-3155`
    also returns `0005-3155`) → `IPNI_PUB` (`id`) and `BPH` when `bphNumber` is all digits.
  - `crossref-journal` (source, 40, 1, an ISSN and no `CROSSREF_JOURNAL`): `GET /journals/{issn}` 200 → value ISSN.

- [ ] **Step 1: Write the tests**, one IT per lookup against the saved fixtures, asserting the exact values:
  ZooKeys work → `CROSSREF 10.3897/zookeys.1187.113403`, `OPENALEX W4390339873`, `PMID 38187161`, `PMCID PMC10768028`,
  `BIODIVERSITY_PMC pmc/PMC10768028`, `BLR 10446357` (figure records `10446547`, `10446549` ignored), `PLAZI
  331A59CAD9185E36B059921AFDF9F77C`; Phytotaxa → `WIKIDATA Q4807825`, `IPNI_PUB 20011462-1`, no `BPH`;
  `ipniFuzzyResultIgnored`, `wikidataItemOfTwoSourcesUnused`, `budgetSpentDefers`, `zenodo503IsOutage`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='*LookupIT'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): lookups for Crossref, OpenAlex, Europe PMC, Biodiversity PMC, BLR, Plazi, Wikidata, IPNI`

---

### Task 5: identifiers in the API and search by identifier

**Files:**
- Create: `web/.../api/public_/dto/IdentifierDto.java`
- Modify: `web/.../api/public_/WorkController.java`, `SourceController.java`, `dto/WorkDetail.java`,
  `dto/SourceDetail.java`, `store/.../search/{WorkFilter.java,SearchMapper.xml}`, `openapi.yaml`
- Test: `web/src/test/java/.../api/public_/WorkControllerIT.java`, `SourceControllerIT.java` (extend)

**Interfaces:**
- Consumes: `IdentifierRegistry` (Task 2), `WorkIdentifierMapper.list`, `SourceIdentifierMapper.list` (Task 1).
- Produces:
  - `record IdentifierDto(String scheme, String label, String value, @Nullable String url)`; `WorkDetail` and
    `SourceDetail` gain `List<IdentifierDto> identifiers` (from `registry.links`).
  - `WorkFilter` gains `@Nullable Identifier identifier` (a new last component; every existing constructor call passes
    null); `SearchMapper.xml` `where`: `EXISTS (SELECT 1 FROM work_identifier wi WHERE wi.work_id = w.id AND
    wi.scheme = … AND wi.value = …)`.
  - `GET /api/works?identifier=SCHEME:value` (400 when `registry.parse` gives null); combines with every other filter.

- [ ] **Step 1: Write the tests.** `WorkControllerIT`: `detailListsIdentifiersInOrder` (DOI, CROSSREF, OPENALEX,
  PMID, PMCID, BIODIVERSITY_PMC, BLR, PLAZI with their URLs; OAI absent), `identifierSearchFindsByPmcid`
  (`identifier=PMCID:10768028` → the work), `identifierSearchSplitsAtFirstColon` (the SICI DOI of Review Focus 1),
  `unknownSchemeIs400`, `extraSchemeRowsAreListed` (a `TESTDB` row and an extra scheme bean → listed with its URL).
  `SourceControllerIT.journalIdentifiers` (ISSN, OPENALEX, IPNI_PUB with URLs; BPH with url null).
- [ ] **Step 2: Run** `mvn -B -pl web -am verify -Dit.test='WorkControllerIT,SourceControllerIT,OpenApiCoverageTest'` → FAIL.
- [ ] **Step 3: Implement**; document `identifier` and `identifiers` in `openapi.yaml`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(web): identifiers of works and journals, and search by identifier`

---

### Task 6: language codes, detection and the decision rule

**Files:**
- Create: `store/.../lang/LanguageCodes.java`, `indexer/.../lang/LanguageDetector.java`,
  `indexer/.../lang/LanguageDecider.java`, `indexer/.../lang/LanguageProperties.java`
- Modify: `indexer/pom.xml` (`com.github.pemistahl:lingua:1.2.2`), `indexer/.../layer/europepmc/EuropePmcLayer.java`
  (`language` → `LanguageCodes.normalise`)
- Test: `store/src/test/java/.../lang/LanguageCodesTest.java`, `indexer/src/test/java/.../lang/LanguageDetectorTest.java`,
  `.../lang/LanguageDeciderTest.java`

**Interfaces:**
- Produces:
  - `LanguageCodes.normalise(@Nullable String raw) -> @Nullable String`: ISO 639-1 where one exists, else ISO 639-3;
    accepts 639-1, 639-2/T, 639-2/B (`ger fre chi dut cze gre per rum slo wel alb arm baq bur geo ice mac mao may tib`),
    English names (`Locale.getAvailableLocales` display names), BCP 47 tags (`en_US`, `zh-Hans`, `pt-BR`: region and
    script dropped), any case; `und`, `mul`, `zxx`, `mis` and unknown values → null.
    `LanguageCodes.isLatinScript(String code) -> boolean` (false for `zh ja ko ru uk be bg mk sr kk ky mn ar fa ur he
    el th hi bn ta te ml kn mr ne si my km lo ka hy am`; used by Task 9's order).
  - `LanguageDetector(LanguageProperties p)`: Lingua `LanguageDetectorBuilder.fromLanguages(…)` over 40 languages (EN, ZH,
    JA, KO, RU, UK, BG, SR, ES, PT, FR, DE, IT, NL, PL, CS, SK, HU, RO, HR, SL, TR, FA, AR, HE, EL, TH, VI, ID, MS, HI,
    BN, TA, SV, NB, DA, FI, ET, LT, CA), high accuracy, lazily built once. `Detection detect(@Nullable String text)`;
    `record Detection(@Nullable String lang, float confidence)`. Start values (tunable, properties
    `nametracker.language.*`): `min-confidence` 0.80; a title counts with at least `min-title-words` 3 words, or 8
    letters of a CJK/Hangul/Kana/Thai script; an abstract or body with at least `min-text-letters` 100 letters. Text is
    stripped of markup; names are not removed (they are a small share of a title or abstract).
  - `LanguageDetector.titleAndAbstract(@Nullable String title, @Nullable String abstractText, List<String>
    parallelTexts) -> @Nullable Detection`: parallel texts are removed from the abstract first (exact paragraph
    matches); the title decides when it qualifies and is confident; else the abstract; else null.
  - `LanguageDecider.decide(List<LanguageEvidence> ev, List<String> journalLanguages) -> Decision`; `record
    Decision(@Nullable String lang, String source)`. First that applies: FULLTEXT (confidence ≥ min) → DECLARED (by
    layer `MAGTECH`, `SCIELO`, `OAI`, `EUROPEPMC`, in that order) → DETECTED (≥ min) → OPENALEX, else LEGACY (reported
    as `OPENALEX`: V20's legacy values came from the first layer, almost always OpenAlex) → JOURNAL (exactly one
    journal language) → `UNDECIDED` with lang null.

- [ ] **Step 1: Write the tests.** `LanguageCodesTest` with the inputs and outputs of Review Focus 2, plus
  `"Spanish"` → `es`, `"spa"` → `es`, `"ZH"` → `zh`, `"Uzbek"` → `uz`, `isLatinScript("ru")` false. `LanguageDetectorTest`:
  `chineseTitle` ("中国西南地区蜚蠊科两新属" → zh ≥ 0.8), `englishTitle`, `shortTitleUndecided` ("Notes" → null),
  `titleBeatsParallelEnglishAbstract` (Spanish title, English abstract → es; Chinese title, English summary → zh),
  `parallelTextRemovedBeforeAbstractDetection`, `uzbekMisdetectionFixed` (an English title and abstract → en).
  `LanguageDeciderTest`: `fulltextWins`, `declaredOrder` (MAGTECH zh beats OAI en), `detectedBeatsOpenAlex` (OPENALEX
  uz, DETECTED en 0.95 → en DETECTED), `lowConfidenceDetectionSkipped`, `journalOnlyWithOneLanguage`,
  `undecidedWithoutEvidence`.
- [ ] **Step 2: Run** `mvn -B -pl store test -Dtest=LanguageCodesTest` and `mvn -B -pl indexer test
  -Dtest='LanguageDetectorTest,LanguageDeciderTest'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: language codes, offline detection and the language decision rule`

---

### Task 7: deciding languages — evidence from every layer, `FIND_NAMES`, `DETECT_LANGUAGE`

**Files:**
- Create: `indexer/.../lang/LanguageService.java`, `indexer/.../lang/DetectLanguageHandler.java`
- Modify: `indexer/.../harvest/WorksPager.java` (`store` records evidence and decides), `indexer/.../layer/HarvestedWork.java`
  (`declaredLanguage`), `layer/oai/OaiRecordMapper.java`, `layer/magtech/{MagtechClient,MagtechLayer}.java`,
  `layer/europepmc/EuropePmcLayer.java`, `openalex/WorkRecord.java`, `finder/FindNamesHandler.java`,
  `work/MergeWorksHandler.java`, `indexer/.../registry/DoajClient.java` and `IdentifySourceHandler.java` (M6b: DOAJ
  `bibjson.language` into `source.languages`; `DoajJournal` gains `List<String> languages`), `store/.../work/WorkMerger.java` (`language` no longer merged)
- Test: `indexer/src/test/java/.../lang/LanguageServiceIT.java`, `.../lang/DetectLanguageHandlerIT.java`,
  `.../layer/magtech/MagtechLayerIT.java` (extend), `.../layer/oai/OaiRecordMapperTest.java` (extend)

**Interfaces:**
- Consumes: Task 1 mappers, `LanguageCodes`, `LanguageDetector`, `LanguageDecider` (Task 6).
- Produces:
  - `HarvestedWork` gains `@Nullable String declaredLanguage` and `List<TextIn> texts` (Task 8 fills the texts;
    the old 3-argument constructor stays and passes null / `List.of()`); `record TextIn(String field, @Nullable String
    lang, String kind, String text)` in `indexer/.../layer/` (a `work_text` row before the work id is known).
  - `LanguageService`: `void evidence(long workId, LanguageEvidence e)` (upsert, then `decide`), `Decision
    decide(long workId)` (reads evidence and `source.languages`, writes the decision, and when the language changed
    to a non-English one with no English title, enqueues `TRANSLATE_TITLE {"workId"}` at `BACKFILL`, key
    `translate:<id>`, only when translation is enabled), `void detectFromWork(Work w)` (DETECTED evidence from
    `titleAndAbstract`, parallel texts from `work_text`), `void detectFromDocument(long workId, Document doc)` (FULLTEXT
    from PARAGRAPH blocks, first 5,000 letters, only when the document has a PARAGRAPH block).
  - Evidence by layer: OAI `dc:language` and JATS `article/@xml:lang` → DECLARED `OAI` (raw kept, value normalised);
    Europe PMC `language` → DECLARED `EUROPEPMC`; OpenAlex `language` → OPENALEX; Magtech → DECLARED `MAGTECH` from the
    language of a `citation_title` whose `xml:lang` is not `en` (live check 2026-11-19: the EN page Garrulus reads says
    `citation_language` `en` for a Chinese article, so that tag is not used); SciELO comes in Task 10. Crossref gives
    none (its `language` is often the metadata's).
  - `FindNamesHandler`, after storing names: `detectFromWork` and, for a document with body text,
    `detectFromDocument`, then `decide`. `MergeWorksHandler` moves evidence and texts and decides for the survivor.
  - `DETECT_LANGUAGE` (`DetectLanguageHandler.TYPE`): `{"afterId":0,"batch":1000}` or `{"workIds":[…]}`; per work
    `detectFromWork`, plus `detectFromDocument` from the retained full-text blob when there is one, then `decide`;
    continues per batch at its priority, key `detect-language:<afterId>`.

- [ ] **Step 1: Write the tests.** `LanguageServiceIT`: `oaiEnglishSpelledOutIsEnglish` (`dc:language` `English` →
  `en` DECLARED), `europePmcChiIsZh`, `openAlexUzOverruledByDetection` (OpenAlex `uz`, English title and abstract,
  after `FIND_NAMES` → `en`, source DETECTED), `fulltextDecides`, `newDeclaredEvidenceRedecides`,
  `mergeMovesEvidence`, `doajSingleLanguageUsedLast`. `MagtechLayerIT.chineseOriginalDeclaredFromTitleLang` (page with
  `citation_language` `en` and `citation_title xml:lang="zh"` → DECLARED `zh`). `DetectLanguageHandlerIT`:
  `batchRedecidesAndContinues` (150 works with LEGACY `uz` and English titles → all `en`; a continuation queued),
  `idempotentOnRedelivery`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='LanguageServiceIT,DetectLanguageHandlerIT,MagtechLayerIT'`
  and `-Dtest=OaiRecordMapperTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS, then `mvn -B -pl indexer -am verify` (existing layer and merge ITs still pass).
- [ ] **Step 5: Commit** `feat(indexer): every work's language decided from full text, declarations, detection and OpenAlex`

---

### Task 8: parallel titles and abstracts from the layers

**Files:**
- Create: `indexer/.../lang/ParallelTexts.java`
- Modify: `layer/oai/OaiRecordMapper.java` (every `dc:title`/`dc:description` with its `xml:lang`; JATS
  `trans-title-group/trans-title`, `trans-abstract`), `layer/magtech/MagtechLayer.java` (English feed title and
  abstract; Chinese original from the page), `crossref/CrossrefWorks.java` (`original-title`), `harvest/WorksPager.java`
  (writes `texts` and `title_lang`/`abstract_lang`, then `refreshTitleEn`), `store/.../work/WorkMapper.xml`
  (`setTextLangs`)
- Test: `indexer/src/test/java/.../lang/ParallelTextsTest.java`, `layer/oai/OaiRecordMapperTest.java`,
  `layer/magtech/MagtechLayerIT.java`, `crossref/CrossrefWorksTest.java` (extend)

**Interfaces:**
- Consumes: `WorkTextMapper` (Task 1), `LanguageCodes`, `LanguageDetector` (Task 6), `HarvestedWork.texts`, `TextIn` (Task 7).
- Produces:
  - Rules (`ParallelTexts.of(@Nullable String primaryTitle, @Nullable String primaryAbstract, List<TextIn> versions)
    -> List<TextIn>`): a version equal to the primary text is dropped;
    a version without a language gets `LanguageDetector`'s confident language or is dropped; versions are PARALLEL;
    at most one per field, language and kind (the first wins). `WorksPager.store` writes them as `WorkText` with
    source = the layer name.
  - Magtech: `work.title` stays the English feed title (the finder's offsets point into it), `title_lang` = `en`,
    `abstract_lang` = `en`; the page's non-English `citation_title` is stored as kind **ORIGINAL** (the source's
    original-language title, which `work.title` is not). `refreshTitleEn` leaves `title_en` null (the main title is
    English).
  - Crossref: when `original-title` differs from `title`, the title's detected language becomes `title_lang` and
    `original-title` is stored ORIGINAL with its detected language; when detection is not confident, nothing is stored.
  - OAI: the first title stays `work.title`; others PARALLEL by `xml:lang`. `title_lang`/`abstract_lang` from the
    first title's and description's `xml:lang` when present.
  - `WorkMapper.setTextLangs(long id, @Nullable String titleLang, @Nullable String abstractLang)` (never overwrites
    a value with null). Texts never change `work.title` (frozen once named) or the abstract columns.

- [ ] **Step 1: Write the tests.** `OaiRecordMapperTest`: `parallelTitlesByXmlLang` (`dc:title xml:lang="es"` first,
  `xml:lang="en"` second → work.title Spanish, one PARALLEL en TITLE), `jatsTransTitleAndAbstract`,
  `untaggedVersionDetectedOrDropped`. `MagtechLayerIT.englishFeedChineseOriginal` (work.title English, title_lang en,
  ORIGINAL zh title from the page, abstract English with its `<i>` kept as before). `CrossrefWorksTest.originalTitle`.
  `ParallelTextsTest`: `duplicateOfPrimaryDropped`, `onePerFieldAndLanguage`.
- [ ] **Step 2: Run** the tests → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): parallel titles and abstracts from OAI, Magtech and Crossref`

---

### Task 9: `TRANSLATE_TITLE` through a local LibreTranslate

**Files:**
- Create in `indexer/.../translate/`: `TitleTranslator.java`, `LibreTranslateClient.java`, `TranslateTitleHandler.java`,
  `TranslationProperties.java`
- Modify: `indexer/.../http/PoliteHttp.java` (`postJson(URI, String json, Map<String,String> headers, @Nullable
  String channel)`), `indexer/src/main/resources/application.yml`, `.env.example`
- Test: `indexer/src/test/java/.../translate/TranslateTitleHandlerIT.java`, `.../translate/LibreTranslateClientIT.java`

**Interfaces:**
- Consumes: `WorkTextMapper.upsert/refreshTitleEn/deleteMachine` (Task 1), `LanguageCodes.isLatinScript` (Task 6),
  `IndexMapper` title mentions.
- Produces:
  - `interface TitleTranslator { String engine(); String model(); Set<String> sources(); String translate(String text,
    String from) throws IOException; }` (to English only).
  - `LibreTranslateClient` (start choice, tunable: LibreTranslate 1.9.6 with Argos models, installed with pip and run as
    a systemd unit on the VM like gnfinder, `libretranslate --host 127.0.0.1 --port 5055 --load-only
    zh,ja,ko,ru,uk,ar,fa,th,vi,tr,es,pt,fr,de,it,id,pl,cs,en`): `GET /languages` once (cached 1 h) for `sources()`,
    `POST /translate` `{"q","source","target":"en","format":"text"}` → `translatedText`. Properties:
    `nametracker.translation.enabled` (false), `.url` (`NAMETRACKER_TRANSLATION_URL`, blank), `.model` (label stored with
    each row, default `libretranslate-1.9.6/argos`), `.timeout` (30 s). Connection refused, 5xx or a timeout →
    `ServiceOutageException` (30 minutes).
  - `TRANSLATE_TITLE` (`TranslateTitleHandler.TYPE`): `{"workId":n}` or `{"all":true,"afterId":0,"batch":500,
    "script":"NON_LATIN"|"LATIN","force":false}`. Translates when translation is enabled, the work's language is
    decided, not `en`, in `sources()`, `coalesce(title_lang, language) <> 'en'`, there is no PARALLEL `en` title, and
    (unless `force`) no MACHINE `en` title of the current model. Stores MACHINE (TITLE, `en`, source = engine, model)
    and `refreshTitleEn`. **Drops** the result (stores nothing, INFO with a running count) when it is blank, equal to
    the input after whitespace folding, or does not contain every Latin-script title mention verbatim (the finder's
    mentions in the TITLE block). `all` pages `NON_LATIN` languages first and continues with `LATIN` (key
    `translate-all:<script>:<afterId>`). Disabled → the task ends DONE with one INFO (a later `all` run catches up).

- [ ] **Step 1: Write the tests.** `LibreTranslateClientIT` (WireMock): `translatesAndCachesLanguages`,
  `connectionRefusedIsOutage`, `fiveHundredIsOutage`. `TranslateTitleHandlerIT` (a fake `TitleTranslator` bean):
  `chineseTitleGetsMachineTranslationLabelled` (row kind MACHINE, model stored, `title_en_kind` MACHINE, the English
  query finds it through `fts`), `translationLosingANameIsDropped` (title mention `Carabus` missing from the output →
  no row), `echoAndEmptyAreDropped`, `parallelEnglishMeansNoTranslation`, `englishWorkSkipped`,
  `disabledDoesNothing`, `allRunsNonLatinFirst`, `forceRetranslatesOtherModel`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='TranslateTitleHandlerIT,LibreTranslateClientIT'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): machine-translated English titles from a local LibreTranslate, labelled and checked`

---

### Task 10: full text from OAI JATS, the abstract top-up, the OpenAlex TEI re-check

**Files:**
- Create: `indexer/.../content/route/OaiJatsRoute.java`; in `indexer/.../content/topup/`: `AbstractTopUp.java`,
  `TopUpResult.java`, `EuropePmcAbstract.java`, `SpringerMetaAbstract.java`, `ScieloAbstract.java`, `CiniiAbstract.java`
- Modify: `content/ResolveContentHandler.java` (top-up after the routes), `layer/oai/OaiPmhClient.java`
  (`getRecord`), `store/.../work/WorkUpsert.java` (`UpsertResult.teiAppeared`), `harvest/WorksPager.java` and
  `harvest/OpenAlexRecheckHandler.java` (enqueue on `teiAppeared`), `store/.../work/WorkMapper.xml`
  (`fillAbstract`), `application.yml`, `.env.example`
- Test: `indexer/src/test/java/.../content/route/OaiJatsRouteIT.java`, `.../content/topup/*IT.java`,
  `.../content/ResolveContentHandlerIT.java` (extend), `.../harvest/OpenAlexRecheckHandlerIT.java` (create)

**Interfaces:**
- Consumes: `ContentRoute`, `FetchedContent`, `JatsSupport`, `LicencePolicy`, `TextLookupMapper` (Task 1),
  `LanguageService.evidence` (Task 7), `ParallelTexts` (Task 8).
- Produces:
  - `OaiJatsRoute` (`@Order(0)`, route `OAI_JATS`): applies when the work has an `OAI` identifier and its source an
    ACTIVE or VERIFIED `OAI` route whose config `prefix` is `jats`; `GET <base>?verb=GetRecord&identifier=<oai
    id>&metadataPrefix=jats` (OAI channel, 2 s) → `JatsConverter`; full text only when the record has a `<body>`;
    licence from `<license xlink:href>` (checked live on JoTT 2026-11-19: CC BY 4.0, 23 bodies in one page).
    `OaiPmhClient.getRecord(URI base, String identifier, String prefix) -> Optional<OaiRecord>`.
  - `interface AbstractTopUp { String service(); boolean enabled(); boolean applies(Work w, @Nullable Source s);
    default boolean always() { return false; } Optional<TopUpResult> fetch(Work w, @Nullable Source s) throws
    IOException; }`; `record TopUpResult(@Nullable String abstractText, @Nullable String abstractJats, @Nullable
    String abstractLang, String abstractSource, List<TextIn> parallels, @Nullable String declaredLanguage)`.
  - In `RESOLVE_CONTENT`, after the full-text routes and before the text level is computed: when the work has no
    abstract, the top-ups run in order Europe PMC → Springer → SciELO → CiNii, each skipped when `text_lookup` says it
    was tried within 180 days; the first abstract is stored with `WorkMapper.fillAbstract(id, text, jats, source)`
    (fills nulls only), so the chosen document changes and `FIND_NAMES` follows as today. `always()` top-ups (SciELO)
    run once per work even when an abstract exists, for parallels and the declared language. Every try writes
    `text_lookup`; an outage of a top-up defers the task only on a `reprocess` run, otherwise it is logged and skipped.
  - Services (start values, tunable): Europe PMC (`EXT_ID:<pmid> AND SRC:MED`, else `DOI:"…"`, `resultType=core`,
    `abstractText`), always on; Springer Nature Meta (`https://api.springernature.com/meta/v2/json?q=doi:<doi>&api_key=`,
    DOIs `10.1007/`, `10.1186/`, `10.1038/`; `SPRINGER_API_KEY` blank = off; `nametracker.content.topup.springer.daily-cap`
    100, counted in `api_usage` service `SPRINGER_META`; a 401 disables it until restart with one WARN); SciELO
    ArticleMeta (`nametracker.content.topup.scielo.collections`, start `10.1590=scl`: lists
    `/api/v1/article/identifiers/?collection=<c>&issn=<issn>&from=<pub date − 30 d>&until=<pub date + 365 d>` up to 3
    pages of 100, matches the DOI, then `/api/v1/article/?code=<pid>&collection=<c>` → `v83` abstracts and `v12`
    titles by language (PARALLEL), `v40` → DECLARED `SCIELO`); CiNii Research (`https://cir.nii.ac.jp/opensearch/
    articles?q=<doi>&format=json&appid=<CINII_APPID>`, sources with country `JP`; blank appid = off; only
    `abstractLicenseFlag` `allow`, `disallow` is a miss — live samples of 2026-11-19 were all `disallow`).
  - `UpsertResult.teiAppeared`: `openalex_has_tei` went false → true on a work past `DISCOVERED` whose text level is not
    `FULLTEXT`; `WorksPager.store` and `OpenAlexRecheckHandler` then enqueue `RESOLVE_CONTENT {"workId"}` (all routes,
    the TEI route within its budget) at the caller's priority, key `content:<id>:tei`.

- [ ] **Step 1: Write the tests.** `OaiJatsRouteIT`: `jottBodyIsFullTextAndRetained` (fixture of one JoTT record),
  `recordWithoutBodyIsNotFullText`, `notAJatsRouteDoesNotApply`, `oai503IsRetryable`. Top-up ITs (WireMock):
  `europePmcFillsMissingAbstract` (source `EUROPEPMC`, `FIND_NAMES` queued), `springerOffWithoutKey`,
  `springerCapStops`, `scieloAddsParallelsAndDeclaredLanguage` (fixture of `S1984-46702025000100400`),
  `ciniiDisallowIsMiss`, `triedWithin180DaysSkipped`, `existingAbstractNeverReplaced`. `OpenAlexRecheckHandlerIT.
  teiOnResolvedWorkReresolves`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='OaiJatsRouteIT,*AbstractIT,ResolveContentHandlerIT,OpenAlexRecheckHandlerIT'` → FAIL.
- [ ] **Step 3: Implement**; keys in `.env.example` (`SPRINGER_API_KEY`, `CINII_APPID`).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): OAI JATS full text, abstract top-up from Europe PMC, Springer, SciELO, CiNii`

---

### Task 11: the institutional route (University of Copenhagen), off

**Files:**
- Create in `indexer/.../content/institutional/`: `InstitutionalRoute.java`, `InstitutionalProperties.java`,
  `PublisherTdm.java`, `ElsevierTdm.java`, `ElsevierXml.java`, `WileyTdm.java`
- Modify: `application.yml`, `.env.example`, `web/.../api/AdminStatusController.java` (`institutional`: fetches per
  publisher today and in 30 days)
- Test: `indexer/src/test/java/.../content/institutional/InstitutionalRouteIT.java`, `.../ElsevierXmlTest.java`,
  `web/src/test/java/.../api/AdminStatusControllerIT.java` (extend)

**Interfaces:**
- Consumes: `ContentRoute`, `Spool` (via `RESOLVE_CONTENT`), `InstitutionalFetchMapper` (Task 1), `TeiConverter`.
- Produces:
  - `InstitutionalRoute` (`@Order(100)`, route `INSTITUTIONAL`, last, so it runs only when no open route had full text):
    applies when `nametracker.content.institutional.enabled` is true, the work has a DOI, its licence is not open
    (`LicencePolicy`), and a `PublisherTdm` in the allow-list `nametracker.content.institutional.publishers` (empty by
    default) claims the DOI. Below the per-publisher daily cap (`…<publisher>.daily-cap`, 500) it fetches, writes one
    `institutional_fetch` row per call (also on errors), and returns `FetchedContent(…, license = null, fulltext =
    body present)`: closed, so `RESOLVE_CONTENT` spools it and `FIND_NAMES` purges it as for every closed text.
    Abstracts from this route are never written to `work` (display rights unknown until KU confirms); they reach the
    finder only inside the spooled document.
  - `interface PublisherTdm { String publisher(); boolean claims(String doi); Optional<Document> fetch(String doi)
    throws IOException; }`:
    `ElsevierTdm` (DOI prefix `10.1016/`; `GET https://api.elsevier.com/content/article/doi/{doi}?httpAccept=text/xml`
    with `X-ELS-APIKey` and `X-ELS-Insttoken`; the endpoint answered on 2026-11-19); `ElsevierXml.toDocument(byte[],
    String docId) -> Document` (`dc:title` TITLE, `dc:description` ABSTRACT, `ce:section-title` HEADING, `ce:para`
    PARAGRAPH, `ce:caption` CAPTION, `ce:bib-reference` REFERENCE, `ce:italic` as italic style spans).
    `WileyTdm` (prefixes `10.1002/`, `10.1111/`; `GET https://api.wiley.com/onlinelibrary/tdm/v1/articles/{urlencoded
    doi}` with `Wiley-TDM-Client-Token`; it answers with a PDF, sent to GROBID at
    `nametracker.content.institutional.grobid-url` and read with `TeiConverter`; without that URL Wiley is not loaded,
    one WARN at start). Springer Nature subscription content is left out: its subscriber path is a PDF download, which
    the spec excludes from M6.
  - A 401/403 is a non-retryable failure of the route (logged, audited); 429/5xx/connect errors are retryable.

- [ ] **Step 1: Write the tests.** `InstitutionalRouteIT` (WireMock as both publisher APIs and as GROBID):
  `offByDefaultMakesNoCall` (default properties → zero requests, no audit row), `allowListRequired`,
  `elsevierFullTextIsClosedAndSpooled` (no blob written, spool file present until `FIND_NAMES`, snippets ≤ 200
  characters after indexing), `abstractNeverStored`, `everyFetchAudited` (also a 403), `dailyCapStops`,
  `openRouteFirst` (an open full text from `PensoftRoute` → institutional not called). `ElsevierXmlTest` on a small
  hand-written sample. `AdminStatusControllerIT.institutionalCounts`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test=InstitutionalRouteIT`, `-Dtest=ElsevierXmlTest` → FAIL.
- [ ] **Step 3: Implement**; document the switch, tokens and the licence condition (spec, "Institutional access") in
  `.env.example` comments.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): institutional TDM route for KU, audited, capped and off by default`

---

### Task 12: the mentioned-in filter on searches, feeds and follows

**Files:**
- Modify: `store/.../search/{WorkFilter.java,SearchMapper.xml,WorkNameRow.java}`, `web/.../api/public_/
  {WorkController,TaxonController}.java`, `dto/NameDetail.java`, `web/.../feed/FeedController.java`,
  `web/.../me/{SubscriptionController,SubscriptionDto}.java`, `indexer/.../hits/{WorkNameView,HitMatcher,
  SubscriptionMatcher}.java`, `openapi.yaml`
- Test: `web/src/test/java/.../api/public_/{WorkControllerIT,TaxonControllerIT}.java`, `.../feed/FeedControllerIT.java`,
  `.../me/SubscriptionControllerIT.java`, `indexer/src/test/java/.../hits/SubscriptionMatcherTest.java`,
  `.../hits/EvaluateHitsHandlerIT.java` (extend)

**Interfaces:**
- Consumes: `subscription.mentioned_in` (Task 1).
- Produces:
  - `WorkFilter` gains `List<String> mentionedIn` (empty = any part; every existing call passes `List.of()`).
    `MentionedIn.sql`: `TITLE` → `wn.in_title`, `ABSTRACT` → `wn.in_abstract`, `BODY` → `(wn.body_count > 0 OR
    wn.in_heading)`, OR-ed. With `taxon` or `name` it goes inside that filter's `EXISTS`; without, `EXISTS (… work_name
    wn WHERE wn.work_id = w.id AND NOT wn.only_reference AND (…))`. It combines with `mainSubjectOnly` (same row),
    `hasActs` and `actType`.
  - `/api/works`, `/api/taxa/{colId}/works` and the four public feeds take repeatable `mentionedIn`
    (`TITLE|ABSTRACT|BODY`, else 400).
  - `WorkNameView` gains `boolean inTitle, boolean inAbstract, boolean inHeading, int bodyCount`;
    `SubscriptionMatcher` filters names by `s.mentionedIn()` after the reference rule; the SOURCE shortcut ("any
    work") applies only when `mentionedIn` is empty.
  - Follows: `POST/PATCH /api/me/subscriptions` accept `mentionedIn` (subset of the three, duplicates folded);
    `SubscriptionDto` returns it.
  - `NameDetail` and `WorkNameRow` gain `inTitle, inAbstract, inHeading, bodyCount`.

- [ ] **Step 1: Write the tests.** `WorkControllerIT`: `taxonTitleOrAbstract` (the spec's
  `taxon=…&mentionedIn=TITLE&mentionedIn=ABSTRACT`: a paper with the taxon only in the body is absent, one with it in
  the abstract present), `bodyIncludesHeadings`, `referencesNeverCount`, `withMainSubjectOnly`, `badValueIs400`,
  `nameDetailHasFlags`. `TaxonControllerIT.mentionedInOnTaxonWorks`. `FeedControllerIT.publicFeedMentionedIn`.
  `SubscriptionControllerIT.mentionedInSavedAndReturned`. `SubscriptionMatcherTest`: `emptyMentionedInIsUnchanged`
  (the existing cases, run again with `List.of()`), `titleOnlyFollow`, `sourceFollowWithTitleOnlyNeedsATitleName`.
  `EvaluateHitsHandlerIT.followWithTitleGetsHitsOnlyForTitlePapers`.
- [ ] **Step 2: Run** `mvn -B -pl web -am verify -Dit.test='WorkControllerIT,TaxonControllerIT,FeedControllerIT,SubscriptionControllerIT,OpenApiCoverageTest'`
  and `mvn -B -pl indexer -am verify -Dit.test=EvaluateHitsHandlerIT -Dtest=SubscriptionMatcherTest` → FAIL.
- [ ] **Step 3: Implement**; parameters in `openapi.yaml`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: mentionedIn filter on works, feeds and follows`

---

### Task 13: languages in the API, lists, digests, feeds and the Layers report

**Files:**
- Create: `web/.../api/public_/StatsController.java`, `dto/TextVersion.java`, `dto/LanguageYear.java`
- Modify: `store/.../search/{WorkFilter.java,SearchMapper.java,SearchMapper.xml,WorkHit.java,FeedRow.java}`,
  `store/.../stats/StatsMapper.{java,xml}`, `web/.../api/public_/{WorkController,TaxonController,SourceController}.java`,
  `dto/{WorkDetail,WorkSummary,TaxonStats,SourceDetail}.java`, `web/.../digest/DigestComposer.java`,
  `web/.../feed/{FeedController,AtomWriter}.java`, `indexer/.../layer/coverage/CoverageCheckHandler.java`,
  `store/.../ident/WorkSightingMapper.xml` (`contributionByLanguage`), `web/.../api/AdminLayersController.java` (M6b),
  `openapi.yaml`
- Test: `web/src/test/java/.../api/public_/{WorkControllerIT,StatsControllerIT,TaxonControllerIT,SourceControllerIT}.java`,
  `.../digest/DigestComposerTest.java`, `.../feed/FeedControllerIT.java`, `.../api/AdminLayersControllerIT.java`,
  `indexer/src/test/java/.../layer/coverage/CoverageCheckHandlerIT.java` (extend)

**Interfaces:**
- Consumes: Task 1 columns and `work_text`, Tasks 7–9 data, `LanguageCodes`.
- Produces:
  - `WorkFilter` gains `List<String> languages` (repeatable `language`, each through `LanguageCodes.normalise`, 400
    when null); facet `language` (`w.language`, null as `''`) added to `SearchMapper.FACETS`.
  - `WorkSummary` gains `@Nullable String language, @Nullable String titleLang, @Nullable String titleEn, @Nullable
    String titleEnKind`. `WorkDetail` gains `language, languageSource, titleLang, abstractLang, List<TextVersion>
    titles, List<TextVersion> abstracts`; `record TextVersion(String lang, String kind, String text, @Nullable String
    model)` (all `work_text` rows of the field; `kind` MACHINE always says so).
  - `GET /api/stats/languages?taxgroup=&code=&country=&yearFrom=&yearTo=` → `[LanguageYear(String language /* '' when
    undecided */, int year, long works)]` over listed works, ordered by year then works desc.
  - `TaxonStats` and `SourceDetail` gain `List<LanguageYear> languages`.
  - Digests and feeds: after a non-English title, the English one on its own line, prefixed "English title: " or
    "Machine translation: "; the Atom entry gets the same as a second line of its summary.
  - `COVERAGE_CHECK` also writes `layer_contribution_language` (as V18's country rows, by `work.language`); the M6b
    `LayersView` gains `Map<String, Map<String, Map<String, Long>>> contributionByLanguage`.

- [ ] **Step 1: Write the tests.** `WorkControllerIT`: `languageFilterAndFacet` (`language=zh&language=ja`; facet
  counts), `languageAliasesNormalised` (`language=chi` → zh), `detailTitlesLabelled` (MACHINE title with model, PARALLEL
  abstract, original Magtech title as ORIGINAL), `summaryCarriesEnglishTitle`. `StatsControllerIT`:
  `perYearAndTaxgroup`, `undecidedAsEmpty`, `onlyListedWorks`. `TaxonControllerIT.statsLanguages`,
  `SourceControllerIT.detailLanguages`. `DigestComposerTest.machineTitleLabelled`.
  `FeedControllerIT.entryShowsEnglishTitle`. `CoverageCheckHandlerIT.contributionByLanguage`,
  `AdminLayersControllerIT.languageBreakdown`.
- [ ] **Step 2: Run** `mvn -B -pl web -am verify -Dit.test='WorkControllerIT,StatsControllerIT,TaxonControllerIT,SourceControllerIT,FeedControllerIT,AdminLayersControllerIT,OpenApiCoverageTest' -Dtest=DigestComposerTest`
  and `mvn -B -pl indexer -am verify -Dit.test=CoverageCheckHandlerIT` → FAIL.
- [ ] **Step 3: Implement**; `openapi.yaml` gets `/api/stats/languages`, the parameter, the facet and the fields.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: work languages as filter, facet and metric; English titles in lists, digests and feeds`

---

### Task 14: frontend — the work and journal pages

**Files:**
- Create: `frontend/src/components/IdentifierLinks.tsx`, `components/TranslateButton.tsx`, `frontend/src/translate.ts`,
  `frontend/src/languages.ts`, `components/LanguageBreakdown.tsx`
- Modify: `frontend/src/api.ts` (types), `pages/WorkPage.tsx`, `pages/SourcePage.tsx`
- Test: `frontend/src/components/{IdentifierLinks,TranslateButton,LanguageBreakdown}.test.tsx`, `frontend/src/
  {translate,languages}.test.ts`, `pages/{WorkPage,SourcePage}.test.tsx` (extend)

**Interfaces:**
- Consumes: `identifiers`, `language*`, `titles`, `abstracts`, `NameDetail` flags, `SourceDetail.languages` (Tasks 5,
  12, 13).
- Produces:
  - `IdentifierLinks({identifiers})`: one row of labelled links under the bibliographic block (`rel="noopener"`, new
    tab); an entry without `url` as "Label value" text.
  - `languageName(code)` via `Intl.DisplayNames(['en'], {type: 'language'})`, falling back to the code.
  - `translate.ts`: `translatorAvailable(from: string): Promise<'available'|'downloadable'|'unavailable'>` and
    `translateText(text, from): Promise<string>` over Chrome's `Translator` global (`Translator.availability`,
    `Translator.create({sourceLanguage, targetLanguage: 'en'})`); no network call of the page's own.
  - `TranslateButton({text, lang})`: shown next to a non-English abstract (and a non-English title without an English
    version); on click shows the translation below the original as plain text, labelled "Translated in your browser";
    when the API is missing, a hint: "Your browser can translate this page: use its Translate option." The original
    keeps its name highlighting and `lang` attribute.
  - `WorkPage`: `lang` on title and abstract; under the title the other `titles` labelled "English title", "Machine
    translation" or "Original title"; parallel abstracts as collapsible sections labelled with their language; per
    name, where it occurs ("title, abstract, full text").
  - `SourcePage`: `IdentifierLinks` and `LanguageBreakdown` (works per language, a small table by year; numbers only,
    no new colours).

- [ ] **Step 1: Write the tests.** `IdentifierLinks.test.tsx`: `rendersLabelsAndUrls`, `textWithoutUrl`.
  `translate.test.ts`: `unavailableWithoutTranslatorGlobal`, `usesTranslatorWhenPresent` (a stubbed global).
  `TranslateButton.test.tsx`: `showsTranslationBelowOriginal`, `hintWhenUnavailable`, `noFetchIsMade` (the fetch
  mock is never called). `WorkPage.test.tsx`: `machineTitleLabelled`, `langAttributes`, `identifiersRow`,
  `mentionedWhere`. `SourcePage.test.tsx`: `journalIdentifiersAndLanguages`. `languages.test.ts`: `zhIsChinese`.
- [ ] **Step 2: Run** `cd frontend && npx vitest run src/components src/pages/WorkPage.test.tsx src/pages/SourcePage.test.tsx src/translate.test.ts src/languages.test.ts` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test` and `npm run build` → PASS.
- [ ] **Step 5: Commit** `feat(frontend): identifier links, English and machine titles, in-browser abstract translation`

---

### Task 15: frontend — filters, lists, follows and the admin Layers language view

**Files:**
- Create: `frontend/src/components/MentionedInFilter.tsx`
- Modify: `frontend/src/api.ts`, `components/FilterSidebar.tsx` (language facet, mentioned-in), `components/WorkList.tsx`
  (English title line), `components/FollowButton.tsx` (mentioned-in checkboxes), `pages/{SearchPage,TaxonPage,NamePage,
  FollowingPage}.tsx`, `pages/admin/AdminLayersPage.tsx` (M6b; by-language table)
- Test: `frontend/src/components/{MentionedInFilter,FilterSidebar,FollowButton}.test.tsx`, `pages/{SearchPage,TaxonPage,
  FollowingPage}.test.tsx`, `pages/admin/AdminLayersPage.test.tsx` (extend)

**Interfaces:**
- Consumes: `language`/`mentionedIn` parameters and facet, `WorkSummary` English title, subscription `mentionedIn`,
  `TaxonStats.languages`, `LayersView.contributionByLanguage` (Tasks 12, 13).
- Produces:
  - `MentionedInFilter({value, onChange})`: checkboxes "Title", "Abstract", "Full text" (none = any), with the hint
    "Full text is available for some papers only."; URL parameters `mentionedIn` (repeated) on search, taxon and name
    pages, passed to the API and to the feed link.
  - The filter panel's language facet lists `languageName` with counts; selected values go to `language`.
  - `WorkList`: below a non-English title, the English one in `--gr-grey`, prefixed "English title" or "Machine
    translation".
  - `FollowButton`: "Mentioned in: title, abstract, full text" checkboxes, saved as `mentionedIn`; `FollowingPage`
    shows them on the follow.
  - `TaxonPage`: `LanguageBreakdown`. `AdminLayersPage`: a contribution-by-language table next to the country one.

- [ ] **Step 1: Write the tests.** `MentionedInFilter.test.tsx`: `togglesValues`, `hintShown`. `FilterSidebar.test.tsx`:
  `languageFacetNames`. `SearchPage.test.tsx`: `mentionedInInUrlAndRequest`, `languageInRequest`. `WorkList` via
  `SearchPage`: `machineTitleLine`. `FollowButton.test.tsx`: `savesMentionedIn`, `existingFollowWithoutMentionedIn`.
  `TaxonPage.test.tsx.languageBreakdown`. `AdminLayersPage.test.tsx.byLanguageTable`.
- [ ] **Step 2: Run** `cd frontend && npx vitest run src/components src/pages` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test` and `npm run build` → PASS.
- [ ] **Step 5: Commit** `feat(frontend): language and mentioned-in filters, English titles in lists, follow option`

---

### Task 16: ADR-0010, documentation and the dev-check checklist

**Files:**
- Create: `docs/adr/0010-crosslinks-languages-fallbacks.md`, `docs/m6c-dev-check.md`
- Modify: `CLAUDE.md` (status line; Configuration: `nametracker.links.*`, `nametracker.language.*`,
  `nametracker.translation.*` / `NAMETRACKER_TRANSLATION_URL`, `SPRINGER_API_KEY`, `CINII_APPID`,
  `nametracker.content.topup.*`, `nametracker.content.institutional.*`; task types `LINK_WORK`, `LINK_SOURCE`,
  `DETECT_LANGUAGE`, `TRANSLATE_TITLE`; public API `identifier`, `language`, `mentionedIn`, `/api/stats/languages`; a
  "LibreTranslate" section next to "gnfinder": install, `--load-only`, started by hand locally, systemd on the VM,
  port 5055 closed to other hosts), `.env.example`

- [ ] **Step 1: Write ADR-0010** with what this plan pinned: schemes as a registry with per-entity templates (the
  verified table, the dropped ZooBank and BHL API lookup, `PMID`/`PMCID`/`DOAJ` pending a browser check, BPH as text
  because no per-record page exists and the spec's Wikidata P829 is OEIS: BPH is P4569); `BIODIVERSITY_PMC` stored as
  `<collection>/<id>` rows found by lookup, since it has no ids of its own; `LINK_WORK` per work on first indexing and
  batched for back-fills, attempts 30/180 days; Plazi and BLR lookups only for works with acts or of Plazi/BLR
  journals (cost); language evidence table and decision order (LEGACY counted as OpenAlex; Magtech language from the
  non-English `citation_title`, not `citation_language`; Crossref declares none); `title_lang` and kind ORIGINAL for
  sources whose main title is a translation (Magtech, Crossref `original-title`); `title_en` denormalised for lists and
  `fts`; translation to English only, titles only, dropped when a title name is lost; Lingua and LibreTranslate as
  start choices; the top-up order, the 180-day lookup memory, SciELO's prefix map, CiNii only `allow`; institutional
  abstracts never stored; Springer subscription content left out (PDF); the TEI re-check fix. Known limits: no ISSN
  record for rule 5 (DOAJ only); Phytotaxa has no BPH id in IPNI or Wikidata (2026-11-19), so the spec's "Phytotaxa
  shows … BPH" cannot hold until one of them adds it; detection of short titles stays undecided; Biodiversity PMC
  covers what SIBiLS indexes.
- [ ] **Step 2: Local run** (laptop): `mvn -B verify` and `cd frontend && npm test && npm run build` green; Flyway
  applies V20 to the dev DB; then by hand on 18080/18081: `DETECT_LANGUAGE {"afterId":0}` (record before/after counts
  of `language`, and the 148 `uz` works' new values), `LINK_WORK` for 200 ZooKeys works and `LINK_SOURCE` for
  Phytotaxa, ZooKeys and Zootaxa, a LibreTranslate started by hand with `--load-only zh,ja,ru,en` and
  `TRANSLATE_TITLE {"all":true}` on 50 works, one SciELO and one Europe PMC top-up. Record the counts in
  `docs/m6c-dev-check.md`.
- [ ] **Step 3: Write the GBIF VM checklist** in `docs/m6c-dev-check.md`, each item with its SQL or command, for
  what needs real nights and the VM:
  - Every spec "Done when" line of M6c: the ZooKeys work's links (DOI, Crossref, OpenAlex, PMID/PMCID, BLR, Plazi,
    Biodiversity PMC), Phytotaxa's journal links (and the BPH limit), `GET /api/works?identifier=PMCID:…`; the
    extension test (`newSchemeAndLookupNeedNoMigration`) green in CI; the taxon `mentionedIn=TITLE&mentionedIn=ABSTRACT`
    query and a title-only follow over a week; languages decided for all works (share `UNDECIDED` < 2 % of works with
    a title), the 148 `uz` works re-decided, filter, facet and `/api/stats/languages` per year and taxgroup; Magtech and
    SciELO parallels; machine titles labelled and found by an English search; abstracts translated only in the
    browser (network panel shows no request); the institutional route off (zero `institutional_fetch` rows); no call
    to a paid service in the week's logs (`api_usage` services, host list of the HTTP logs).
  - Measurements that confirm the start values: links found per lookup and the share of misses per scheme after one
    week of first indexings; Wikidata/IPNI coverage of CORE/BROAD journals; detection agreement with declared
    languages on works that have both (target ≥ 95 %) and a hand check of 50 DETECTED decisions per top-5 non-English
    language (tune `min-confidence`, `min-title-words`); LibreTranslate resident memory with the loaded models (reject
    above 4 GB), seconds per title, and Markus' rating of 30 Chinese, 30 Japanese and 30 Russian taxonomic titles as
    useful or misleading (more than 20 % misleading: try OPUS-MT on CTranslate2 behind `TitleTranslator`); the drop
    rate of translations that lost a name; abstracts gained per top-up service and Springer's daily calls against the
    cap; Europe PMC, DOAJ and Biodiversity PMC links opened in a browser.
- [ ] **Step 4: Commit** `docs: ADR-0010 and the M6c dev check`
