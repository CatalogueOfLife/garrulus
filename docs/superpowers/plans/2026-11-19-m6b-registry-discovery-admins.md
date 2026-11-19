# M6b: journal registry, discovery, registration and admins — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Journals reach Garrulus from DOAJ, the PKP Beacon list, Plazi, the COL XR references, BLR and user
registrations as `CANDIDATE`s with their signals, ranked by a hidden sample of their papers; admins named by ORCID
curate them on admin pages; disciplines live on a vocabulary Garrulus owns; ceased journals stop being crawled but keep
their history; admins get mail when a layer fails or recovers and when registrations wait.

**Architecture:** One V19 migration adds the registry tables (`source_signal`, `issn_link`, `pkp_beacon`,
`discovery_title`, `source_sample`, `source_registration`, `admin_notice`, the discipline vocabulary and crosswalks) and
the ceased/discipline/score columns of `source`. In the indexer a `CandidateRegistry` is the one door for every
discovery origin and registration (match by ISSN-L, any ISSN, OpenAlex id; then signal or new candidate), and
`IDENTIFY_SOURCE` completes a candidate from Crossref, OpenAlex, DOAJ, the Beacon table and Europe PMC. `web` stays
call-free: registration stores a row and enqueues; mails to admins come from `admin_notice` rows the indexer (and web's
own daily job) write. Admin access is the token or an `ADMIN` session.

**Tech Stack:** JDK 25, Spring Boot 4.1, Spring Security (OIDC, JDBC sessions), MyBatis, Flyway, Postgres 17,
Testcontainers, WireMock, JUnit 5, AssertJ; React, Mantine, TanStack Query, Vitest.

**Spec:** `docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md` (approved 2026-11-19). M6b covers its
sections "Discovery and the journal registry", "Journal classification", "Ceased journals", "Journal registration",
"Admins and admin pages", "Candidate sampling" and the admin mails of "Coverage monitor and layer health". M6a
(`docs/superpowers/plans/2026-11-19-m6a-identity-and-layered-harvest.md`, ADR-0008) is built and is not planned again.
M6c (crosslinks, `LINK_SOURCE`/`LINK_WORK`, languages and translation, `mentionedIn`, abstract and full-text
fallbacks, the University of Copenhagen route) follows in its own plan.

## Global Constraints

- **Free only, no paid service.** Every external integration has a switch (`nametracker.discovery.<origin>.enabled`,
  `nametracker.candidates.sampling.enabled`, `nametracker.discovery.issnl.enabled`, all default true) and all scheduled
  ones also sit behind the master switch `nametracker.harvest.enabled`.
- Every task handler is idempotent under at-least-once delivery; an outage defers (`ServiceOutageException` /
  `HarvestOutage`) and never uses an attempt. One page per task, continuation tasks as checkpoints (ADR-0004).
- Flyway migrations are expand-only and start at `V19__…` (V18 is the latest); only the indexer migrates, `web`
  validates. Widening a CHECK (drop and re-add with more values) counts as expand.
- Every external call goes through `PoliteHttp` and its per-host `HostLimiter`, user agent
  `Garrulus/<version> (+https://www.catalogueoflife.org)`. New host intervals: `doaj.org` 600 ms (DOAJ allows 2/s),
  `tb.plazi.org` 1 s, `dataverse.harvard.edu` 1 s, `www.issn.org` 1 s.
- **`web` makes no outbound calls**: registration stores a row and enqueues `IDENTIFY_SOURCE`; the indexer identifies.
  Admin mails are sent by `web` from rows the indexer wrote (the indexer has no mail support).
- `/api/admin/**` accepts the `X-Admin-Token` header (scripts; no XSRF) **or** an `ADMIN` session (browser; XSRF
  required like every other session write). ADR-0006 gets an amendment saying so (Task 3).
- Admins only through ORCID: an account signed in **through ORCID** whose iD is in `NAMETRACKER_ADMIN_ORCIDS`
  (property `nametracker.admin.orcids`, comma-separated) gets `ROLE_ADMIN`; email sign-in never does. Read at start.
- Every new public GET under `/api` goes into `web/src/main/resources/static/api/openapi.yaml`
  (`OpenApiCoverageTest`); the new public POST `/api/sources/register` too.
- Colours only from the brand tokens (`frontend/src/styles/tokens.css`: `--gr-paper`, `--gr-ink`, `--gr-grey`,
  `--gr-rule-soft`, `--gr-red`; `docs/brand/`); never a new colour.
- Nothing a user registers or discovery finds is harvested before an admin promotes it; candidate sample papers are
  never written to `work`.
- Work and commit directly on `main`. Commit messages never mention Claude or AI and carry no attribution trailer.
- Commands: `JAVA_HOME=~/.sdkman/candidates/java/25.0.3-librca`; one IT: `mvn -B -pl <module> -am verify
  -Dit.test=<IT>`; unit tests: `mvn -B -pl <module> test -Dtest=<Test>`; frontend: `cd frontend && npx vitest run
  <file>`. Run `mvn -B -DskipTests install` once after Task 1 so `-pl` builds see the new store.
- Parked by M6a, decided here: the zero-yield thresholds are tuned before the mails exist (Task 4), and the daily
  restart of an `ONBOARDING`/`BACKFILL` OAI pass that keeps losing its resumption token is capped at 3 and mailed
  (Task 4).

## Review Focus

1. **An admin removed from `NAMETRACKER_ADMIN_ORCIDS`** still holds a 30-day JDBC session with `ROLE_ADMIN`: after the
   restart that drops the iD, the next `/api/admin/**` request must answer 403, not serve the page. → Task 3
   (`removedOrcidLosesAdminAfterRestart`).
2. **A registered web address that points at a private or loopback host, or redirects there** (SSRF from the
   indexer): the registration ends `NOT_IDENTIFIED` with reason `ADDRESS_NOT_PUBLIC` and no request reaches the host.
   → Task 9 (`privateAddressIsNeverFetched`, `redirectToPrivateHostStops`).
3. **ISSNs as people type them** (`1175 5326`, `11755326`, `0002-936x`, `ISSN 1175-5334`, a wrong check digit): the
   first four normalise to the hyphenated upper-case form, the last is a 400 in `web` and never stored. → Task 1
   (`IssnTest`), Task 9 (`registerRejectsBadCheckDigit`).
4. **The same journal arriving twice under different ISSNs** (DOAJ gives the print ISSN, the Beacon the e-ISSN, and
   the ISSN-L table is empty or not yet loaded): it must end as one source with two signals, also when the second
   pair is learned only later by `IDENTIFY_SOURCE` (fold the younger candidate). → Task 5
   (`printAndElectronicIssnMeetThroughIssnL`), Task 6 (`identifyFoldsDuplicateCandidateIntoHolder`).
5. **PKP Beacon cells that are not single values**: an ISSN cell holding two ISSNs joined by a literal backslash-n
   (`"3078-6592\n3078-6606"`), and `NA` in any column. Both ISSNs are kept, `NA` reads as null. → Task 7
   (`multiIssnCellAndNaValues`).

---

### Task 1: V19 schema, source registry columns, ISSN normalisation

**Files:**
- Create: `store/src/main/resources/db/migration/V19__registry_and_admins.sql`
- Create: `store/src/main/java/org/catalogueoflife/nametracker/store/ident/Issn.java`
- Create in `store/src/main/java/org/catalogueoflife/nametracker/store/registry/`: `SourceSignal.java`,
  `SourceSignalMapper.java`, `IssnLinkMapper.java` (+ XML under `store/src/main/resources/.../store/registry/`)
- Modify: `store/.../store/source/Source.java`, `SourceMapper.java`, `SourceMapper.xml`,
  `store/.../store/route/SourceRouteMapper.xml` (rollout query)
- Test: `store/src/test/java/.../store/ident/IssnTest.java`, `store/src/test/java/.../store/registry/RegistryMappersIT.java`

**Interfaces:**
- Produces:
  - `Issn.normalise(@Nullable String raw) -> @Nullable String` (strips `ISSN`, spaces; inserts the hyphen; upper-case
    `X`; null unless 8 characters with a valid mod-11 check digit); `Issn.all(@Nullable String text) -> List<String>`
    (every valid ISSN in a free text, in order, deduplicated).
  - `Source` gains, at the end: `publicationStatus, ceasedReason, lastIssueYear (Integer), continuedBy (Long),
    statusSetBy, statusChanged (Instant), dormantSince (LocalDate), dormantEvidence (String json),
    dormantDismissedAt (Instant), candidateScore (Float), candidateScoredAt (Instant), disciplineSource,
    disciplineSetBy, doajDiscontinued (LocalDate), successorIssns (List<String>)`.
  - `SourceMapper`: `listHarvestable`, `listToOnboard` and the rollout query of `SourceRouteMapper.xml` add
    `AND publication_status = 'ACTIVE'` (`listBackfillable` does not: the snapshot backfill reads a file, not the
    journal); new `List<Source> findByIssnL(String issnL)`, `long insertCandidate(Source s)` (tier `CANDIDATE`,
    `tier_set_by` `SEED`), `int updateIdentity(long id, @Nullable String issnL, List<String> issns, @Nullable String
    title, @Nullable String publisher, @Nullable String country, @Nullable String openalexId, @Nullable String host)`
    (each null argument keeps the column; ISSNs are unioned), `int setCandidateScore(long id, float score)`.
  - `record SourceSignal(long sourceId, String origin, String signalKey, Instant firstSeen, Instant lastSeen, String
    detail /*json*/)`; `SourceSignalMapper`: `int upsert(long sourceId, String origin, String signalKey, String
    detailJson)` (first_seen kept, last_seen = now(), detail replaced), `List<SourceSignal> forSource(long sourceId)`,
    `int delete(long sourceId, String origin, String signalKey)`, `int moveAll(long from, long to)`.
  - `IssnLinkMapper`: `@Nullable String issnL(String issn)`, `List<String> siblings(String issnL)`,
    `long upsertFromTemp()` (see Task 5).

- [ ] **Step 1: Write the migration** `V19__registry_and_admins.sql` (one file for all of M6b):
  - `source.origin` CHECK widened with `'DOAJ','PKP_BEACON','PLAZI','COL_REFERENCES','BLR','REGISTERED'` (look up the
    generated constraint name with `\d source` first).
  - `source` columns: `publication_status text NOT NULL DEFAULT 'ACTIVE' CHECK IN ('ACTIVE','CEASED')`,
    `ceased_reason text CHECK IN ('STOPPED','TITLE_CHANGE','MERGED','UNKNOWN')`, `last_issue_year smallint`,
    `continued_by bigint REFERENCES source`, `status_set_by text CHECK IN ('ADMIN')`, `status_changed timestamptz`,
    `dormant_since date`, `dormant_evidence jsonb`, `dormant_dismissed_at timestamptz`, `candidate_score real`,
    `candidate_scored_at timestamptz`, `discipline_source text CHECK IN ('OPENALEX','DOAJ','TAXGROUP','ADMIN')`,
    `discipline_set_by text NOT NULL DEFAULT 'DERIVED' CHECK IN ('DERIVED','ADMIN')`, `doaj_discontinued date`,
    `successor_issns text[] NOT NULL DEFAULT '{}'`; index on `issn_l`; GIN index on `issns`.
  - `source_signal(source_id bigint REFERENCES source ON DELETE CASCADE, origin text NOT NULL, signal_key text NOT NULL
    DEFAULT '', first_seen timestamptz NOT NULL DEFAULT now(), last_seen timestamptz NOT NULL DEFAULT now(), detail
    jsonb NOT NULL DEFAULT '{}', PRIMARY KEY (source_id, origin, signal_key))`.
  - `issn_link(issn text PRIMARY KEY, issn_l text NOT NULL)`, index on `issn_l`.
  - `pkp_beacon(id bigserial PK, context_name text, issns text[] NOT NULL, oai_url text, set_spec text, country text,
    total_records int, records_2025 int, last_oai_response timestamptz, unresponsive boolean NOT NULL, loaded
    timestamptz NOT NULL)`, GIN on `issns`.
  - `discovery_title(origin text, title_key text, title text NOT NULL, issn text, refs int NOT NULL, datasets int,
    detail jsonb NOT NULL DEFAULT '{}', resolved_issns text[], resolved_at timestamptz, PRIMARY KEY (origin,
    title_key))`.
  - `discipline(code text PK, label text NOT NULL, parent text REFERENCES discipline, replaced_by text REFERENCES
    discipline, sort_order int NOT NULL)`; seeded with the 9 codes and labels of `frontend/src/subfields.ts`
    (sort order alphabetical by label).
  - `discipline_mapping(scheme text, external_code text, discipline text REFERENCES discipline, weight real NOT NULL
    DEFAULT 1, PRIMARY KEY (scheme, external_code, discipline))`, seeded per Task 2's table.
  - `source_classification_raw(source_id bigint REFERENCES source ON DELETE CASCADE, scheme text, code text, label
    text, share real, rank smallint NOT NULL, fetched timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (source_id,
    scheme, code))`; back-fill `OPENALEX_SUBFIELD` rows from `source.subfields` (rank = array position, share null,
    fetched = `modified`) and `UPDATE source SET discipline_source = 'OPENALEX' WHERE subfields <> '{}'`.
  - `source_sample(id bigserial PK, source_id bigint REFERENCES source ON DELETE CASCADE, sampled_at timestamptz NOT
    NULL DEFAULT now(), route text, articles int NOT NULL, with_abstract int NOT NULL, names_per_article real,
    act_share real, taxgroups jsonb NOT NULL DEFAULT '{}', error text)`, index `(source_id, sampled_at DESC)`.
  - `source_registration(id bigserial PK, user_id bigint NOT NULL REFERENCES app_user ON DELETE CASCADE, kind text
    NOT NULL CHECK IN ('ISSN','DOI','URL'), input text NOT NULL, note text, state text NOT NULL DEFAULT 'IDENTIFYING'
    CHECK IN ('IDENTIFYING','IDENTIFIED','NOT_IDENTIFIED','WITHDRAWN'), outcome text CHECK IN
    ('NEW_CANDIDATE','EXISTING','CEASED'), reason text, source_id bigint REFERENCES source ON DELETE SET NULL,
    tier_at_identification text, decision text CHECK IN ('ACCEPTED','DECLINED'), decision_at timestamptz, created
    timestamptz NOT NULL DEFAULT now(), identified_at timestamptz)`, indexes `(user_id, created)` and `(state)`.
  - `admin_notice(id bigserial PK, kind text NOT NULL CHECK IN ('LAYER_FAILING','LAYER_RECOVERED','LAYER_STILL_FAILING',
    'ROUTE_RESTART_CAP','REGISTRATIONS_WAITING'), dedup_key text NOT NULL UNIQUE, subject text NOT NULL, body text NOT
    NULL, created timestamptz NOT NULL DEFAULT now(), mailed_at timestamptz, outcome text)`, partial index on
    `created WHERE mailed_at IS NULL`.
  - `ALTER TABLE source_route ADD long_restarts smallint NOT NULL DEFAULT 0`.
- [ ] **Step 2: Write the tests.** `IssnTest`:
```java
assertThat(Issn.normalise("1175 5326")).isEqualTo("1175-5326");
assertThat(Issn.normalise("11755326")).isEqualTo("1175-5326");
assertThat(Issn.normalise("0002-936x")).isEqualTo("0002-936X");   // check digit X
assertThat(Issn.normalise("ISSN 1175-5334")).isEqualTo("1175-5334");
assertThat(Issn.normalise("1175-5327")).isNull();                 // wrong check digit
assertThat(Issn.all("ISSN 1175-5326 (print), 1175-5334 (online)")).containsExactly("1175-5326", "1175-5334");
```
  `RegistryMappersIT`:
  `signalUpsertKeepsFirstSeenAndBumpsLastSeen`, `signalsMoveToAnotherSource`, `ceasedSourcesAreNotHarvestable`
  (a CEASED CORE onboarded source is absent from `listHarvestable` and `listToOnboard`, present in
  `listBackfillable`), `updateIdentityUnionsIssnsAndKeepsNulls`, `migrationBackfilledRawSubfields` (a source with
  `subfields {1105,1103}` has two `OPENALEX_SUBFIELD` raw rows with ranks 0 and 1).
- [ ] **Step 3: Run** `mvn -B -pl store -am verify -Dit.test=RegistryMappersIT` and `-Dtest=IssnTest` → FAIL.
- [ ] **Step 4: Implement** the migration, records and mappers (XML in the style of `SourceMapper.xml`).
- [ ] **Step 5: Run** both, then `mvn -B -pl store -am verify` → PASS (existing mapper ITs pass with the new `Source`
  fields null/default). Then `mvn -B -DskipTests install`.
- [ ] **Step 6: Commit** `feat(store): V19 registry, registration, discipline and admin notice tables`

---

### Task 2: disciplines on our own vocabulary

**Files:**
- Create: `store/.../store/discipline/{Discipline,DisciplineMapper,RawClassification,ClassificationRawMapper}.java` + XML
- Create: `indexer/.../indexer/stats/{DisciplineDeriver,DisciplineService}.java`
- Modify: `indexer/.../stats/SourceCategoriser.java`, `indexer/.../source/SourceSeeder.java`,
  `indexer/.../source/SeedProperties.java` (+ `taxonomyTopics`), `indexer/.../openalex/OpenAlexClient.java`
  (`Optional<JsonNode> topic(String id)`), `store/.../account/SubscriptionMapper.{java,xml}`
  (`rewriteDiscipline`), `web/.../public_/VocabController.java`, `web/.../public_/WorkController.java`,
  `web/src/main/resources/static/api/openapi.yaml`, `indexer/src/main/resources/application.yml`
- Test: `indexer/src/test/java/.../stats/DisciplineDeriverTest.java`, `.../stats/SourceCategoriserIT.java` (extend),
  `.../source/SourceSeederTest.java` (extend), `web/src/test/java/.../public_/VocabControllerIT.java`,
  `.../public_/WorkControllerIT.java` (extend)

**Interfaces:**
- Consumes: V19 tables (Task 1).
- Produces:
  - `record Discipline(String code, String label, @Nullable String parent, @Nullable String replacedBy, int sortOrder)`;
    `DisciplineMapper`: `List<Discipline> all()`, `Map<String,String> replacements()` (code → replaced_by, rows with a
    replacement only), `List<Map<String,Object>> mappings()` (scheme, external_code, discipline, weight).
  - `record RawClassification(String scheme, String code, @Nullable String label, @Nullable Float share, int rank)`;
    `ClassificationRawMapper`: `int replace(long sourceId, String scheme, List<RawClassification> rows)` (delete the
    scheme's rows, insert), `List<RawClassification> forSource(long sourceId)`.
  - `DisciplineDeriver.derive(List<RawClassification> raw, Map<String, Double> taxgroupShares) -> Derived`;
    `record Derived(List<String> codes, @Nullable String primary, @Nullable String source)`. Constructed with the
    mapping rows and replacements. Rules, first that yields codes wins:
    1. `OPENALEX`: `OPENALEX_SUBFIELD` rows that map, keeping rows with share null or ≥ 0.05; order by share desc,
       then rank (this reproduces today's `SourceSeeder.subfields`).
    2. `DOAJ`: `DOAJ_LCC` rows, weight summed per discipline, kept at ≥ 0.5, order by weight desc then code.
    3. `TAXGROUP`: for each taxgroup share, the first group up its ancestor chain (`TaxGroups.withAncestors`) that has a
       `TAXGROUP` mapping; weight = share × mapping weight, kept at ≥ 0.20, order desc.
    Every code passes through `replaced_by` (transitively) and is deduplicated.
  - `DisciplineService.apply(long sourceId)` (skips `discipline_set_by = 'ADMIN'`; writes `subfields`,
    `primary_subfield`, `discipline_source`) and `applyAll()`; `CATEGORISE_SOURCES` calls `applyAll()` after its
    current work, then `SubscriptionMapper.rewriteDiscipline(String from, String to, String note)` for every retired
    code (filters JSON `subfield` replaced; `xr_note`, `xr_note_at = now()`).
  - `GET /api/vocab/discipline` → `[{code,label,parent,replacedBy,sortOrder}]`; `/api/works` accepts `discipline` as
    alias of `subfield` (400 when both are given and differ) and resolves either through `replaced_by`.
  - `SeedProperties.taxonomyTopics` from `nametracker.seed.taxonomy-topics` (default: the 17 ids now in
    `SourceSeeder.TAXONOMY_TOPICS`); the seeder writes raw `OPENALEX_SUBFIELD` (every subfield of the topics, share =
    topic-count share, rank by share) and `OPENALEX_TOPIC` (top 10 topics) rows and calls `DisciplineService.apply`;
    once per seeding run it asks `OpenAlexClient.topic(id)` for each configured id and logs
    `WARN configured taxonomy topic {} is unknown to OpenAlex` on a 404.

Crosswalk seed (start values, tunable by editing rows and running `CATEGORISE_SOURCES`):

| scheme | external code | discipline | weight |
|---|---|---|---|
| OPENALEX_SUBFIELD | each of the 9 codes | itself | 1 |
| DOAJ_LCC | QL1-991 (Zoology) | 1103 | 1 |
| DOAJ_LCC | QK1-989 (Botany) | 1110 | 1 |
| DOAJ_LCC | QH1-278.5 (Natural history) | 1105 | 1 |
| DOAJ_LCC | QR1-502 (Microbiology) | 2404 | 1 |
| DOAJ_LCC | QH540-549.5 (Ecology) | 2303 | 1 |
| DOAJ_LCC | QE701-760 (Paleontology) | 1911 | 1 |
| DOAJ_LCC | SH1-691 (Aquaculture, fisheries) | 1104 | 1 |
| DOAJ_LCC | QH301-705.5 (Biology, general) | 1105 | 0.5 |
| DOAJ_LCC | RC109-216 (Infectious and parasitic diseases) | 2405 | 0.5 |
| TAXGROUP | Insects | 1109 | 1 |
| TAXGROUP | Plants | 1110 | 1 |
| TAXGROUP | Fungi | 1110 / 2404 | 0.5 each |
| TAXGROUP | Prokaryotes, Viruses, Protists | 2404 | 1 |
| TAXGROUP | Animals | 1103 | 1 |

- [ ] **Step 1: Write the tests.** `DisciplineDeriverTest`: `openalexSubfieldsInShareOrder` (raw 1105 .6, 1103 .3,
  9999 .1 → `[1105, 1103]`, primary 1105, source `OPENALEX`); `nullSharesKeepRankOrder`; `doajFallbackWhenNoOpenalex`
  (QL1-991 → `[1103]`, `DOAJ`); `halfWeightAloneIsKept` (QH301-705.5 only → `[1105]`); `taxgroupFallbackWalksAncestors`
  (Coleoptera 0.7, Gastropods 0.3 → `[1109, 1103]`, `TAXGROUP`); `retiredCodeResolvesThroughReplacedBy` (mapping to
  X, X replaced by 1105 → `[1105]`); `nothingKnownGivesEmpty` (`[]`, null, null).
  `SourceCategoriserIT`: `crosswalkChangeRederivesWithoutExternalCall` (update a mapping row, run the task, WireMock
  saw no request), `adminSetDisciplinesSurviveRecompute`, `followOnRetiredDisciplineIsRewrittenWithNote`.
  `SourceSeederTest`: `seedWritesRawSubfieldsAndTopics`, `unknownConfiguredTopicIsWarned`.
  `VocabControllerIT.disciplinesServeTheNineCodes` (codes and labels equal `subfields.ts`); `WorkControllerIT`:
  `disciplineAliasFilters`, `retiredSubfieldStillFilters`, `conflictingSubfieldAndDisciplineIs400`.
- [ ] **Step 2: Run** the unit tests and ITs → FAIL.
- [ ] **Step 3: Implement**; add `/api/vocab/discipline` and the `discipline` parameter to `openapi.yaml`; document
  `nametracker.seed.taxonomy-topics` in `CLAUDE.md` and `application.yml`.
- [ ] **Step 4: Run** → PASS; `mvn -B -pl web -am verify -Dit.test=OpenApiCoverageTest*` passes.
- [ ] **Step 5: Commit** `feat: disciplines on a Garrulus vocabulary with crosswalks from OpenAlex, DOAJ and taxonomic focus`

---

### Task 3: admins by ORCID and the admin session

**Files:**
- Create: `web/.../web/auth/AdminOrcids.java`, `web/.../web/api/AdminAccess.java`,
  `web/.../web/api/AdminAccessConfigured.java`
- Modify: `web/.../auth/GarrulusPrincipal.java`, `CurrentUser.java`, `OrcidLoginSuccessHandler.java`,
  `SecurityConfig.java`, `CsrfCookieFilter.java` (`SPA_PATHS` += `/api/admin/**`), `MeController.java` (`Me.admin`),
  every `Admin*Controller` (header check → `AdminAccess`; `@Conditional(AdminAccessConfigured.class)`),
  `web/src/main/resources/application.yml` (`nametracker.admin.orcids: ${NAMETRACKER_ADMIN_ORCIDS:}`),
  `.env.example`, `docs/adr/0006-accounts-and-alerts.md` (amendment), `CLAUDE.md`
- Delete: `web/.../api/AdminAuth.java` (replaced), `AdminTokenConfigured.java` (folded into `AdminAccessConfigured`;
  `AdminTokenConfiguredTest` becomes `AdminAccessConfiguredTest`)
- Test: `web/src/test/java/.../web/api/AdminAccessIT.java`, `.../auth/AdminOrcidsTest.java`,
  `.../auth/OrcidLoginIT.java` (extend), existing admin controller ITs (still green with the token)

**Interfaces:**
- Produces:
  - `AdminOrcids(String csv)`: `boolean contains(@Nullable String orcid)`, `boolean any()`; iDs normalised (prefix
    `https://orcid.org/` dropped, upper-case `X`, pattern `\d{4}-\d{4}-\d{4}-\d{3}[\dX]`; an invalid entry is a WARN
    at start and ignored).
  - `GarrulusPrincipal.authentication(long userId, boolean admin)` (authorities `ROLE_USER`, plus `ROLE_ADMIN`);
    `CurrentUser.signIn(long userId, boolean admin, …)`; the email-link path passes `false`, ORCID passes
    `adminOrcids.contains(user.orcid())`.
  - `AdminAccess.check(@Nullable String tokenHeader)`: header present → constant-time compare with
    `nametracker.admin.token` (401 on mismatch or when no token is configured; never falls back to the session);
    no header → authenticated `GarrulusPrincipal` with `ROLE_ADMIN` **and** the account's ORCID still in
    `AdminOrcids` (looked up per request) → ok; signed in otherwise → 403; not signed in → 401.
  - `AdminAccessConfigured`: token set or `AdminOrcids.any()`.
  - CSRF: `ignoringRequestMatchers` keeps `/api/unsubscribe`, `/api/auth/link` and replaces `/api/admin/**` by
    "path `/api/admin/**` AND header `X-Admin-Token` present".
  - `MeController.Me` gains `boolean admin` (true only for a session with `ROLE_ADMIN` whose iD is still listed).

- [ ] **Step 1: Write the tests.** `AdminOrcidsTest`: `normalisesUrlsAndLowercaseX`, `invalidEntriesIgnored`.
  `AdminAccessIT` (with `TestSignIn`): `tokenStillWorksWithoutXsrf`, `wrongTokenIs401EvenWithAdminSession`,
  `adminOrcidSessionReadsStatus` (200), `emailSignInOfListedOrcidAccountIsNotAdmin` (403),
  `otherAccountGets403OnEveryAdminPath` (GET status, PATCH source, POST tasks), `signedOutIs401`,
  `adminSessionWriteNeedsXsrf` (PATCH without `X-XSRF-TOKEN` → 403, with it → 200),
  `removedOrcidLosesAdminAfterRestart` (Review Focus 1: session created with ROLE_ADMIN, context restarted with an
  empty list → 403), `adminEndpointsMappedWithOrcidsButNoToken`. `OrcidLoginIT.listedOrcidGetsAdminRole` and
  `meReportsAdmin`.
- [ ] **Step 2: Run** `mvn -B -pl web -am verify -Dit.test='AdminAccessIT,OrcidLoginIT'` → FAIL.
- [ ] **Step 3: Implement.** Write the ADR-0006 amendment ("Amendment 2026-11-19 (M6b): admin sessions"): the token
  stays for scripts and is exempt from XSRF only when the header is sent; an `ADMIN` session needs XSRF; the role
  comes only from an ORCID sign-in of a listed iD and is re-checked against the list on every admin request; the
  XSRF cookie is also issued on `/api/admin/**`.
- [ ] **Step 4: Run** those ITs and `mvn -B -pl web -am verify` → PASS.
- [ ] **Step 5: Commit** `feat(web): admins by ORCID; admin endpoints accept the token or an admin session`

---

### Task 4: admin notices, layer mails, tuned zero-yield rule, OAI restart cap

**Files:**
- Create: `store/.../store/notice/{AdminNotice,AdminNoticeMapper}.java` + XML
- Create: `web/.../web/mail/AdminMailJob.java`
- Modify: `indexer/.../layer/coverage/{LayerHealthCalculator,CoverageCheckHandler}.java`,
  `store/.../layer/LayerHealthMapper.xml` (per-weekday daily yields), `indexer/.../layer/oai/HarvestOaiHandler.java`,
  `indexer/.../route/RouteLifecycle.java` and `VerifyRouteHandler.java` (reset `long_restarts`),
  `store/.../route/SourceRouteMapper.{java,xml}` (`incrementLongRestarts`, `resetLongRestarts`),
  `web/.../mail/MailService.java` (`sendAdminNotice`), both `application.yml`, `CLAUDE.md`
- Test: `indexer/src/test/java/.../layer/coverage/LayerHealthCalculatorTest.java` (extend),
  `.../coverage/CoverageCheckHandlerIT.java` (extend), `.../layer/oai/OaiLayerIT.java` (extend),
  `web/src/test/java/.../mail/AdminMailJobIT.java`

**Interfaces:**
- Consumes: `AdminOrcids` (Task 3).
- Produces:
  - `record AdminNotice(long id, String kind, String dedupKey, String subject, String body, Instant created,
    @Nullable Instant mailedAt, @Nullable String outcome)`; `AdminNoticeMapper`: `int insert(String kind, String
    dedupKey, String subject, String body)` (ON CONFLICT (dedup_key) DO NOTHING), `List<AdminNotice> claimUnmailed(int
    limit)` (`UPDATE … SET mailed_at = now() WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING *`),
    `int setOutcome(long id, String outcome)`, `@Nullable AdminNotice latest(String kindPrefix, String keyPrefix)`.
  - `AdminMailJob.run()` (`nametracker.admin.mail-cron`, default `0 */5 * * * *`, ShedLock `admin-mail`): claims up
    to 20 notices, sends each as one mail to every verified address of an account whose ORCID is in `AdminOrcids`;
    outcome `SENT`, `FAILED`, or `SUPPRESSED` (no mail host, or no admin address: logged "admin mail suppressed" with
    the notice id, never an address). At most once: a failed send is not retried (the weekly reminder repeats it).
  - `CoverageCheckHandler` writes notices (keys carry the day, so a redelivered check adds nothing):
    `LAYER_FAILING` (`layer-failing:<LAYER>:<day>`) when the layer turns FAILING; `LAYER_RECOVERED`
    (`layer-recovered:<LAYER>:<day>`) when it is OK and its latest `layer-` notice is FAILING or STILL_FAILING;
    `LAYER_STILL_FAILING` (`layer-still:<LAYER>:<day>`) when FAILING and its latest `layer-` notice is 7 or more days
    old. Subject `Garrulus: layer <LAYER> is failing` / `… has recovered` / `… is still failing`; body: state, failed
    days, zero-yield days, 7-day yield, weekly median, miss share, and the link `<public URL>/admin/layers`
    (`nametracker.public-url` is added to the indexer's config for this, default `http://localhost:5173`).
  - Tuned rule (start values, properties under `nametracker.coverage.*`, tuned after a month):
    `LayerHealthCalculator.Inputs` is unchanged, the calculator gains `zero-fail-days` and `yield-min-median`;
    `CoverageCheckHandler` counts a **zero-yield day** only on a day with a
    DONE run whose **expected yield** (median of the same weekday's daily sightings over the last 8 weeks) is at least
    `zero-yield-min-expected` = 3.0, so a weekday-publishing journal's weekend and an issue-based Magtech feed's quiet
    days never count. FAILING by zero yield needs `zero-fail-days` = 3 such days in a row (run failures keep
    `fail-days` = 2); the yield-ratio rule (`yield-ratio` 0.5) applies only when the weekly median is at least
    `yield-min-median` = 14.
  - Restart cap: in `HarvestOaiHandler`'s "lost again after a restart" branch for `ONBOARDING`/`BACKFILL`,
    `incrementLongRestarts(routeId)`; when it exceeds `nametracker.layers.oai.max-long-restarts` (3) the route goes
    `BROKEN` with "resumption tokens rejected in 4 long passes", nothing is re-enqueued, and a `ROUTE_RESTART_CAP`
    notice (`route-restart-cap:<routeId>:<day>`) names the route, source title and mode. The count resets on the
    route's last page and on a passing `VERIFY_ROUTE`.

- [ ] **Step 1: Write the tests.** `LayerHealthCalculatorTest`: `threeZeroDaysFailing`, `twoZeroDaysOnlyDegraded`,
  `twoFailedRunDaysStillFailing`, `smallMedianSkipsYieldRatio`. `CoverageCheckHandlerIT`:
  `weekdayPublisherQuietWeekendIsOk` (eight weeks of Mon–Fri 10 a day, Sat/Sun DONE runs with 0 → OK),
  `magtechIssueFeedQuietDaysAreOk` (expected < 3 → zero days not counted, OK), `failingWritesOneNoticeAndRedeliveryAddsNone`,
  `recoveryAfterFailingWritesRecoveredNotice`, `stillFailingRemindsWeekly` (days 1, 8; not 2–7),
  `openalexBlockTwoNightsGivesDegradedThenFailingNotice`. `OaiLayerIT`: `fourthLongPassRestartBreaksRouteAndNotifies`,
  `lastPageResetsRestartCount`. `AdminMailJobIT` (with `CapturingMailSender`): `mailsEveryAdminOnce`,
  `noAdminAddressIsSuppressed`, `nonAdminVerifiedAccountsGetNothing`, `secondRunSendsNothing`.
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='CoverageCheckHandlerIT,OaiLayerIT'`,
  `-Dtest=LayerHealthCalculatorTest`, `mvn -B -pl web -am verify -Dit.test=AdminMailJobIT` → FAIL.
- [ ] **Step 3: Implement**; document the new properties and the mail cron in `CLAUDE.md`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: admin mails for failing and recovered layers; zero-yield rule by weekday; OAI restart cap`

---

### Task 5: one identity for journals — ISSN-L table and the candidate registry

**Files:**
- Create in `indexer/.../indexer/registry/`: `IssnlRefreshHandler.java`, `SourceIdentity.java`, `Candidate.java`,
  `CandidateRegistry.java`
- Modify: `store/.../registry/IssnLinkMapper.xml` (temp table load and upsert), `store/.../source/SourceMapper.xml`
  (`findByOpenalexId` unchanged), `indexer/.../IndexerConfig.java` (beans), `indexer/src/main/resources/application.yml`
- Fixture: `indexer/src/test/resources/issnl/issnltables.zip` (built in the test from three lines, see Step 1)
- Test: `indexer/src/test/java/.../registry/{IssnlRefreshHandlerIT,CandidateRegistryIT}.java`

**Interfaces:**
- Consumes: `Issn`, `SourceSignalMapper`, `SourceMapper.findByIssnL/insertCandidate/updateIdentity` (Task 1).
- Produces:
  - `REFRESH_ISSNL` (`IssnlRefreshHandler.TYPE`, payload `{}`): streams
    `nametracker.discovery.issnl.url` (default `https://www.issn.org/wp-content/uploads/2014/03/issnltables.zip`,
    27 MB, refreshed daily by the ISSN IC; checked 2026-11-19) and reads the entry whose name starts with
    `ISSN_ISSN-L_table` (tab-separated, header `ISSN	ISSN-L`), COPYs it into a temp table and upserts `issn_link` in
    one statement (`ON CONFLICT (issn) DO UPDATE … WHERE issn_l <> EXCLUDED.issn_l`). Connection failure, 5xx or a
    stalled read (5 min) → outage; a zip without that entry → WARN, task DONE, table kept. Off with
    `nametracker.discovery.issnl.enabled=false`. (The ISSN IC page offers the table "on demand" through a form; the
    URL is the file the form serves. If it stops answering, matching falls back to plain ISSNs.)
  - `record Candidate(String origin, String signalKey, List<String> issns, @Nullable String title, @Nullable String
    publisher, @Nullable String country, @Nullable String openalexId, String detailJson, List<RawClassification>
    classifications, @Nullable RouteHint route)`; `record RouteHint(String layer, String configJson)`.
  - `SourceIdentity.find(List<String> issns, @Nullable String openalexId) -> @Nullable Source`: by ISSN-L (each ISSN
    through `issn_link`, then `findByIssnL` and `findByIssn` of every sibling), then any ISSN, then the OpenAlex id;
    the lowest source id wins when several match.
  - `CandidateRegistry.offer(Candidate c) -> Offer`; `record Offer(long sourceId, boolean created, String tier, String
    publicationStatus)`. Existing source: signal upsert, ISSNs unioned (`updateIdentity`), raw classifications of the
    candidate's schemes replaced, nothing else (tier, status and routes untouched; a CEASED or EXCLUDED source only
    gets the signal). New source: `insertCandidate` (origin = `c.origin`, ISSN-L from `issn_link`), signal, raw
    classifications, the route hint stored `PENDING` with origin `DISCOVERED` (`REGISTERED` for origin
    `REGISTERED`) plus `VERIFY_ROUTE`, and `IDENTIFY_SOURCE {"sourceId":n}` at `BACKFILL`, key `identify:s<n>`. Runs
    in one transaction with an advisory lock on the smallest ISSN (or the OpenAlex id), so two offers of one journal
    cannot create two rows.

- [ ] **Step 1: Write the tests.** `IssnlRefreshHandlerIT` (WireMock serves a zip built in the test with
  `ISSN_ISSN-L_table.20261118.txt`): `loadsAndUpdatesLinks` (second load with one changed ISSN-L updates it),
  `zipWithoutTableKeepsLinks`, `downloadFailureIsOutage`. `CandidateRegistryIT`: `newCandidateGetsSignalRouteAndIdentify`,
  `existingSourceGetsSignalOnly` (tier CORE unchanged, no task), `printAndElectronicIssnMeetThroughIssnL` (Review
  Focus 4: DOAJ offers the print ISSN, PKP the e-ISSN, `issn_link` links both → one source, two signals),
  `ceasedSourceOnlyGetsSignal`, `excludedSourceOnlyGetsSignal`, `concurrentOffersCreateOneSource` (two threads),
  `secondOfferIsIdempotent` (no second route, the identify dedup key holds).
- [ ] **Step 2: Run** `mvn -B -pl indexer -am verify -Dit.test='IssnlRefreshHandlerIT,CandidateRegistryIT'` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): ISSN-L table and one candidate registry for every discovery origin`

---

### Task 6: `IDENTIFY_SOURCE` completes a candidate

**Files:**
- Create in `indexer/.../registry/`: `IdentifySourceHandler.java`, `DoajClient.java`, `JournalCompletion.java`
- Modify: `indexer/.../crossref/CrossrefClient.java` (`journal`, `journalsByTitle`),
  `indexer/.../openalex/OpenAlexClient.java` (`sourceByIssn`), `indexer/.../layer/europepmc/ProbeEuropePmcHandler.java`
  (a `{"sourceId"}` probe also for candidates), `store/.../source/SourceMapper.{java,xml}` (`foldInto`)
- Fixtures `indexer/src/test/resources/registry/`: `crossref-journal-1175-5326.json`,
  `openalex-source-issn.json`, `doaj-journal-2118-9773.json` (record each from the live API, trimmed),
  `doaj-journal-discontinued.json` (hand-edited copy with `discontinued_date` and `is_replaced_by`)
- Test: `indexer/src/test/java/.../registry/{DoajClientTest,IdentifySourceHandlerIT}.java`

**Interfaces:**
- Consumes: `CandidateRegistry`, `SourceIdentity` (Task 5), `DisciplineService.apply` (Task 2).
- Produces:
  - `CrossrefClient.journal(String issn) -> Optional<JsonNode>` (`/journals/{issn}`, 404 → empty);
    `journalsByTitle(String title, int rows) -> List<JsonNode>` (`/journals?query=…&rows=`).
  - `OpenAlexClient.sourceByIssn(String issn) -> Optional<JsonNode>` (`/sources/issn:{issn}`, through the budget; a
    `BudgetExhaustedException` skips the step, it is not an outage).
  - `DoajClient(PoliteHttp, String baseUrl /* https://doaj.org/api/v4 */)`: `Optional<DoajJournal> byIssn(String issn)`
    (`/search/journals/issn:<issn>`), `DoajPage journalsByLcc(String code, int page)` (`/search/journals/
    bibjson.subject.code:"<code>"?page=&pageSize=100`); `record DoajJournal(String id, String title, List<String>
    issns /*pissn, eissn*/, @Nullable String publisher, @Nullable String country, List<RawClassification> lcc,
    @Nullable String licenceUrl, @Nullable LocalDate discontinued, List<String> replacedBy, @Nullable String
    homepage)`; `record DoajPage(List<DoajJournal> journals, int total)`.
  - `IDENTIFY_SOURCE {"sourceId":n}` (this task; `{"registrationId":n}` comes in Task 9): skipped for a CEASED source.
    Steps, each written as soon as it answers: ISSN-L and siblings from `issn_link`; Crossref `journal` (title when the
    stored one equals an ISSN, publisher, ISSNs); OpenAlex `sourceByIssn` (OpenAlex id, country, host, `is_oa`,
    `is_in_doaj`, raw `OPENALEX_SUBFIELD`/`OPENALEX_TOPIC` as in the seeder); DOAJ `byIssn` (raw `DOAJ_LCC`, country
    when null, `in_doaj`, `doaj_discontinued`, `successor_issns`); the `pkp_beacon` row of any ISSN → OAI route hint
    `{"base":oai_url,"set":set_spec,"prefix":"oai_dc","issns":[…]}` unless the source has an OAI route with that base
    and set; `ProbeEuropePmcHandler.enqueue`; `DisciplineService.apply`. Any outage defers the whole task (the steps
    are upserts, so the repeat is harmless).
  - **Fold:** when OpenAlex names an id another source holds, and this source is a `CANDIDATE` with no works and no
    `ACTIVE` route, `SourceMapper.foldInto(from, to)` moves signals, routes, raw rows, samples and registrations to
    the holder (a route or signal the holder already has is dropped) and deletes the candidate (INFO log); otherwise
    WARN and leave both.

- [ ] **Step 1: Write the tests.** `DoajClientTest`: `parsesLccIssnsCountryLicence`, `readsDiscontinuedAndSuccessor`,
  `emptyResultIsEmpty`. `IdentifySourceHandlerIT` (WireMock for Crossref, OpenAlex, DOAJ, Europe PMC):
  `completesCandidateFromAllServices` (ISSNs, ISSN-L, publisher, OpenAlex id, country, raw rows, disciplines
  derived, EPMC probe queued), `beaconRowGivesPendingOaiRoute`, `doajDiscontinuedIsStored`,
  `identifyFoldsDuplicateCandidateIntoHolder` (Review Focus 4), `openalexBudgetOutSkipsStep`,
  `crossrefDownDefersWithoutAttempt`, `ceasedSourceIsSkipped`, `redeliveryChangesNothing`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): IDENTIFY_SOURCE completes candidates from Crossref, OpenAlex, DOAJ and the Beacon`

---

### Task 7: discovery from DOAJ and the PKP Beacon

**Files:**
- Create in `indexer/.../registry/discovery/`: `DiscoverySource.java`, `DiscoverSourcesHandler.java`,
  `DoajDiscovery.java`, `PkpBeaconDiscovery.java`, `BeaconFile.java`, `Countries.java`
- Create: `store/.../registry/{PkpBeaconMapper,DiscoveryTitleMapper}.java` + XML
- Fixtures: `indexer/src/test/resources/discovery/doaj-lcc-ql-page1.json` (record
  `https://doaj.org/api/v4/search/journals/bibjson.subject.code:%22QL1-991%22?pageSize=100`, trimmed to 3 journals,
  `total` kept), `beacon-sample.tab` (header row and 8 rows cut from the live `beacon.tab`: two matching keywords,
  one excluded word, one inactive, one unresponsive, one multi-ISSN cell, one with `NA`, one off-topic)
- Test: `indexer/src/test/java/.../registry/discovery/{BeaconFileTest,CountriesTest,DoajDiscoveryIT,PkpBeaconDiscoveryIT}.java`

**Interfaces:**
- Consumes: `CandidateRegistry.offer` (Task 5), `DoajClient.journalsByLcc` (Task 6).
- Produces:
  - `interface DiscoverySource { String origin(); @Nullable String page(JsonNode payload) throws IOException; }` —
    returns the next payload (JSON) or null when done; `DISCOVER_SOURCES` (`{"origin":…}` plus the source's own
    position fields) dispatches by origin, enqueues the continuation at the task's priority with key
    `discover:<origin>:<sha12 of the payload>`, and is off per origin with `nametracker.discovery.<origin lower
    case, underscores as hyphens>.enabled`.
  - DOAJ (origin `DOAJ`): positions `{"code","page"}` over `nametracker.discovery.doaj.lcc-codes` (start:
    `QL1-991,QK1-989,QH1-278.5,QR1-502,QH540-549.5,QE701-760`, about 600 journals; Biology (General) QH301-705.5 has
    482 and is left out); each journal → `Candidate` with `DOAJ_LCC` raw rows, signal detail `{"doajId","lcc":[…]}`,
    signal key `''`.
  - `BeaconFile.read(InputStream) -> Stream<BeaconRow>`; `record BeaconRow(String contextName, List<String> issns,
    @Nullable String oaiUrl, @Nullable String setSpec, @Nullable String country, @Nullable Integer totalRecords,
    @Nullable Integer records2025, @Nullable Instant lastOaiResponse, boolean unresponsive)`. Columns by header name
    (`context_name, issn, oai_url, country, total_record_count, record_count_2025, set_spec, last_oai_response,
    unresponsive_endpoint, unresponsive_context`); `NA` is null; an ISSN cell splits on the two characters `\n` and
    on real newlines, each through `Issn.normalise`.
  - PKP (origin `PKP_BEACON`, one task): downloads `nametracker.discovery.pkp.url` (default
    `https://dataverse.harvard.edu/api/access/datafile/14084919`, `beacon.tab` of doi:10.7910/DVN/OCZNVY v7, CC0,
    32 MB, follows the redirect to S3), replaces `pkp_beacon` with every row that has an ISSN (one transaction), then
    offers the rows that pass the filter with route hint `OAI` `{"base":oai_url,"set":set_spec,"prefix":"oai_dc",
    "issns":[…]}` and signal detail `{"context","records2025","oaiUrl"}`.
  - Filter (start values, tunable as lists `nametracker.discovery.pkp.include` / `.exclude`), on the accent-folded,
    lower-cased `context_name`, each term matched at a word start: include `zoolog, taxonom, entomolog, botan,
    mycolog, fauna, flora, natural history, museum, herpetolog, ornitholog, ichthyolog, malacolog, arachnolog,
    acarolog, lichenolog, bryolog, phycolog, nematolog, parasitolog, biodivers, systematic, phytotax, zootax, historia
    natural, histoire naturelle, faune, flore, museu, museo`; exclude `museolog, museum studies, veterinar, medic,
    pharmac`; activity: not unresponsive and (`records2025 ≥ 5` or `lastOaiResponse` within 365 days) — a zero count
    alone never excludes (Zootaxa reports 0).
  - `Countries.code(@Nullable String name) -> @Nullable String`: ISO 3166-1 alpha-2 from English names
    (`Locale.getISOCountries()`) plus aliases `Russia RU, Iran IR, Turkey/Türkiye TR, Viet Nam VN, South
    Korea/Korea, Republic of KR, Czechia CZ, Taiwan TW, Bolivia BO, Venezuela VE, Tanzania TZ, Syria SY, Laos LA,
    Moldova MD`; DOAJ and OpenAlex already give codes.

- [ ] **Step 1: Write the tests.** `BeaconFileTest`: `readsColumnsByHeader`, `multiIssnCellAndNaValues` (Review Focus
  5). `CountriesTest`: `englishNamesAndAliases`. `DoajDiscoveryIT`: `pagesThroughCodeAndOffersCandidates` (3 journals
  → 3 candidates with `DOAJ_LCC` rows; a continuation for page 2 only when `total` says so), `outageDefersPage`,
  `switchOffLeavesTaskQueued`. `PkpBeaconDiscoveryIT`: `loadsTableAndOffersOnlyFilteredActiveJournals` (2 of 8),
  `offeredJournalGetsPendingOaiRoute`, `reloadReplacesTable`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; document the properties in `CLAUDE.md`. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): discover candidate journals from DOAJ subjects and the PKP Beacon`

---

### Task 8: discovery from Plazi, COL references and BLR; the monthly schedule

**Files:**
- Create in `indexer/.../registry/discovery/`: `JournalTitleKey.java`, `TitleResolver.java`, `PlaziDiscovery.java`,
  `ColReferencesDiscovery.java`, `BlrDiscovery.java`, `DiscoveryScheduler.java`
- Modify: `store/.../layer/LayerUnmatchedMapper.{java,xml}` (`byIssn`), `indexer/.../source/SourceSeeder.java`
  (signal `SEED_TOPIC` per seeded source; never re-tiers a CEASED source)
- Fixtures: `indexer/src/test/resources/discovery/plazi-stats-2025.json` (record
  `https://tb.plazi.org/GgServer/srsStats/stats?outputFields=bib.source+doc.articleUuid&groupingFields=bib.source&FP-bib.year=2025&format=JSON`,
  trimmed to 6 rows including a numeric label such as `"183"`), `crossref-journals-query.json`,
  `xr-references.zip` (built in the test: a `Reference.tsv` with `col:` prefixed headers)
- Test: `.../registry/discovery/{JournalTitleKeyTest,PlaziDiscoveryIT,ColReferencesDiscoveryIT,BlrDiscoveryIT,DiscoverySchedulerTest}.java`

**Interfaces:**
- Consumes: `DiscoverySource` (Task 7), `CrossrefClient.journalsByTitle` (Task 6), `CandidateRegistry` (Task 5).
- Produces:
  - `JournalTitleKey.of(String title) -> @Nullable String`: NFKD without marks, lower case, `&` → `and`, punctuation
    to spaces, leading `the` dropped, spaces collapsed; null when no letter remains.
  - `TitleResolver.resolvePage(String origin, @Nullable String afterKey, int size) -> @Nullable String`: up to `size`
    (100) `discovery_title` rows of the origin with `resolved_at` null or older than 180 days, keyed after
    `afterKey`; for each, `journalsByTitle(title, 5)` and accept the first item whose `JournalTitleKey` equals the
    row's; accepted → `resolved_issns` and an offer (origin of the row, detail from the row); else `resolved_at` only.
    Returns the last key, or null at the end. A Crossref outage defers the page.
  - Plazi (origin `PLAZI`): phase 1 (`{}`) reads the URL above for the current year and the two before
    (`nametracker.discovery.plazi.base-url`, default `https://tb.plazi.org/GgServer/srsStats/stats`), sums
    `DocCount` (treatments) and `DocArticleUuid` (articles) per `BibSource` label, keeps labels with at least
    `min-articles` = 3 articles, upserts `discovery_title` (refs = articles, detail `{"treatments","articles","label"}`),
    then continues as phase 2 (`{"resolve":true,"after":…}`) through `TitleResolver`.
  - COL references (origin `COL_REFERENCES`): phase 1 streams `nametracker.finder.xr-zip-url` (as
    `BuildDictionaryHandler` does, same outage rules), reads the entry named `Reference.tsv`/`.txt`/`.csv` (any
    directory, case-insensitive), headers with or without the `col:` prefix; groups by normalised `issn` when present,
    else by `JournalTitleKey(containerTitle)`; counts references and distinct `sourceID`; rows with neither are
    skipped and counted. ISSN groups with at least `min-references` = 10 are offered at once (detail
    `{"references","datasets","title"}`); title groups with at least 10 go to `discovery_title` (the 2000 largest,
    `max-titles`) and phase 2 resolves them.
  - BLR (origin `BLR`, one task): `LayerUnmatchedMapper.byIssn(Instant since /* 12 months */)` → rows (issn,
    container_title, distinct DOIs, last first_seen, an example DOI); ISSNs with at least 2 DOIs are offered (detail
    `{"dois","lastSeen","example"}`).
  - `DiscoveryScheduler` (`nametracker.discovery.cron`, default `0 15 1 1 * *` UTC, behind the harvest switch):
    `REFRESH_ISSNL` (key `issnl:<yyyy-mm>`) and one `DISCOVER_SOURCES {"origin":o}` per enabled origin of `DOAJ,
    PKP_BEACON, PLAZI, COL_REFERENCES, BLR` (key `discover:<o>:<yyyy-mm>`), all at `BACKFILL`. The OpenAlex seeding
    stays `SEED_SOURCES` on its own monthly cron and is the `SEED_TOPIC` origin.

- [ ] **Step 1: Write the tests.** `JournalTitleKeyTest`: `foldsAccentsAndAmpersand` (`"Revista Mexicana de
  Biodiversidad"` = `"REVISTA MEXICANA DE BIODIVERSIDAD."`), `numbersOnlyGiveNull` (`"183"`). `PlaziDiscoveryIT`:
  `aggregatesThreeYearsAndSkipsNumericLabels`, `resolvesTitlesThroughCrossrefAndOffers`,
  `unmatchedTitleIsMarkedNotOffered`. `ColReferencesDiscoveryIT`: `issnGroupsOfferedDirectly`,
  `prefixedHeadersAreRead`, `smallGroupsSkipped`. `BlrDiscoveryIT`: `twoDoisMakeACandidate`,
  `ceasedJournalSignalOnly` (the signal's `last_seen` is today: the revival evidence of Task 11).
  `DiscoverySchedulerTest` (`Clock.fixed`, `MockEnvironment` as in `LayerSchedulerTest`):
  `monthlyEnqueuesEveryEnabledOrigin`, `harvestSwitchOffEnqueuesNothing`, `disabledOriginSkipped`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; document properties and task types in `CLAUDE.md`.
  **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): discover journals from Plazi, the COL references and BLR, monthly`

---

### Task 9: journal registration

**Files:**
- Create: `store/.../registry/{SourceRegistration,RegistrationMapper}.java` + XML
- Create: `store/.../net/PublicHost.java` (host classification moved out of `web/.../api/RouteConfigCheck.java`, which
  now delegates to it)
- Create: `web/.../web/registry/{RegistrationController,RegistrationMailJob}.java`
- Create: `indexer/.../registry/{RegistrationResolver,JournalPage}.java`
- Modify: `indexer/.../registry/IdentifySourceHandler.java` (`registrationId`), `web/.../mail/MailService.java`
  (`sendRegistrationDecision`), `web/.../auth/SecurityConfig.java` (POST `/api/sources/register` authenticated),
  `openapi.yaml`, `CLAUDE.md`
- Fixtures `indexer/src/test/resources/registry/`: `crossref-work-doi.json`, `journal-page-ojs.html`,
  `journal-page-magtech.html` (head and footer of `https://www.biodiversity-science.net/EN/`, trimmed)
- Test: `web/src/test/java/.../registry/{RegistrationControllerIT,RegistrationMailJobIT}.java`,
  `indexer/src/test/java/.../registry/{RegistrationResolverIT,PublicHostTest}.java`

**Interfaces:**
- Consumes: `Issn`, `IdScheme.DOI.normalise` (store), `CandidateRegistry.offer` (Task 5), `IdentifySourceHandler`
  (Task 6), `AdminNoticeMapper` (Task 4).
- Produces:
  - `POST /api/sources/register` (session, XSRF) `{"issn"|"doi"|"url": "…", "note": "…"}`: exactly one of the three
    (400 otherwise); ISSN through `Issn.normalise`, DOI through `IdScheme.DOI.normalise`, URL `http(s)` with a host,
    no user info, at most 2000 characters; note at most 500 characters; 21st registration of a user within 24 hours
    → 429. Stores the row (`IDENTIFYING`) and enqueues `IDENTIFY_SOURCE {"registrationId":n}` at `LIVE`, key
    `identify:r<n>`; answers 202 `{"id":n}`. No outbound call.
  - `GET /api/me/registrations` (newest first, 100 at most), `GET /api/me/registrations/{id}` (404 for another
    user's), `DELETE /api/me/registrations/{id}` (state `WITHDRAWN`, deletes its `REGISTERED` signal; the candidate
    stays). DTO `RegistrationDto(long id, String kind, String input, @Nullable String note, String state, @Nullable
    String outcome, @Nullable String reason, String status /*IDENTIFYING, COVERED, CANDIDATE, DECLINED, CEASED,
    NOT_IDENTIFIED, WITHDRAWN*/, @Nullable JournalDto journal, Instant created, @Nullable Instant identifiedAt)`;
    `JournalDto(long id, String title, List<String> issns, @Nullable String publisher, @Nullable String country, String
    tier, String publicationStatus, List<String> routes /*layer names*/)`. `status` is derived at read time from the
    source's tier and status.
  - `RegistrationResolver.resolve(String kind, String input) -> Resolution`; `record Resolution(List<String> issns,
    @Nullable String title, @Nullable String publisher, @Nullable String openalexId, @Nullable RouteHint route,
    @Nullable String reason)`. ISSN: as given. DOI: Crossref `work(doi)` → `ISSN` and `container-title`; else OpenAlex
    `work("doi:…")` → its source; else reason `DOI_UNKNOWN`. URL: `PublicHost.problem(uri)` first (else reason
    `ADDRESS_NOT_PUBLIC`); `JournalPage.fetch(uri)` follows at most 3 redirects by hand, checking each hop with
    `PublicHost`, reads at most 2 MB of `text/html`; ISSNs from `citation_issn`, `prism.issn`, `dc.identifier` metas,
    else `Issn.all` of the text (first 3); title from `citation_journal_title` or `og:site_name`; a page containing
    `Powered by Beijing Magtech` (case-insensitive) gives route hint `MAGTECH {"site":"<scheme>://<host>","issn":…}`;
    a `<meta name="generator" content="Open Journal Systems…">` page gives hint `OAI {"base":"<journal url>/oai",
    "prefix":"oai_dc","issns":[…]}`; no ISSN → reason `NO_ISSN_FOUND`.
  - `IDENTIFY_SOURCE {"registrationId":n}`: no-op unless `IDENTIFYING`; resolves; an existing source → `IDENTIFIED`,
    outcome `EXISTING` (or `CEASED`), `REGISTERED` signal (key = registration id, detail `{"registrationId":n}`: the
    note and the user are read through the registration, so deleting the account removes them); a new one → offer
    with origin `REGISTERED`, outcome `NEW_CANDIDATE`; `tier_at_identification` recorded; the completion of a new
    source runs as its own `IDENTIFY_SOURCE {"sourceId"}` at `LIVE`, so the user's answer is not held up by it.
    Failure to resolve → `NOT_IDENTIFIED` with the reason. An outage defers.
  - `RegistrationMailJob` (`nametracker.registrations.mail-cron`, `0 */5 * * * *`, ShedLock): registrations whose
    `tier_at_identification` was `CANDIDATE` and whose source is now `CORE`/`BROAD` (ACCEPTED) or `EXCLUDED`
    (DECLINED) get `decision`, `decision_at` and one mail to a verified address ("Your registered journal … is now
    harvested" / "… will not be harvested"); without a verified address, logged as suppressed. Daily at
    `nametracker.registrations.admin-note-cron` (`0 0 7 * * *` UTC) it inserts a `REGISTRATIONS_WAITING` notice
    (key `registrations:<date>`) when registrations were identified as candidates since the previous such notice:
    count and up to 20 lines (journal title, input, link `<public URL>/admin/journals/<id>`).

- [ ] **Step 1: Write the tests.** `PublicHostTest`: the cases of `RouteConfigCheckTest`, moved. `RegistrationControllerIT`:
  `registerByIssnStoresAndEnqueues` (202, row `IDENTIFYING`, one `IDENTIFY_SOURCE` task, no outbound request —
  the WireMock server sees nothing), `registerRejectsBadCheckDigit` (Review Focus 3), `exactlyOneInputRequired`,
  `signedOutIs401`, `withoutXsrfIs403`, `twentyFirstPerDayIs429`, `otherUsersRegistrationIs404`,
  `withdrawRemovesSignal`. `RegistrationResolverIT`: `registerByIssnCreatesCandidateWithSignal`,
  `registerByArticleDoiFindsJournal`, `coveredJournalReportsCovered` (existing CORE → `EXISTING`, status `COVERED`,
  no new source), `ceasedJournalReportsCeased`, `privateAddressIsNeverFetched` and `redirectToPrivateHostStops`
  (Review Focus 2), `magtechFooterGivesMagtechRoute`, `ojsGeneratorGivesOaiRoute`, `unknownDoiNotIdentified`,
  `secondDeliveryIsNoOp`. `RegistrationMailJobIT`: `acceptedMailsOnce`, `declinedMailsOnce`,
  `coveredAtRegistrationNeverMails`, `dailyNoteListsNewCandidatesOnce`.
- [ ] **Step 2: Run** the web and indexer ITs → FAIL. **Step 3: Implement**; add `POST /api/sources/register` to
  `openapi.yaml`. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: journal registration by ISSN, article DOI or address, identified by the indexer`

---

### Task 10: candidate sampling and the candidate score

**Files:**
- Create in `indexer/.../registry/sample/`: `SampleFetcher.java`, `SampleArticle.java`, `SampleSourceHandler.java`,
  `SampleScheduler.java`, `CandidateScore.java`, `CandidateScorer.java`
- Create: `store/.../registry/{SourceSample,SourceSampleMapper}.java` + XML
- Modify: `CrossrefClient` (`recentArticles`), `OpenAlexClient` (`recentWorks`), `EuropePmcSearchClient`
  (`first(String query, String sort, int pageSize)`), `indexer/src/main/resources/application.yml`, `CLAUDE.md`
- Test: `.../registry/sample/{CandidateScoreTest,SampleSourceHandlerIT,SampleSchedulerTest}.java`

**Interfaces:**
- Consumes: `NameFinder` (bean, as `FindNamesHandler` gets it), `DocumentLoader.abstractDoc(Work)`, `ClbClient.match`,
  `TaxGroups.of(ClbMatch)`, `XrReleaseService` (current key), `SourceSignalMapper`.
- Produces:
  - `record SampleArticle(String title, @Nullable String abstractText, @Nullable String abstractJats)`;
    `SampleFetcher.fetch(Source s) -> Sample`; `record Sample(@Nullable String route, List<SampleArticle> articles)`.
    Routes in order of cost, the first that yields at least 5 articles of the last 2 years wins, at most 20 newest:
    `CROSSREF` (`recentArticles(issns, from, 20)`: `filter=issn:…,from-pub-date:…,type:journal-article`,
    `sort=published&order=desc`), `OAI` (a non-BROKEN OAI route: `listRecords` from 2 years ago, at most 3 pages,
    newest 20 by date), `EUROPEPMC` (`(ISSN:"a" OR …) AND FIRST_PDATE:[from TO today]`, sort `P_PDATE_D desc`,
    page size 20), `OPENALEX` (`recentWorks(S…, from, 20)`, sorted `publication_date:desc`, inverted abstract
    rebuilt). No route → `route` null, empty list.
  - `SAMPLE_SOURCE {"sourceId":n}`: skipped for CEASED or non-`CANDIDATE` sources. Each article becomes a transient
    `Work` (never inserted) for `DocumentLoader.abstractDoc`; the finder runs per article; per sample:
    `articles`, `with_abstract`, `names_per_article` (distinct `Mention.name` of kinds other than `OTU`, averaged),
    `act_share` (articles with at least one `Act`), `taxgroups` (up to 60 distinct names matched with
    `ClbClient.match` against the current release, memoised in the task; `TaxGroups.of` shares of the top 5 groups,
    as JSON). Inserts one `source_sample` row and updates the score. Finder not ready → defer 30 min (as
    `FIND_NAMES`); CLB or any route outage → defer. Writes nothing to `work`, `mention`, `name_string` or
    `name_match`.
  - `CandidateScore.of(Signals sig, @Nullable SampleStats sample) -> float` with `record Signals(int origins, int
    plaziArticles, int colReferences, boolean registered)`, `record SampleStats(int articles, double namesPerArticle,
    double actShare)`. Start formula (weights under `nametracker.candidates.score.*`, tuned once a few hundred are
    sampled):
```
sampleScore = 0.6 * min(namesPerArticle / 8, 1) + 0.4 * min(actShare / 0.25, 1)
signalScore = 0.4 * min(origins / 3, 1) + 0.3 * min(log10(1 + plaziArticles) / 2, 1)
            + 0.2 * min(log10(1 + colReferences) / 3, 1) + 0.1 * (registered ? 1 : 0)
score       = sample with >= 5 articles ? 0.7 * sampleScore + 0.3 * signalScore : 0.5 * signalScore
```
  - `CandidateScorer.rescoreAll()` recomputes every candidate's score from its signals and latest sample.
  - `SampleScheduler` (`nametracker.candidates.sample-cron`, `0 30 1 * * *` UTC, behind the harvest switch and
    `nametracker.candidates.sampling.enabled`): `rescoreAll()`, then `SAMPLE_SOURCE` at `BACKFILL` (key
    `sample:<id>:<yyyy-mm-dd>`) for up to `sample-per-night` = 200 active `CANDIDATE`s that are not CEASED and have
    no sample or one older than 365 days; registered ones first, then never sampled, then by score descending.

- [ ] **Step 1: Write the tests.** `CandidateScoreTest`: `noSampleUsesHalfTheSignalScore`, `richSampleScoresHigh`
  (10 names/article, act share 0.3, 2 origins → > 0.8), `smallSampleCountsAsNoSample` (4 articles).
  `SampleSourceHandlerIT` (WireMock Crossref, CLB; a stub `NameFinder` that returns fixed mentions and one act):
  `sampleStoresAggregatesOnly` (one `source_sample` row; `work`, `mention`, `name_string` counts unchanged — the
  "no sample paper in work" criterion), `fallsBackToOaiWhenCrossrefHasTooFew`, `noRouteKeepsSignalScore`,
  `finderNotReadyDefers`, `clbOutageDefers`, `ceasedOrPromotedSourceSkipped`. `SampleSchedulerTest`:
  `registeredFirstThenUnsampledThenScore`, `atMostTwoHundred`, `switchOffEnqueuesNothing`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; document properties in `CLAUDE.md`. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(indexer): sample candidate journals and rank them by names, acts and signals`

---

### Task 11: ceased journals

**Files:**
- Create: `indexer/.../layer/coverage/DormancyCheck.java`
- Modify: `indexer/.../layer/LayerHarvestHandler.java`, `source/OnboardSourceHandler.java`,
  `route/{RouteBackfill,VerifyRouteHandler}.java`, `layer/coverage/CoverageCheckHandler.java` (calls
  `DormancyCheck`), `store/.../source/SourceMapper.{java,xml}` (`setPublicationStatus`, `setDormancy`,
  `dismissDormant`), `store/.../route/SourceRouteMapper.{java,xml}` (`pauseAll(sourceId, reason)`,
  `resumePaused(sourceId, reason)`), `store/.../account/SubscriptionMapper.{java,xml}` (`noteSourceFollows`),
  `web/.../api/AdminSourceController.java` (status fields of `PATCH`), `web/.../public_/SourceController.java`,
  `dto/SourceDetail.java`, `dto/SourceItem.java`, `web/.../me/{SubscriptionController,SubscriptionDto}.java`,
  `openapi.yaml`
- Test: `indexer/src/test/java/.../layer/coverage/DormancyCheckIT.java`, `.../layer/LayerHarvestHandlerIT.java`
  (extend), `web/src/test/java/.../api/AdminSourceControllerIT.java` (extend), `.../public_/SourceControllerIT.java`
  (extend), `.../me/SubscriptionControllerIT.java` (extend), `.../digest/DigestJobIT.java` (extend)

**Interfaces:**
- Consumes: V19 columns, `AdminAccess` (Task 3).
- Produces:
  - `PATCH /api/admin/sources/{id}` accepts `publicationStatus` (`ACTIVE`|`CEASED`), `ceasedReason` (required with
    `CEASED`), `lastIssueYear` (1800 to this year), `continuedBy` (an existing other source id), `dismissDormant`
    (true). Setting `CEASED`: columns, `status_set_by` ADMIN, `status_changed`; `pauseAll(id, "journal ceased")`
    (ACTIVE/VERIFIED/PENDING routes → PAUSED with that `last_error`); `noteSourceFollows(id, note)` sets `xr_note`/
    `xr_note_at` of every SOURCE follow of it to `Ceased publication · last issue <year>` plus `. Continued as
    <title>.` when set (the digest prints it once, the existing XR-note path). Setting `ACTIVE` again:
    `resumePaused(id, "journal ceased")` puts those routes back to `VERIFIED`, so the nightly rollout gives each an
    `ONBOARDING` pass (OpenAlex refills through its 120-day window); no follow note.
  - No crawling: `LayerHarvestHandler.handle` ends a job whose source is CEASED without a run (INFO);
    `OnboardSourceHandler`, `RouteBackfill.enqueue`, `VERIFY_ROUTE`, `SAMPLE_SOURCE` and `IDENTIFY_SOURCE {"sourceId"}`
    skip it; `listHarvestable`/`listToOnboard`/the rollout exclude it (Task 1); coverage counts no misses for it.
    `REPROCESS_NAMES`, XR switches and `RESCORE_SALIENCE` are unchanged.
  - `DormancyCheck.run(LocalDate day)` (called by `COVERAGE_CHECK` after the layers): for every ACTIVE-status source
    with works, `lastPub = max(coalesce(publication_date, make_date(year, 12, 31)))`, `usualGap = 1095 / max(1, works
    published in [lastPub − 3 years, lastPub])` days; dormant when `day − lastPub > max(365, 3 × usualGap)` (start
    values `nametracker.dormancy.{min-days=365, gap-factor=3}`). Sets `dormant_since` (kept while it stays dormant;
    null when not) and `dormant_evidence` `{"lastPublished","usualGapDays","brokenRoutes","doajDiscontinued",
    "successorIssns"}`. A dismissal hides the proposal for 365 days. A CEASED source with a signal whose `last_seen`
    is after `status_changed` is a revival proposal (computed in the admin read, Task 12).
  - Public: `GET /api/sources?publicationStatus=ACTIVE|CEASED`; `SourceItem` and `SourceDetail` gain
    `publicationStatus`, `ceasedReason`, `lastIssueYear`, `continuedBy` (`SourceRef(id, title)` or null);
    `SubscriptionDto` gains `publicationStatus` and `lastIssueYear` for SOURCE follows (null otherwise).

- [ ] **Step 1: Write the tests.** `AdminSourceControllerIT`: `ceasingPausesRoutesAndNotesFollows`,
  `ceasedNeedsReason` (400), `continuedByMustExistAndDiffer`, `reactivationPutsRoutesBackToVerified`.
  `LayerHarvestHandlerIT.ceasedSourceJobMakesNoCall` (WireMock zero requests, task DONE, no run row).
  `DormancyCheckIT`: `twelveQuietMonthsMakesDormant`, `slowQuarterlyJournalNotDormantAtThirteenMonths` (usual gap
  150 days → threshold 450), `dismissedHiddenForAYear`, `evidenceListsBrokenRoutesAndDoaj`. `SourceControllerIT`:
  `ceasedFieldsAndFilter`, `ceasedWorksStayListed`. `SubscriptionControllerIT.sourceFollowShowsCeased`.
  `DigestJobIT.ceasedNoteAppearsOnce`. A `ReprocessNamesHandlerIT` case `ceasedJournalWorksAreReprocessed`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; add the fields and the filter to `openapi.yaml`.
  **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: ceased journals keep their history, are never crawled, and dormant ones are proposed`

---

### Task 12: admin API for journals, registrations and layers; promotion of Crossref-only journals

**Files:**
- Modify: `web/.../api/AdminSourceController.java` (list filters and sort, detail, discipline override, sample
  trigger), `store/.../source/SourceMapper.{java,xml}` (`adminList`, `adminCount`), `store/.../layer/LayerHealthMapper.{java,xml}`
  (`coverageRows(limit)`, `health(days)`)
- Create: `web/.../api/{AdminRegistrationController,AdminLayersController}.java`
- Modify: `indexer/.../source/OnboardSourceHandler.java`, `indexer/.../layer/crossref/CrossrefHistoryLayer.java`
  (accepts `ONBOARDING`), `indexer/.../route/RouteLifecycle.java` (Crossref onboarding pass)
- Test: `web/src/test/java/.../api/{AdminSourceControllerIT,AdminRegistrationControllerIT,AdminLayersControllerIT}.java`,
  `indexer/src/test/java/.../source/OnboardSourceHandlerIT.java` (extend)

**Interfaces:**
- Consumes: Tasks 1–11.
- Produces:
  - `GET /api/admin/sources` adds `origin`, `country`, `publicationStatus`, `dormant` (bool), `registered` (bool) and
    `sort` (`score` default for `tier=CANDIDATE`, else `id`; also `title`); answers `{"total":n,"items":[SourceView]}`
    (breaking only for scripts reading the old array; noted in `CLAUDE.md`). `SourceView(long id, @Nullable String
    openalexId, String title, String tier, boolean active, @Nullable Instant onboardedAt, @Nullable Instant
    lastHarvested, @Nullable Integer worksPerYear, @Nullable String issnL, List<String> issns, String origin, @Nullable
    String country, @Nullable String publisher, @Nullable Float candidateScore, String publicationStatus, boolean
    dormant, boolean revival, @Nullable String proposal, List<String> signalOrigins, @Nullable SampleView sample,
    Map<String, Long> routes, int registrations)`; `SampleView(Instant sampledAt, @Nullable String route, int articles, int withAbstract,
    @Nullable Float namesPerArticle, @Nullable Float actShare, JsonNode taxgroups)`.
  - `GET /api/admin/sources/{id}` → `SourceAdminDetail(SourceView source, List<SignalView> signals, List<SampleView>
    samples, List<SourceRoute> routes, List<AdminRegistrationView> registrations, List<String> disciplines, String
    disciplineSetBy, @Nullable String disciplineSource, List<RawClassification> classifications, @Nullable Integer
    lastIssueYear, @Nullable String ceasedReason, @Nullable SourceRef continuedBy, @Nullable LocalDate dormantSince,
    @Nullable JsonNode dormantEvidence)`; `SignalView(String origin, Instant firstSeen, Instant lastSeen, JsonNode
    detail)`, a `REGISTERED` signal's detail enriched with the note and the user's display name.
  - `PATCH /api/admin/sources/{id}` also accepts `disciplines` (codes of the vocabulary, not retired; sets
    `discipline_set_by` ADMIN and `discipline_source` ADMIN) and `releaseDisciplines` (true: back to DERIVED, then
    `DisciplineService` result on the next `CATEGORISE_SOURCES`). Accept and decline stay the tier change.
  - Name-density feedback for harvested journals, `proposal` computed at read time from `name_density` and `act_rate`
    (written nightly by `CATEGORISE_SOURCES`; start values `nametracker.admin.proposals.*`, tuned later): `PROMOTE`
    for a BROAD journal with act rate ≥ 0.10 and at least 5 names per work; `DEMOTE` for a CORE journal with act rate
    < 0.02 and fewer than 1 name per work, and for a BROAD journal with fewer than 0.5; else null. Filter
    `proposal=true`. Shown, never applied.
  - `POST /api/admin/sources/{id}/sample` → 202, `SAMPLE_SOURCE` at `LIVE`, key `sample:<id>:manual:<yyyy-mm-dd>`.
  - `GET /api/admin/registrations?state=&limit=&offset=` → `AdminRegistrationView(long id, String kind, String input,
    @Nullable String note, String state, @Nullable String outcome, @Nullable String reason, @Nullable Long sourceId,
    @Nullable String sourceTitle, @Nullable String userDisplayName, Instant created)`.
  - `GET /api/admin/layers?days=28` (1 to 90) → `LayersView(List<LayerHealth> health, Map<String,Map<String,Long>>
    contribution, Map<String,Map<String,Map<String,Long>>> contributionByCountry, List<CoverageRow> coverage)`;
    `CoverageRow(long sourceId, String title, @Nullable String country, String layer, int sightings28d, int misses)`,
    the 200 rows with most misses.
  - Promotion: `ONBOARD_SOURCE` of a source without an OpenAlex id and without a PENDING/VERIFIED/ACTIVE route, but
    with ISSNs, enqueues `HARVEST_CROSSREF_HISTORY` in `ONBOARDING` mode from today − 120 days to today at
    `REPROCESS`, key `onboard-crossref:<id>`; its last page (`RouteLifecycle`) sets `onboarded_at`, enqueues the
    Europe PMC probe and `RouteBackfill.enqueue` (Crossref history to 2000), as a route's onboarding does.

- [ ] **Step 1: Write the tests.** `AdminSourceControllerIT`: `candidatesSortByScoreWithSignalsAndSample`,
  `filtersByOriginCountryAndRegistered`, `densityProposalsShown`, `detailShowsRegistrationNoteAndRoutes`, `adminDisciplinesOverride`,
  `retiredDisciplineRejected`, `acceptRegisteredJournalStartsOnboarding` (PATCH tier CORE → `ONBOARD_SOURCE`),
  `sampleTriggerQueuesTask`. `AdminRegistrationControllerIT.listsByState`. `AdminLayersControllerIT`:
  `healthContributionAndCoverage`, `daysOutOfRangeIs400`. `OnboardSourceHandlerIT`:
  `crossrefOnlyJournalOnboardsThroughCrossrefHistory` (pass DONE → `onboarded_at` set, `ROUTE_BACKFILL` work queued,
  works origin `ONBOARDING`, no hit), `journalWithRouteWaitsForItsRoute`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement**; update the admin endpoint list in `CLAUDE.md`.
  **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: admin API for candidates, registrations and layers; Crossref-only journals can be promoted`

---

### Task 13: frontend — disciplines from the vocabulary, ceased badges, registration

**Files:**
- Create: `frontend/src/disciplines.ts` (replaces `subfields.ts`), `frontend/src/components/CeasedBadge.tsx`,
  `frontend/src/components/RegisterJournalDialog.tsx`, `frontend/src/components/RegisteredJournals.tsx`
- Delete: `frontend/src/subfields.ts`, `subfields.test.ts` (moved to `disciplines.test.ts`)
- Modify: `frontend/src/api.ts` (types and calls: `getDisciplines`, `registerJournal`, `getRegistration`,
  `listRegistrations`, `withdrawRegistration`; `Me.admin`; source fields), `components/FilterSidebar.tsx`,
  `pages/{JournalsPage,SourcePage,FollowingPage,AccountPage}.tsx`
- Test: `frontend/src/disciplines.test.ts`, `components/{CeasedBadge,RegisterJournalDialog}.test.tsx`,
  `pages/{JournalsPage,SourcePage,FollowingPage,AccountPage}.test.tsx` (extend)

**Interfaces:**
- Consumes: `GET /api/vocab/discipline` (Task 2), registration endpoints (Task 9), source fields (Task 11).
- Produces:
  - `useDisciplines()` (TanStack Query, key `['vocab','discipline']`, `staleTime` 1 h) and `disciplineLabel(code,
    list)`, `disciplineOptions(list, current?)` with the behaviour of today's `subfieldLabel`/`subfieldOptions`
    (unknown code shown as itself; a retired code shows its replacement's label).
  - `CeasedBadge({status, lastIssueYear, continuedBy})`: renders `Ceased publication · last issue <year>` (or without
    the year) and `Continued as <title>` linked to `/source/<id>`; nothing for ACTIVE. Text in `--gr-ink` on
    `--gr-paper` with a `--gr-red` rule, no new colour.
  - `RegisterJournalDialog`: a "Register a journal" button on `JournalsPage` (signed out: link to `/signin` with
    `next=/journals`); the dialog takes ISSN, article DOI or web address (one segmented control) and a note; posts,
    then polls `GET /api/me/registrations/{id}` every 2 s for up to 30 s; shows the identified journal (title, ISSNs,
    publisher, status: covered / candidate / ceased / not identified with its reason) with "Done" and "Not this
    journal" (withdraws); after 30 s: "Still identifying. The result will appear under Registered journals on your
    account page."
  - `AccountPage` gets a "Registered journals" section (`RegisteredJournals`: input, date, status, journal link).

- [ ] **Step 1: Write the tests.** `disciplines.test.ts`: `labelsFromVocabulary`, `retiredCodeShowsReplacement`,
  `unknownCodeShownAsCode`. `CeasedBadge.test.tsx`: `rendersYearAndSuccessorLink`, `nothingForActive`.
  `RegisterJournalDialog.test.tsx` (fake timers, mocked fetch): `postsIssnAndShowsIdentifiedJournal`,
  `stopsPollingAfterThirtySeconds`, `showsNotIdentifiedReason`, `notThisJournalWithdraws`, `badIssnShowsServerError`.
  Page tests: `JournalsPage` shows the button and badges, `SourcePage` the badge and successor, `FollowingPage` the
  badge on a SOURCE follow, `AccountPage` the list.
- [ ] **Step 2: Run** `cd frontend && npx vitest run src/disciplines.test.ts src/components src/pages` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test` and `npm run build` → PASS.
- [ ] **Step 5: Commit** `feat(frontend): disciplines from the vocabulary, ceased badges, journal registration`

---

### Task 14: frontend — admin pages

**Files:**
- Create in `frontend/src/pages/admin/`: `AdminLayout.tsx` (guard and sub-menu), `AdminJournalsPage.tsx`,
  `AdminJournalPage.tsx`, `AdminLayersPage.tsx`, `AdminStatusPage.tsx`, `admin.module.css`
- Modify: `frontend/src/routes.tsx`, `components/SiteMenu.tsx` (Admin entry when `me.admin`), `api.ts` (admin calls,
  always with the XSRF header like other session writes)
- Test: `frontend/src/pages/admin/*.test.tsx`, `components/SiteMenu.test.tsx` (extend)

**Interfaces:**
- Consumes: the admin API (Tasks 3, 11, 12), `GET /api/admin/status`.
- Produces routes: `/admin` → `/admin/journals`; `/admin/journals` (list), `/admin/journals/:id` (detail),
  `/admin/layers`, `/admin/status`. `AdminLayout` renders "Not found" for non-admins (no hint that the page exists).
  Layouts (start values, within the tokens):
  - **Journals list:** filter row (tier, default `CANDIDATE`; origin; country; status ACTIVE/CEASED/dormant;
    registered only; proposals only) and a table sorted by score: title (link), country, origin, signal chips, sample
    (names per article, act share, top group), routes (layer:status), score, flags (dormant, revival, ceased,
    promote/demote proposal). Paged by 50.
  - **Journal detail:** header with title, ISSNs, publisher, country, tier, status; action bar: "Accept as CORE",
    "Accept as BROAD", "Decline" (EXCLUDED), "Sample now"; sections: signals (origin, first and last seen, detail;
    registrations with note and user), samples, routes (add route form with layer and config JSON, pause/activate),
    disciplines (multi-select from the vocabulary, "Release to derived"), publication status (cease form: reason,
    last issue year, successor search by title; reactivate; dismiss dormant with its evidence).
  - **Layers:** a layer × day grid of the last 28 days (OK `--gr-ink` outline, DEGRADED `--gr-grey` fill, FAILING
    `--gr-red` fill, with the state as text for screen readers), the contribution table (first found, only found per
    layer), the same by country, and the coverage table (journal, layer, sightings, misses).
  - **Status:** the sections of `GET /api/admin/status` as plain tables (tasks by type and state, works by state and
    text level, today's API usage, last runs, XR releases, backfill runs, finder rollouts).

- [ ] **Step 1: Write the tests.** `SiteMenu.test.tsx.adminEntryOnlyForAdmins`; `AdminLayout.test.tsx.nonAdminSeesNotFound`;
  `AdminJournalsPage.test.tsx`: `listsCandidatesByScore`, `filtersByOrigin`; `AdminJournalPage.test.tsx`:
  `acceptSendsTierPatchWithXsrf`, `ceaseFormRequiresReason`, `disciplineOverrideSaves`, `addRoutePostsConfig`;
  `AdminLayersPage.test.tsx`: `gridShowsStatesAsText`; `AdminStatusPage.test.tsx.rendersTaskCounts`.
- [ ] **Step 2: Run** `cd frontend && npx vitest run src/pages/admin src/components/SiteMenu.test.tsx` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test` and `npm run build` → PASS.
- [ ] **Step 5: Commit** `feat(frontend): admin pages for journals, layers and status`

---

### Task 15: ADR-0009, documentation and the dev-check checklist

**Files:**
- Create: `docs/adr/0009-registry-discovery-admins.md`, `docs/m6b-dev-check.md`
- Modify: `CLAUDE.md` (status line, Configuration, task types `REFRESH_ISSNL`, `DISCOVER_SOURCES`, `IDENTIFY_SOURCE`,
  `SAMPLE_SOURCE`, admin endpoints, public API additions), `.env.example`, `docs/adr/0008-layered-harvest.md` (the
  "mails belong to M6b" lines point to ADR-0009)

- [ ] **Step 1: Write ADR-0009** with the decisions this plan pinned: one V19; `CandidateRegistry` as the only door and
  its matching order; fold of duplicate candidates; signals without user data (registration id only); `admin_notice`
  as the mail queue (the indexer writes, `web` sends, at most once; the spec's "web sends from rows not mailed yet"
  realised as notices so the route restart cap and the registration note share it); the zero-yield rule by weekday
  and its start values; the OAI restart cap of 3; admin role only from an ORCID sign-in and re-checked per request;
  the DOAJ LCC codes, PKP filter terms, Plazi query, COL reference grouping and candidate score as start values to
  tune; sampling through CLB matching (the COL dictionary carries no classification); the snapshot backfill still
  imports ceased journals; reuse of `xr_note` for source follow notes; the ISSN-L file URL and its fallback;
  Crossref-only promotion. Known limits: an undocumented ISSN-L URL; Beacon titles in many languages pass the
  English and Romance keyword filter only partly; COL references without container title or ISSN are skipped.
- [ ] **Step 2: Local run** (laptop, what a local run can show): `mvn -B verify` and `cd frontend && npm test && npm
  run build` green; start the dev-run indexer and web with `NAMETRACKER_ADMIN_ORCIDS` set to Markus' iD,
  `ADMIN_TOKEN` set and Mailpit; Flyway applies V19; then by hand: `REFRESH_ISSNL`, `DISCOVER_SOURCES` for `DOAJ`,
  `PKP_BEACON`, `PLAZI`, `BLR` (COL references only if the XR zip download is acceptable on the network), a
  registration by ISSN and one by article DOI through the SPA, a few `SAMPLE_SOURCE`, an ORCID sign-in that shows the
  admin pages, a non-admin account's 403, one accept from the journal page, and an `admin_notice` row inserted by hand
  that reaches Mailpit. Record counts (candidates per origin, signals, samples, notices) in `docs/m6b-dev-check.md`.
- [ ] **Step 3: Write the GBIF VM checklist** in `docs/m6b-dev-check.md` for what needs real nights: the first
  monthly discovery and its candidate counts per origin and country (China, India called out); 200 samples a night
  and the score distribution; the OpenAlex block test of the spec with the FAILING and the recovery mail (one each);
  a week without a FAILING mail for weekday-quiet or Magtech layers; dormant proposals after the first coverage run;
  ceasing one journal and checking that no layer called its hosts the next night; a `REPROCESS_NAMES {"all":true}`
  that includes its works; "no call to a paid service" in the week's logs. Each item with its SQL or command.
- [ ] **Step 4: Commit** `docs: ADR-0009 and the M6b dev check`
