# M2 Harvest & Content Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The indexer seeds the journal registry from OpenAlex. It harvests new works daily from OpenAlex and Crossref, and resolves each work's text through content routes 1–4, ending with the abstract. It stays within the free OpenAlex allowance, so a week of unattended live harvesting fills the dev database.

**Architecture:**
- Everything external runs in the `indexer` as idempotent task handlers on the existing Postgres queue.
- Scheduled jobs (ShedLock) only enqueue tasks.
- A shared polite HTTP layer gives every host a rate limiter, the Garrulus User-Agent and retries. A usage meter records OpenAlex spend from its response headers and enforces the daily cap.
- Full text is converted to the finder `Document` with the finder-core converters. Raw and converted text go into a content-addressed blob store, but only for open licences.
- `web` gains admin endpoints that change tiers and enqueue tasks. It never calls external services.

**Tech Stack:** Spring Boot 4.1, JDK 25, MyBatis, Flyway, Postgres 17, `java.net.http.HttpClient`, Jackson 3 (`tools.jackson`), ShedLock, WireMock 3.13.2 and Testcontainers for tests.

**Spec:** `docs/design.md`, §1–3 (source registry, harvesters, content acquisition), §6 (`work`, `content`), §10 (orchestration), Workflows 1–3, the Roadmap row for M2, and Open items.

**Branching:** work directly on `main`. This is a local single-session repo, and Markus asked for no branches.

## Global Constraints

- **Packages and ids:**
  - Packages: `org.catalogueoflife.garrulus.store.*`, `org.catalogueoflife.garrulus.indexer.*`, `org.catalogueoflife.garrulus.web.*`.
  - Every outgoing request sends `User-Agent: Garrulus/<version> (+https://www.catalogueoflife.org)`.
  - `<version>` is the jar's Implementation-Version, or `dev` when there is none.
- **Who talks to whom:**
  - Only the `indexer` calls external services.
  - `web` writes only `source.tier` / `source.active` and inserts `task` rows.
  - The two apps never call each other.
- **Free by default:**
  - OpenAlex spend is capped per UTC day at `garrulus.openalex.max-daily-usd` (env `GARRULUS_OPENALEX_MAX_DAILY_USD`, default `1.00`) and never exceeds what the API reports as remaining (`X-RateLimit-Remaining`).
  - Prices: singleton get $0, list/filter or group-by call $0.0001, content download $0.01. They are used only when the response carries no `X-RateLimit-Credits-Used` header.
  - Content downloads (route 4) use only what is left above `garrulus.openalex.content.reserve-usd` (default `0.30`). They are switched by `garrulus.openalex.content.enabled` (env `GARRULUS_OPENALEX_CONTENT_ENABLED`, default `true`), apply only to CORE-tier sources, and never to `BACKFILL` works.
  - When the cap is hit, the work is deferred to the next UTC day. It never fails into `DEAD`.
- **Secrets and personal data:**
  - `OPENALEX_API_KEY` and `CROSSREF_MAILTO` come from the environment or a git-ignored `.env`. There are no defaults in the repo and no personal email anywhere.
  - Without `CROSSREF_MAILTO`, Crossref runs in the public pool.
- **Works:**
  - DOIs are stored lower-case, without `https://doi.org/`.
  - `work.first_seen_at` and `work.origin` are set on insert and never change.
  - A work is one row whether it arrives through OpenAlex, Crossref or both.
- **Tasks:**
  - Delivery is at-least-once, so every handler is idempotent.
  - Priorities:
    - `LIVE`: daily harvests, `LIVE`/`SWEEP` works.
    - `BACKFILL`: onboarding imports, `ONBOARDING` works.
  - Dedup keys are as named in each task.
- **Content routes,** in the design's order, stopping at the first success:
  1. `PENSOFT`
  2. `EUROPEPMC`
  3. `PMC`
  4. `OPENALEX_TEI`
  5. `ABSTRACT` (always written when the work has a title or an abstract)

  Local GROBID on publisher PDFs (design route 5) is **not** in M2. The roadmap names routes 1–4.
- **Retention:**
  - Blobs (raw and `Document` JSON) are kept only when the licence is open: `cc-by`, `cc-by-sa`, `cc0` or `public-domain`. The list is configurable as `garrulus.content.open-licences`.
  - Otherwise the `content` row keeps the hashes, URL and licence with `retained=false` and no blob; the text is re-fetched when names are found (M3).
- **Documents:**
  - Built only with the finder-core converters: `JatsConverter`, `TeiConverter.convert(tei, docId, isKnownWord)`, `AbstractConverter.fromParts`.
  - Serialised with `FinderJson.mapper()`.
  - `docId` = `"work:" + work.id`.
- **Schema changes** are new Flyway migrations (`V2__…` onwards). They are expand-only, so a running `web` keeps working.
- **Tests:**
  - Every external HTTP call is tested with WireMock fixtures under `src/test/resources/<service>/`. `mvn -B verify` makes no live calls.
  - Live smoke checks run only by hand.
- **Never touch local ports 8080/8081** (the user's own process). For manual runs, use `GARRULUS_INDEXER_PORT=18081` and `SERVER_PORT=18080`.
- **Commits:** conventional style, no tool attribution lines, files staged explicitly by path.

## Review Focus

1. **The OpenAlex budget runs out mid-harvest** (`429`, or `X-RateLimit-Remaining` ≤ cost). The harvest stops cleanly, its task re-queues itself for the next UTC day with its position kept, and nothing goes `DEAD`. Pinned in Task 3 (`budgetExhaustedDefersToNextUtcDay`) and Task 5 (`harvestResumesFromSavedCursorAfterDeferral`).
2. **The same article arrives twice.** Crossref sends DOI `10.3897/ZooKeys.1.2`, and OpenAlex later sends `https://doi.org/10.3897/zookeys.1.2` with an OpenAlex id. There must be one row; `openalex_id` gets filled in, and `origin`/`first_seen_at` stay unchanged. Pinned in Task 1 (`upsertMergesByDoiAndKeepsOriginAndFirstSeen`) and Task 6 (`crossrefOnlyWorkLaterFoundInOpenAlexIsMerged`).
3. **A newly added or promoted journal.** Its last 120 days arrive as `ONBOARDING` works, never as `LIVE`, even if the daily harvest runs first. Pinned in Task 5 (`liveHarvestSkipsSourcesNotYetOnboarded`).
4. **A full-text route returns HTML, a 404, a truncated XML file or XML with a DOCTYPE.** That route is skipped, the next one is tried, and the work still ends `CONTENT_RESOLVED` (at worst `ABSTRACT_ONLY` / `NO_TEXT`). Pinned in Task 8 (`brokenRouteFallsThrough`).
5. **The indexer is restarted mid-run.** The re-run tasks create no duplicate works, content rows or blobs. Pinned in Task 7 (`resolveTwiceIsIdempotent`) and Task 5 (`harvestTwiceCreatesNoDuplicates`).

---

## File Structure

**store** (`store/src/main/...nametracker/store/`):
- `db/migration/V2__harvest_and_content.sql`: new columns plus `api_usage` and `harvest_run`.
- `source/Source.java` (gains `onboardedAt`, `tierSetBy`), `source/SourceMapper.java` and `.xml`: upsert by OpenAlex id, listing by tier/active/onboarded.
- `work/Work.java` (gains `abstractJats`, `pmcid`, `openalexHasTei`), `work/WorkMapper.java` and `.xml`: `upsert`, `findByDoi`, `findByOpenalexId`, `setState`.
- `work/WorkUpsert.java`: merge rules, as a service class.
- `content/Content.java`, `content/ContentMapper.java` and `.xml`.
- `usage/ApiUsageMapper.java` and `.xml`.
- `harvest/HarvestRun.java`, `harvest/HarvestRunMapper.java` and `.xml`.

**indexer** (`indexer/src/main/java/.../nametracker/indexer/`):
- `http/PoliteHttp.java`, `http/HostLimiter.java`, `http/HttpProblem.java`
- `cost/OpenAlexBudget.java`, `cost/BudgetExhaustedException.java`
- `openalex/OpenAlexClient.java`, `openalex/OpenAlexWorks.java` (JSON → `WorkRecord`), `openalex/WorkRecord.java`
- `crossref/CrossrefClient.java`, `crossref/CrossrefWorks.java`
- `source/SourceSeeder.java`, `source/SeedSourcesHandler.java`, `source/OnboardSourceHandler.java`, `source/AddSourceHandler.java`
- `harvest/OpenAlexHarvestHandler.java`, `harvest/CrossrefHarvestHandler.java`, `harvest/OpenAlexRecheckHandler.java`, `harvest/HarvestScheduler.java`
- `content/BlobStore.java`, `content/FileBlobStore.java`, `content/ContentRoute.java`, `content/FetchedContent.java`, `content/LicencePolicy.java`, `content/ResolveContentHandler.java`
- `content/route/PensoftRoute.java`, `content/route/EuropePmcRoute.java`, `content/route/PmcBucketRoute.java`, `content/route/OpenAlexTeiRoute.java`, `content/route/AbstractRoute.java`
- `HarvestConfig.java`: beans and `@ConfigurationProperties`.

**web:** `web/src/main/java/.../web/api/AdminSourceController.java`, `AdminStatusController.java`.

**docs:** `CLAUDE.md` (commands and config), `docs/adr/0004-onboarding-and-harvest-windows.md`, and an update of the design's Open items.

---

### Task 1: Schema V2 and store mappers

**Files:**
- Create:
  - `store/src/main/resources/db/migration/V2__harvest_and_content.sql`
  - `store/.../work/WorkUpsert.java`
  - `store/.../content/{Content.java, ContentMapper.java}` and `.xml`
  - `store/.../usage/ApiUsageMapper.java` and `.xml`
  - `store/.../harvest/{HarvestRun.java, HarvestRunMapper.java}` and `.xml`
- Modify:
  - `Source.java`, `SourceMapper.java` and `.xml`
  - `Work.java`, `WorkMapper.java` and `.xml`
- Test:
  - `store/src/test/.../work/WorkUpsertIT.java`
  - `store/src/test/.../source/SourceMapperIT.java`
  - `store/src/test/.../content/ContentMapperIT.java`
  - `store/src/test/.../usage/ApiUsageMapperIT.java`

The ITs extend the existing Postgres IT base.

**V2 contents** (exact):

```sql
ALTER TABLE source ADD COLUMN onboarded_at timestamptz;
ALTER TABLE source ADD COLUMN tier_set_by text NOT NULL DEFAULT 'SEED' CHECK (tier_set_by IN ('SEED','ADMIN'));
ALTER TABLE work ADD COLUMN abstract_jats text;
ALTER TABLE work ADD COLUMN pmcid text;
ALTER TABLE work ADD COLUMN openalex_has_tei boolean NOT NULL DEFAULT false;
ALTER TABLE work ADD COLUMN openalex_checked_at timestamptz;
CREATE INDEX work_state_idx ON work (state);
CREATE TABLE api_usage (
  service text NOT NULL, day date NOT NULL,
  calls integer NOT NULL DEFAULT 0, usd numeric(10,4) NOT NULL DEFAULT 0,
  remaining_usd numeric(10,4),
  PRIMARY KEY (service, day));
CREATE TABLE harvest_run (
  id bigserial PRIMARY KEY, adapter text NOT NULL, window_from date, window_to date,
  started timestamptz NOT NULL DEFAULT now(), finished timestamptz,
  calls integer NOT NULL DEFAULT 0, works_seen integer NOT NULL DEFAULT 0, works_new integer NOT NULL DEFAULT 0,
  state text NOT NULL DEFAULT 'RUNNING' CHECK (state IN ('RUNNING','DONE','DEFERRED','FAILED')), error text);
```

**Interfaces:**
- **Consumes:** existing `Source`, `Work`, `SourceMapper`, `WorkMapper`, the `StringArrayTypeHandler` and `JsonbStringTypeHandler` type handlers, and the Postgres IT base in `store/src/test`.
- **Produces:**
  - `Work`: the existing record plus `String abstractJats, String pmcid, Boolean openalexHasTei, Instant openalexCheckedAt`, appended in that order.
  - `Source`: plus `Instant onboardedAt, String tierSetBy`, appended.
  - `SourceMapper`:
    - `Source findByOpenalexId(String id)`
    - `List<Source> listHarvestable()`: active, tier CORE or BROAD, onboarded_at not null, ordered by id
    - `List<Source> listToOnboard()`: active, CORE/BROAD, onboarded_at null
    - `int setOnboarded(long id, Instant at)`
    - `int setLastHarvested(long id, Instant at)`
    - `int setTier(long id, String tier, boolean active, String tierSetBy)`
    - `long upsertSeed(Source s)`: inserts, or on an `openalex_id` conflict refreshes only the metadata columns (issn_l, issns, title, publisher, host, is_oa, in_doaj, subfields, primary_subfield, oa_model, country, works_per_year). It never touches tier, active, origin, tier_set_by or onboarded_at.
  - `WorkMapper`:
    - `Work findByDoi(String doi)`
    - `Work findByOpenalexId(String id)`
    - `int setState(long id, String state, String textLevel)`
    - `List<Work> listCrossrefOnly(int limit)`: openalex_id null, `openalex_checked_at` null or older than 7 days
  - `WorkUpsert(WorkMapper)`, `UpsertResult upsert(Work incoming)`, with `record UpsertResult(long id, boolean created)`. The merge rule:
    1. Normalise the DOI: lower-case; strip `https://doi.org/`, `http://dx.doi.org/` and `doi:`.
    2. Find by openalex_id, else by doi.
    3. Insert when there is none.
    4. Otherwise fill every column that is null in the stored row from the incoming one, and overwrite the bibliographic columns (title, dates, volume, issue, pages, authors, oa_status, license, best_oa_url, openalex_has_tei) when the incoming value is non-null. `origin`, `first_seen_at`, `state` and `text_level` are never changed. `abstract_jats` is overwritten only by a non-null value.
    5. Concurrency: `INSERT … ON CONFLICT DO NOTHING`. When no row came back, re-read and merge.
  - `ContentMapper`:
    - `long upsert(Content c)` on `(work_id, route)`, replacing the row
    - `List<Content> listByWork(long workId)`
  - `Content` record: id, workId, route, kind, license, retained, rawSha256, docSha256, sourceUrl, fetchedAt.
  - `ApiUsageMapper`:
    - `void add(String service, LocalDate day, int calls, BigDecimal usd, @Nullable BigDecimal remainingUsd)`: an upsert that increments calls and usd; `remaining_usd` is the last value seen.
    - `Map<String,Object> get(String service, LocalDate day)`
  - `HarvestRunMapper`:
    - `long start(String adapter, LocalDate from, LocalDate to)`
    - `int finish(long id, String state, int calls, int seen, int created, @Nullable String error)`
    - `List<HarvestRun> recent(int limit)`

- [ ] **Step 1: Write the failing ITs:**
  - `WorkUpsertIT.upsertMergesByDoiAndKeepsOriginAndFirstSeen`:
    - insert via upsert `doi="10.3897/ZooKeys.1.2"`, origin `LIVE`, no openalex id;
    - upsert again with `doi="https://doi.org/10.3897/zookeys.1.2"`, `openalexId="W1"`, origin `ONBOARDING`, and a title;
    - assert one row, doi `10.3897/zookeys.1.2`, openalex_id `W1`, origin `LIVE`, `first_seen_at` unchanged, `created` false on the second call.
  - `WorkUpsertIT.concurrentUpsertsMakeOneRow`: 8 threads upsert the same DOI; assert exactly one row.
  - `SourceMapperIT.upsertSeedNeverChangesAdminTier`:
    - insert a source; then `setTier(id,"EXCLUDED",false,"ADMIN")`;
    - `upsertSeed` the same openalex id with tier CORE and a new title;
    - assert tier EXCLUDED, active false, title updated.
  - `SourceMapperIT.listHarvestableNeedsOnboarding`: two CORE sources, one onboarded; `listHarvestable` returns 1 and `listToOnboard` returns 1.
  - `ContentMapperIT.upsertReplacesSameRoute`.
  - `ApiUsageMapperIT.addAccumulatesPerDay`: two adds on the same day sum; a different day is separate.
- [ ] **Step 2:** Run `mvn -B -pl store -am verify -Dit.test='WorkUpsertIT,SourceMapperIT,ContentMapperIT,ApiUsageMapperIT'`. Expected: FAIL (columns, methods missing).
- [ ] **Step 3:** Write the migration, records, mappers and `WorkUpsert`. Check that the existing ITs still pass, because the record constructors grew.
- [ ] **Step 4:** Run `mvn -B -pl store,indexer,web -am verify`. Expected: PASS. `web`'s `SchemaCheckIT` validates V2.
- [ ] **Step 5:** Commit `feat(store): schema V2 for harvest and content, work upsert with merge rules`.

---

### Task 2: Polite HTTP layer

**Files:**
- Create: `indexer/.../http/PoliteHttp.java`, `HostLimiter.java`, `HttpProblem.java`
- Test: `indexer/src/test/.../http/PoliteHttpTest.java` (WireMock)

**Interfaces:**
- **Produces:**
  - `PoliteHttp(HttpClient client, Map<String, Duration> minIntervalPerHost, String userAgent, Clock clock, Sleeper sleeper)`, with `interface Sleeper { void sleep(Duration d) throws InterruptedException; }` so tests can avoid real waits.
  - `HttpResponse<byte[]> get(URI uri, Map<String,String> headers)`:
    - waits for the host's limiter, then sends with the User-Agent;
    - on `429` or `503`, honours `Retry-After` (seconds; capped at 120 s, default 10 s) and retries up to 3 times;
    - on another `5xx` or an `IOException`, retries twice after 2 s and 8 s;
    - returns any other status to the caller untouched;
    - throws `HttpProblem` (unchecked; holds uri, status, and whether it is retryable) once the retries are spent.
  - `static String userAgent()` returns `Garrulus/<version> (+https://www.catalogueoflife.org)`.
  - `HostLimiter`: one per host. `acquire()` blocks until `minInterval` has passed since the last start. It is thread-safe and shared by all worker threads.
  - **Default intervals** (`garrulus.http.min-interval.<host>`):

    | Host | Interval |
    |---|---|
    | `api.openalex.org` | 100 ms |
    | `content.openalex.org` | 1 s |
    | `api.crossref.org` | 250 ms |
    | `www.ebi.ac.uk` | 200 ms |
    | `pmc-oa-opendata.s3.amazonaws.com` | 100 ms |
    | `*.pensoft.net` | 1 s, one limiter for all Pensoft hosts |
    | anything else | 1 s |

- [ ] **Step 1: Write the failing tests:**
  - `sendsGarrulusUserAgent`: WireMock verifies the header matches `Garrulus/(dev|[0-9].*) \(\+https://www.catalogueoflife.org\)`.
  - `retriesAfter429HonouringRetryAfter`: the stub returns 429 with `Retry-After: 7`, then 200; assert 200 and that the sleeper was asked for 7 s.
  - `givesUpAfterThreeRetries`: four 503s → `HttpProblem` with status 503, retryable.
  - `notFoundIsReturnedNotThrown`: a 404 is returned.
  - `limiterSpacesRequestsPerHost`: with a fake clock, two acquires on the same host 1 s apart; different hosts do not wait.
- [ ] **Step 2:** Run `mvn -B -pl indexer -am test -Dtest=PoliteHttpTest`. Expected: FAIL.
- [ ] **Step 3:** Implement it. Read the version from `PoliteHttp.class.getPackage().getImplementationVersion()`, else `dev`. Add `addDefaultImplementationEntries` to the indexer jar manifest if it is missing.
- [ ] **Step 4:** Run it again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): polite HTTP with per-host limits, retries and the Garrulus user agent`.

---

### Task 3: OpenAlex client and budget

**Files:**
- Create:
  - `indexer/.../cost/OpenAlexBudget.java`, `BudgetExhaustedException.java`
  - `indexer/.../openalex/OpenAlexClient.java`, `OpenAlexWorks.java`, `WorkRecord.java`
- Test:
  - `indexer/src/test/.../cost/OpenAlexBudgetTest.java`
  - `indexer/src/test/.../openalex/OpenAlexClientTest.java` (WireMock)
  - `OpenAlexWorksTest.java`
- Fixtures: `indexer/src/test/resources/openalex/` holds real responses saved once by hand with curl (free singleton gets, plus one list page), trimmed to what the tests need:
  - `work-W*.json` for a ZooKeys article (has a PMCID);
  - a Crossref-only-style DOI miss (`404.json`);
  - `list-page.json` (2 results, with `meta.next_cursor`);
  - `group-by.json`;
  - `source-S199213172.json` (ZooKeys).

**Interfaces:**
- **Consumes:** `PoliteHttp` (Task 2), `ApiUsageMapper` (Task 1).
- **Produces:**
  - `OpenAlexBudget(ApiUsageMapper usage, BigDecimal maxDailyUsd, BigDecimal contentReserveUsd, Clock clock)`:
    - `void check(Call kind)` throws `BudgetExhaustedException(Instant retryAt)`, where `retryAt` is the next 00:05 UTC. It throws when today's spend + the kind's price exceeds `maxDailyUsd`, or when the last seen `remaining_usd` is below the price. For `CONTENT`, it also throws when the remaining amount would drop below `contentReserveUsd`.
    - `void record(Call kind, @Nullable BigDecimal creditsUsed, @Nullable BigDecimal remaining)`: uses the header values when present, else the price table.
    - `enum Call { SINGLETON, LIST, GROUP_BY, CONTENT }`, with prices 0, 0.0001, 0.0001 and 0.01.
  - `OpenAlexClient(PoliteHttp http, OpenAlexBudget budget, @Nullable String apiKey, String baseUrl)` (base `https://api.openalex.org`):
    - `Optional<JsonNode> work(String idOrDoi)` for `W…` or `doi:10…`. A 404 gives empty.
    - `Optional<JsonNode> source(String id)`.
    - `Page listWorks(String filter, String select, @Nullable String cursor, int page)` with `per_page=200`. It uses cursor paging when an API key is set (`cursor=*` first), else page paging with `sort=publication_date,id` and a stop at 10,000 results. `record Page(List<JsonNode> results, @Nullable String nextCursor, int nextPage, boolean last)`.
    - `List<GroupCount> groupBy(String filter, String groupBy)` follows all pages with a key and only the first page without one, logging a WARN that results are truncated. `record GroupCount(String key, long count)`.
    - It sends `api_key` as a query parameter when set. Every call goes through `budget.check` and then `budget.record`, with the values of `X-RateLimit-Credits-Used` and `X-RateLimit-Remaining`.
    - A `429` whose body or headers say the daily budget is spent (remaining 0) throws `BudgetExhaustedException`; other 429s are retried by `PoliteHttp`.
  - `WorkRecord`: the fields of `Work` that harvest fills (openalexId without the `https://openalex.org/` prefix, doi, title, publicationDate, year, volume, issue, pages, authors JSON `[{"name":…,"orcid":…}]`, type, language, oaStatus, license, bestOaUrl, abstractText from `AbstractConverter.fromInvertedIndex`, pmcid, openalexHasTei from `has_content.grobid_xml`, sourceOpenalexId). Plus `Work toWork(Long sourceId, String origin)`.
  - `OpenAlexWorks.parse(JsonNode) → WorkRecord`. Licence: `best_oa_location.license`, else `primary_location.license`, normalised to lower-case (`cc-by`, …). PMCID: `ids.pmcid`, as its trailing `PMC…` segment.
  - `OpenAlexWorks.SELECT`: `id,doi,title,publication_date,biblio,authorships,type,language,open_access,best_oa_location,primary_location,abstract_inverted_index,ids,has_content`.

- [ ] **Step 1: Write the failing tests:**
  - `OpenAlexBudgetTest.budgetExhaustedDefersToNextUtcDay`: max 0.0002 and two LIST calls recorded; the next `check(LIST)` throws, and `retryAt` = tomorrow 00:05 UTC.
  - `contentLeavesTheReserve`: remaining 0.305, reserve 0.30, so `check(CONTENT)` throws while `check(LIST)` passes.
  - `headerCostsWinOverPriceTable`.
  - `OpenAlexClientTest.cursorPagingWithKey` and `pagePagingWithoutKey` (asserts the query parameters).
  - `singletonMissIsEmpty`.
  - `dailyBudget429Throws`.
  - `recordsHeaderCosts`.
  - `OpenAlexWorksTest.parsesZookeysWork`: DOI lower-case without prefix, licence `cc-by`, PMCID, abstract text starting with the fixture's first words, `openalexHasTei` as in the fixture.
- [ ] **Step 2:** Run `mvn -B -pl indexer -am test -Dtest='OpenAlexBudgetTest,OpenAlexClientTest,OpenAlexWorksTest'`. Expected: FAIL.
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Run it again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): OpenAlex client with a daily budget from its cost headers`.

---

### Task 4: Source seeding and admin-added sources

**Files:**
- Create: `indexer/.../source/SourceSeeder.java`, `SeedSourcesHandler.java`, `AddSourceHandler.java`, `indexer/.../HarvestConfig.java`
- Test: `indexer/src/test/.../source/SourceSeederTest.java` (WireMock plus a fake mapper or the Postgres IT base), `SeedSourcesHandlerIT.java`

**Seeding rules** (design §1; thresholds are configurable under `garrulus.seed.*`, defaults as given):
- **Window:** `from_publication_date` = today − 3 years, plus `type:article`, `primary_location.source.type:journal`.
- **Taxonomy counts:** `groupBy(filter + ",primary_topic.id:T14399|T11494|T12132|T12606|T11974|T12447|T13269|T12361|T12568|T14049|T11836|T11528|T12668|T11879|T11354|T10332|T12319", "primary_location.source.id")`.
- **Broad counts:** the same with `primary_topic.subfield.id:1105|1109|1103|1104|1110|1911|2404|2405|2303`.
- **Totals:** for every source in either result, in batches of 50 ids: `groupBy(window + ",primary_location.source.id:S1|…", "primary_location.source.id")`.
- **Tier:**
  - `CORE` if taxonomy ≥ `core-min-works` (20) and taxonomy/total ≥ `core-min-share` (0.30);
  - else `BROAD` if broad ≥ `broad-min-works` (30) and broad/total ≥ `broad-min-share` (0.50);
  - else `CANDIDATE` if broad ≥ 10;
  - else skipped.
- **Metadata** from `client.source(id)`:
  - `issn_l`, `issn`, `display_name`, `host_organization_name`;
  - host of `homepage_url`;
  - `is_oa`, `is_in_doaj`, `country_code`;
  - `subfields`: the harvested-vocabulary subfield ids among `topics[].subfield.id`, weighted by count, each ≥ 5% of the source's topic count, as bare ids like `"1105"`; `primary_subfield` is the largest;
  - `oa_model`: `DIAMOND` if in DOAJ and `apc_usd` null or 0; `GOLD` if `is_oa` and `apc_usd` > 0; otherwise null;
  - `journal_type`: `TAXONOMIC` for CORE, else `GENERAL`;
  - `works_per_year` = total / 3.
- **Upsert** with `SourceMapper.upsertSeed`, origin `SEED_TOPIC`, `tier_set_by` `SEED`.
- **Onboarding:** every active CORE/BROAD source still to onboard gets an `ONBOARD_SOURCE` task (BACKFILL priority, dedup `onboard:<sourceId>`).
- **`ADD_SOURCE` task** (payload `{"openalexId":"S…","tier":"CORE|BROAD|CANDIDATE"}`): fetches the source, upserts it with origin `MANUAL`, sets the tier with `tier_set_by` `ADMIN`, and enqueues onboarding when CORE or BROAD.

**Interfaces:**
- **Consumes:** `OpenAlexClient`, `SourceMapper`, `TaskQueue`, `TaskHandler`.
- **Produces:**
  - task types `SEED_SOURCES` (payload `{}`, dedup `seed-sources`) and `ADD_SOURCE`;
  - `SourceSeeder.seed() → SeedReport(int core, int broad, int candidate, int skipped, boolean truncated)`;
  - `ONBOARD_SOURCE` tasks with payload `{"sourceId":n}` (handled in Task 5).

- [ ] **Step 1: Write the failing tests:**
  - `SourceSeederTest.tiersFromShares`: three fixture sources:
    - taxonomy 50 / total 100 → CORE;
    - broad 80 / total 100 → BROAD;
    - broad 12 / total 100 → CANDIDATE.
  - `oaModelAndSubfields` (DIAMOND, subfield ≥ 5% kept, the smaller dropped).
  - `SeedSourcesHandlerIT.seedTwiceKeepsAdminTierAndEnqueuesOnboardingOnce`.
  - `addSourceSetsAdminTierAndOnboards`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement it. `HarvestConfig` binds `garrulus.openalex.*`, `garrulus.seed.*`, `garrulus.http.*`, `garrulus.content.*` and `garrulus.crossref.*`, with env overrides in `indexer/src/main/resources/application.yml`. It also creates `PoliteHttp`, `OpenAlexBudget` and `OpenAlexClient`.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): seed the journal registry from OpenAlex topics and subfields`.

---

### Task 5: OpenAlex harvest: daily window, weekly sweep, onboarding

**Files:**
- Create: `indexer/.../harvest/OpenAlexHarvestHandler.java`, `indexer/.../source/OnboardSourceHandler.java`
- Test: `indexer/src/test/.../harvest/OpenAlexHarvestHandlerIT.java` (WireMock plus Testcontainers)

**Rules:**
- **`HARVEST_OPENALEX`**:
  - Payload: `{"window":"LIVE"|"SWEEP","from":"YYYY-MM-DD","batch":i,"cursor":…,"page":n}`.
  - `LIVE` uses `from` = today − 120 days; `SWEEP` uses today − 400 days.
  - Covers `SourceMapper.listHarvestable()` in batches of 50 source ids. The filter is `primary_location.source.id:S…|S…,from_publication_date:<from>,type:article|review`, with `select=OpenAlexWorks.SELECT`.
  - Each result is upserted with origin = the window name (`LIVE` or `SWEEP`). New works get a `RESOLVE_CONTENT` task: LIVE priority, dedup `content:<workId>`, payload `{"workId":n}`.
  - After each page it saves its position (batch, cursor/page) by **enqueueing its own continuation**: the same payload with the next position, dedup `harvest:openalex:<window>:<from>:<batch>:<pos>`. The current task then completes, so a crash re-runs at most one page.
  - On `BudgetExhaustedException`, it enqueues the continuation with `run_after` = `retryAt` and completes. It never throws.
  - At the end of each batch it sets `last_harvested` on that batch's sources.
  - It writes one `harvest_run` row per window and `from`. The first page starts the row and carries `"runId"` in every continuation payload. Each page adds its calls and counts, the last page finishes the row as `DONE`, and a budget deferral marks it `DEFERRED` until a continuation completes it.
- **`ONBOARD_SOURCE`** (`{"sourceId":n}`):
  - Same paging, for that one source, `from` = today − 120 days, origin `ONBOARDING`.
  - `RESOLVE_CONTENT` goes in at BACKFILL priority.
  - After the last page it calls `setOnboarded(id, now)`.
  - While a source is not yet onboarded, `LIVE` and `SWEEP` skip it. An `ONBOARDING` work that is later also listed by LIVE keeps origin `ONBOARDING` (upsert rule).

**Interfaces:**
- **Consumes:** `OpenAlexClient.listWorks`, `OpenAlexWorks.parse`, `WorkUpsert`, `SourceMapper`, `TaskQueue`, `HarvestRunMapper`.
- **Produces:** task types `HARVEST_OPENALEX` and `ONBOARD_SOURCE`; `RESOLVE_CONTENT` tasks (handled in Task 7).

- [ ] **Step 1: Write the failing ITs:**
  - `harvestTwiceCreatesNoDuplicates`: the same 2-page stub run twice gives the same row count; `RESOLVE_CONTENT` is enqueued once per work.
  - `harvestResumesFromSavedCursorAfterDeferral`: the budget throws on page 2, so page 2's continuation task exists with `run_after` tomorrow; run it with the budget reset and assert all works are present.
  - `liveHarvestSkipsSourcesNotYetOnboarded`: a source not yet onboarded gets no LIVE request (WireMock verify), and after ONBOARD_SOURCE its works have origin `ONBOARDING`.
  - `onboardingWorksGetBackfillPriority`.
- [ ] **Step 2:** Run `mvn -B -pl indexer -am verify -Dit.test=OpenAlexHarvestHandlerIT`. Expected: FAIL.
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Run it again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): OpenAlex daily window, weekly sweep and onboarding imports`.

---

### Task 6: Crossref change detector and OpenAlex re-check

**Files:**
- Create: `indexer/.../crossref/CrossrefClient.java`, `CrossrefWorks.java`, `indexer/.../harvest/CrossrefHarvestHandler.java`, `OpenAlexRecheckHandler.java`
- Test: `indexer/src/test/.../crossref/CrossrefClientTest.java`, `CrossrefWorksTest.java`, `indexer/src/test/.../harvest/CrossrefHarvestHandlerIT.java`
- Fixtures: `indexer/src/test/resources/crossref/`: a real `/works` page with a `jats:` abstract, saved once by hand, plus an empty page.

**Rules:**
- **Request:** `GET https://api.crossref.org/works?filter=from-index-date:<d>,until-index-date:<d>,type:journal-article,issn:<i1>,issn:<i2>…&rows=1000&cursor=<c>&select=DOI,ISSN,title,abstract,published,container-title,volume,issue,page,author,license,type`, plus `&mailto=<CROSSREF_MAILTO>` only when it is set.
  - `<d>` = yesterday (UTC). ISSNs come from all harvestable sources (`issn_l` plus `issns`), 40 per request.
  - Crossref ORs repeated filters of the same name. This is a design open item: confirm it once by hand with two ISSNs, and record the result in the ADR (Task 10).
- **Per DOI:**
  - **Known work:** set `abstract_jats` when the work has none. Crossref JATS keeps italics and is preferred over the inverted index (design §2).
  - **Unknown DOI:** `OpenAlexClient.work("doi:"+doi)` (free).
    - Found: upsert from OpenAlex with origin `LIVE`, add the Crossref `abstract_jats`, enqueue `RESOLVE_CONTENT`.
    - Not found: upsert from Crossref metadata (no `openalex_id`, origin `LIVE`, `source_id` from the matching ISSN), enqueue `RESOLVE_CONTENT`.
- **Position:** stored by continuation tasks, as in Task 5 (dedup `harvest:crossref:<d>:<batch>:<cursor-hash>`).
- **`OPENALEX_RECHECK`** (`{}`, dedup `openalex-recheck`, daily):
  - Runs over `WorkMapper.listCrossrefOnly(500)`; each found work is merged (upsert) and sets `openalex_checked_at`.
  - It never changes origin or `first_seen_at`.

**Interfaces:**
- **Produces:**
  - `CrossrefClient(PoliteHttp, @Nullable String mailto, String baseUrl)`, `Page works(String filter, String cursor)`
  - `CrossrefWorks.parse(JsonNode) → WorkRecord`: `abstractJats` = the raw `abstract` string; the date comes from `published.date-parts`.
  - task types `HARVEST_CROSSREF` (`{"date":"YYYY-MM-DD"}`) and `OPENALEX_RECHECK`.

- [ ] **Step 1: Write the failing tests:**
  - `CrossrefClientTest.sendsMailtoOnlyWhenSet`.
  - `issnFiltersRepeated`.
  - `CrossrefWorksTest.keepsJatsAbstract`.
  - `CrossrefHarvestHandlerIT.knownWorkGetsJatsAbstract`.
  - `unknownDoiFoundInOpenAlexIsUpsertedWithOpenAlexId`.
  - `crossrefOnlyWorkLaterFoundInOpenAlexIsMerged`: created without an openalex id; RECHECK with an OpenAlex stub fills it in; origin and `first_seen_at` are unchanged.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Run them again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): Crossref index-date change detector with OpenAlex re-checks`.

---

### Task 7: Blob store, route SPI, licence policy and RESOLVE_CONTENT with the abstract route

**Files:**
- Create: `indexer/.../content/{BlobStore, FileBlobStore, ContentRoute, FetchedContent, LicencePolicy, ResolveContentHandler}.java`, `indexer/.../content/route/AbstractRoute.java`
- Test: `FileBlobStoreTest.java`, `LicencePolicyTest.java`, `ResolveContentHandlerIT.java`

**Interfaces:**
- **Produces:**
  - `interface BlobStore`:
    - `String put(byte[] bytes)` returns the lower-case hex SHA-256 of the uncompressed bytes;
    - `Optional<byte[]> get(String sha256)`;
    - `boolean exists(String sha256)`.
  - `FileBlobStore(Path root)` writes `root/<sha[0:2]>/<sha[2:4]>/<sha>.gz` gzipped and atomically (temp file, then move). Writing an existing hash does nothing. Root: `garrulus.blobs.dir` (env `GARRULUS_BLOBS_DIR`, default `./data/blobs`; `data/` is git-ignored).
  - `interface ContentRoute`:
    - `String route()`: a `content.route` value;
    - `boolean applies(Work w, Source s)`;
    - `Optional<FetchedContent> fetch(Work w, Source s)`: empty when the route has nothing; throws only for retryable problems.
  - `record FetchedContent(String route, String kind, byte[] raw, Document document, @Nullable String license, String sourceUrl, boolean fulltext)`.
  - `LicencePolicy(Set<String> open)`:
    - `boolean isOpen(@Nullable String licence)`;
    - `static String normalise(String)`, which maps forms like `http://creativecommons.org/licenses/by/4.0/`, `CC BY 4.0` and `cc-by-4.0` to `cc-by`, and `…/by-nc/…` to `cc-by-nc`.
  - **`RESOLVE_CONTENT`** (`{"workId":n}`):
    1. Load the work and its source.
    2. Try the full-text routes in order (`List<ContentRoute>` beans ordered by `@Order`: Pensoft 1, Europe PMC 2, PMC 3, OpenAlex TEI 4). The first non-empty fulltext result wins.
    3. Always run `AbstractRoute` as well, when the title or abstract is present.
    4. For each result, upsert a `content` row:
       - `raw_sha256`, `doc_sha256`, `source_url`, `license`, `retained = policy.isOpen(license)`;
       - blobs are written only when retained.
    5. Set the state with `setState(id, 'CONTENT_RESOLVED', text_level)`: FULLTEXT, ABSTRACT_ONLY or NO_TEXT.
    6. **Re-check:** if the result is ABSTRACT_ONLY, the work's source is CORE or BROAD, and `first_seen_at` is less than 90 days ago, enqueue the same task again with `run_after` = `first_seen_at` + 7, 30 or 90 days (the next step not yet passed; payload `"recheck":k`).
    7. **Errors:** a `RuntimeException` from one route is logged at WARN and the next route is tried. A `BudgetExhaustedException` in route 4 skips route 4.
  - `AbstractRoute`: route `ABSTRACT`, kind `ABSTRACT`, document = `AbstractConverter.fromParts(docId, title, abstract, abstractJats)`, licence = `work.license`, `fulltext=false`.

- [ ] **Step 1: Write the failing tests:**
  - `FileBlobStoreTest.putIsContentAddressedAndIdempotent`.
  - `LicencePolicyTest.normalisesCommonForms`: table-driven, including `by-nc-nd` → not open.
  - `ResolveContentHandlerIT.abstractOnlyWork`: state `CONTENT_RESOLVED`, `ABSTRACT_ONLY`, one ABSTRACT row; blobs present for `cc-by`, absent for null.
  - `resolveTwiceIsIdempotent`: same rows, same blob count.
  - `recheckScheduledForCoreAbstractOnly`.
  - `noTitleNoAbstractIsNoText`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement it. Add `data/` to `.gitignore` if it is missing.
- [ ] **Step 4:** Run them again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): content resolution with a blob store, licence retention and the abstract route`.

---

### Task 8: Free full-text routes: Pensoft, Europe PMC, PMC bucket

**Files:**
- Create: `indexer/.../content/route/{PensoftRoute, EuropePmcRoute, PmcBucketRoute}.java`
- Test: `PensoftRouteTest.java`, `EuropePmcRouteTest.java`, `PmcBucketRouteTest.java`, plus `ResolveContentHandlerIT.brokenRouteFallsThrough` and `fullTextWinsOverAbstract`.
- Fixtures: `indexer/src/test/resources/{pensoft,europepmc,pmc}/`. Take small real XML when it is CC-BY, or cut-down copies of the finder-core test fixtures. Add a bucket listing, a metadata JSON and an HTML error page.

**Rules:**
- **PENSOFT** applies when the DOI starts with `10.3897/`.
  - URL: `https://<slug>.pensoft.net/article/<id>/download/xml/`. `slug` = the DOI segment after `10.3897/` up to the first `.`, lower-cased. `id` = the last DOI segment with a leading `e`/`E` removed.
  - Convert with `new JatsConverter().convert(in, docId, JatsOptions.defaults())`, keeping taxon markup.
  - Licence: the XML's `<license>` `xlink:href` or `license-type`, normalised; else `cc-by`. Kind `TAXPUB`.
- **EUROPEPMC** applies when the work has a DOI or a PMCID.
  - PMCID: `work.pmcid`, else `GET https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=DOI:"<doi>"&format=json&resultType=lite`, using the first result's `pmcid` when `isOpenAccess` is `Y`. A PMCID found this way is stored on the work.
  - Text: `GET …/rest/<PMCID>/fullTextXML`. A 404 or an empty body gives empty.
  - Kind `JATS`. Licence from the XML's `<license>`.
- **PMC** applies when a PMCID is known.
  - List `https://pmc-oa-opendata.s3.amazonaws.com/?list-type=2&prefix=<PMCID>.&delimiter=/` and take the highest version `<PMCID>.<v>`.
  - Fetch `<PMCID>.<v>/<PMCID>.<v>.xml`. Licence from the version's metadata JSON; check the bucket's current layout by listing it once by hand, and pin it in the fixture.
  - Kind `JATS`.
- **All three:**
  - A 200 is used only when it parses as XML whose root is `article`. HTML, a truncated file or a DOCTYPE (the converter rejects it) gives empty, logged at INFO with the URL.
  - `fulltext=true` only if the document has at least one PARAGRAPH block.

**Interfaces:**
- **Consumes:** `ContentRoute`, `PoliteHttp`, `JatsConverter`, `LicencePolicy.normalise`.

- [ ] **Step 1: Write the failing tests:**
  - `PensoftRouteTest.urlFromDoi`: `10.3897/zookeys.1297.218447` → `https://zookeys.pensoft.net/article/218447/download/xml/`, and `10.3897/BDJ.14.e202849` → `https://bdj.pensoft.net/article/202849/download/xml/`.
  - `PensoftRouteTest.convertsTaxPub`.
  - `EuropePmcRouteTest.findsPmcidByDoiAndStoresIt`.
  - `notOpenAccessIsEmpty`.
  - `PmcBucketRouteTest.picksLatestVersion`.
  - `ResolveContentHandlerIT.brokenRouteFallsThrough`: Pensoft returns HTML, Europe PMC returns a 404, PMC succeeds → one PMC row plus ABSTRACT, FULLTEXT.
  - `fullTextWinsOverAbstract`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Run them again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): Pensoft, Europe PMC and PMC bucket full-text routes`.

---

### Task 9: OpenAlex TEI route (route 4) and the TEI converter path

**Files:**
- Create: `indexer/.../content/route/OpenAlexTeiRoute.java`
- Modify: `HarvestConfig.java` (optional dictionary)
- Test: `OpenAlexTeiRouteTest.java`
- Fixture: `indexer/src/test/resources/openalex/tei-small.xml`. Reuse the eval GROBID fixture text (CC0) or a hand-written TEI.

**Rules:**
- **Applies** only when all of these hold:
  - `garrulus.openalex.content.enabled`;
  - the source tier is `CORE`;
  - `work.origin` is not `BACKFILL`;
  - `work.openalexHasTei`;
  - the work has an `openalex_id`.
- **Fetch:**
  - `budget.check(CONTENT)` first; a `BudgetExhaustedException` propagates, and the handler skips the route.
  - `GET https://content.openalex.org/works/<W>.grobid-xml?api_key=…`.
  - Record the cost from the headers, or 0.01.
- **Convert** with `TeiConverter.convert(in, docId, isKnownWord)`.
  - `isKnownWord` = `ColDictionary::isKnownWord` when `FINDER_DICTIONARY` names a readable dictionary file, loaded once and lazily.
  - Otherwise `w -> false`, with one WARN at startup that de-hyphenation is off.
- **Licence** = `work.license`. Kind `TEI`, route `OPENALEX_TEI`.

- [ ] **Step 1: Write the failing tests:**
  - `OpenAlexTeiRouteTest.disabledSwitchNeverCalls` (WireMock verify: 0 requests).
  - `broadTierNeverCalls`.
  - `backfillNeverCalls`.
  - `reserveProtectsHarvest`: the budget throws, so there is no request.
  - `convertsTei`: TITLE and PARAGRAPH blocks, cost recorded.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Run them again. Expected: PASS.
- [ ] **Step 5:** Commit `feat(indexer): OpenAlex TEI route from the leftover free allowance, CORE tier only`.

---

### Task 10: Scheduling, admin endpoints, docs

**Files:**
- Create:
  - `indexer/.../harvest/HarvestScheduler.java`
  - `web/.../api/AdminSourceController.java`, `AdminStatusController.java`
  - `docs/adr/0004-onboarding-and-harvest-windows.md`
- Modify:
  - `indexer/src/main/resources/application.yml`
  - `CLAUDE.md`, `.env.example`
  - `docs/design.md` (Open items: record what was verified)
- Test: `HarvestSchedulerTest.java`, `web/src/test/.../api/AdminSourceControllerIT.java`, `AdminStatusControllerIT.java`

**Rules:**
- **`HarvestScheduler`** (`@Scheduled` plus `@SchedulerLock`) only enqueues tasks, with dedup keys that include the date:

  | Task | Default cron (UTC) | Env override | Dedup key |
  |---|---|---|---|
  | `HARVEST_OPENALEX` LIVE | `0 0 2 * * *` | `GARRULUS_HARVEST_OPENALEX_CRON` | `harvest:openalex:LIVE:<date>` |
  | `HARVEST_OPENALEX` SWEEP | `0 0 4 * * SUN` | `GARRULUS_HARVEST_SWEEP_CRON` | (with the date) |
  | `HARVEST_CROSSREF` | `0 0 3 * * *` | `GARRULUS_HARVEST_CROSSREF_CRON` | (with the date) |
  | `OPENALEX_RECHECK` | `0 30 3 * * *` | (none) | (with the date) |
  | `SEED_SOURCES` | `0 0 1 1 * *` (monthly) | (none) | `seed-sources:<yyyy-MM>` |

  - Master switch: `garrulus.harvest.enabled` (env `GARRULUS_HARVEST_ENABLED`, **default `false`**). Off means the scheduler enqueues nothing; handlers still run for tasks enqueued by hand or by the admin.
  - Register all new task types with the dispatcher.
- **web** (behind the existing admin-token condition and header check):
  - `GET /api/admin/sources?tier=&active=&q=` lists sources (id, openalexId, title, tier, active, onboardedAt, lastHarvested, worksPerYear), paged with `limit` ≤ 500 and `offset`.
  - `PATCH /api/admin/sources/{id}` with `{"tier":…, "active":…}`:
    - sets the tier with `tier_set_by='ADMIN'`;
    - when the new tier is CORE/BROAD, the source is active and not yet onboarded, inserts an `ONBOARD_SOURCE` task (BACKFILL priority, dedup `onboard:<id>`);
    - an unknown id gives 404; a bad tier gives 400.
  - `POST /api/admin/sources` with `{"openalexId":"S…","tier":…}` enqueues `ADD_SOURCE` and answers 202.
  - `GET /api/admin/status` returns:
    - task counts by type and state;
    - work counts by state and text_level;
    - today's `api_usage` rows;
    - the last 20 `harvest_run` rows;
    - source counts by tier.

  Add the read methods each endpoint needs to the store mappers.
- **ADR-0004** records:
  - the onboarding gate (no LIVE before onboarding);
  - continuation tasks as harvest checkpoints;
  - the budget source of truth (`X-RateLimit-*` headers);
  - whether Crossref ORs repeated `issn` filters, as observed;
  - keyless OpenAlex limits (one group-by page, page paging capped at 10,000, a 10× smaller budget);
  - why local GROBID is not in M2.
- **CLAUDE.md** gets:
  - the new env variables;
  - how to seed and run a harvest by hand (`POST /api/admin/tasks` with `SEED_SOURCES`);
  - the status endpoint.

- [ ] **Step 1: Write the failing tests:**
  - `HarvestSchedulerTest.disabledEnqueuesNothing`.
  - `enabledEnqueuesWithDatedDedupKeys`.
  - `AdminSourceControllerIT.promotionEnqueuesOnboardingOnce`.
  - `unknownSource404`.
  - `badTier400`.
  - `withoutToken401`.
  - `AdminStatusControllerIT.reportsCounts`.
- [ ] **Step 2:** Run the tests. Expected: FAIL.
- [ ] **Step 3:** Implement it and write the docs.
- [ ] **Step 4:** Run `mvn -B verify`. Expected: all modules PASS.
- [ ] **Step 5:** Commit `feat: harvest scheduling, admin source curation and status, ADR-0004`.

---

### Task 11: Live dev run (M2 finish line)

No new code unless the run exposes defects. Each fix gets its own failing test and commit.

- [ ] **Step 1:** Run `docker compose up -d db`. Start the indexer with:
  - `GARRULUS_INDEXER_PORT=18081`
  - `GARRULUS_HARVEST_ENABLED=true`
  - `FINDER_DICTIONARY=$PWD/eval/work/dictionary-316691.bin`
  - `OPENALEX_API_KEY` / `CROSSREF_MAILTO` only if they are set in `.env`

  Start `web` with `SERVER_PORT=18080` and an `ADMIN_TOKEN` from the shell, never written to a file.
- [ ] **Step 2:** Enqueue `SEED_SOURCES` through the admin API. Record the seed report: counts per tier, whether it was truncated (keyless), and the OpenAlex spend. Spot-check 10 CORE and 10 BROAD titles for plausibility. ZooKeys, Zootaxa, Phytotaxa and MycoKeys should be CORE.
- [ ] **Step 3:** Let onboarding, the first LIVE, Crossref and the content tasks run. After at least 2 hours, record from `/api/admin/status`:
  - works per origin, state and text_level;
  - content rows per route;
  - DEAD tasks with their errors;
  - spend vs the cap;
  - blob directory size.

  Fix any defect test-first. Expected: zero DEAD tasks except for documented permanent failures, and spend ≤ the cap.
- [ ] **Step 4:** Leave the indexer and web running in the background for the week-long unattended run. Write `docs/m2-dev-run.md` with the start time, the commands, and the first-day numbers. The week-long check ("A week of live harvesting runs unattended into the dev DB") is done when `harvest_run` shows 7 consecutive daily LIVE and Crossref runs as `DONE` or `DEFERRED`-then-`DONE`, with no growth in DEAD tasks.
- [ ] **Step 5:** Commit `docs: M2 dev run report`.
