# M4 Accounts & alerts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People sign in to Garrulus (ORCID or an emailed link), follow taxa, journals, names and taxonomic groups,
and get the new papers as hits, email digests and Atom feeds.

**Architecture:**
- **The indexer** evaluates hits after every indexing of a work (`EVALUATE_HITS`) and writes `hit` rows.
- **`web`** gets Spring Security (one-time-token email sign-in, ORCID OIDC, sessions in Postgres), the `/api/me`
  endpoints, feeds, and an hourly digest job that mails undelivered hits.
- **The schema** (V7) lives in `store`, like every other table. The two apps never call each other.

**Tech Stack:**
- Java 25, Spring Boot 4.1, Spring Security 7 (`oneTimeTokenLogin`, `oauth2Login`), Spring Session JDBC,
  Spring Mail, ShedLock.
- MyBatis, Flyway, Postgres 17, Testcontainers.
- React 18, Mantine 7.17.8, TanStack Query 5, vitest/MSW.
- Mailpit for development mail.

**Spec:** `docs/superpowers/specs/2026-11-16-m4-accounts-and-alerts-design.md` (approved 2026-11-16, commit 09980a0).
Background: `docs/design.md` §7–§9, ADR-0005, CLAUDE.md.

**Branching:** work directly on `main`, as Markus asked. No deployment: everything is local.

## Global Constraints

- **The two apps never call each other.**
  - The indexer writes `hit` and `work.first_indexed_at`.
  - `web` writes `app_user`, `subscription`, sessions, tokens, `digest`, `email_token` and `hit.digest_id`.
- **One migration, `V7__accounts_and_alerts.sql`, in `store`, expand-only.**
  - `web` never migrates.
  - Spring Session and one-time-token tables are created by V7, never by framework auto-init: set
    `spring.session.jdbc.initialize-schema=never`.
- **Free by default.**
  - Without `spring.mail.host`, `web` logs "mail suppressed" with the user id (never the address) and sends nothing.
  - No paid service.
- **No secrets in the repo.** Read these from the environment:
  - `ORCID_CLIENT_ID`, `ORCID_CLIENT_SECRET`, `ORCID_BASE_URL` (default `https://orcid.org`);
  - `GARRULUS_PUBLIC_URL` (default `http://localhost:5173`);
  - `GARRULUS_MAIL_FROM` (default `Garrulus <noreply@localhost>`), plus `spring.mail.*`;
  - `GARRULUS_SIGNING_KEY` (blank means a random key per start, with a WARN);
  - `GARRULUS_DIGEST_CRON` (default `0 5 * * * *`);
  - `GARRULUS_HIT_WINDOW_DAYS` (default 90).
- **No email address** in logs, feeds, URLs or public API responses. Log the user id.
- **Public name "Garrulus"** in mails, pages and feeds. Colours come from `docs/brand/`.
- **Values from the spec:**
  - Alert window: 90 days rolling, or `since_year`. Never more than 7 days in the future.
  - At most 100 subscriptions per user. Labels are at most 200 characters.
  - Sign-in token: valid 15 minutes, single use.
  - Rate limits: 3 links per address per 15 minutes, 20 per IP per hour.
  - Verify-email token: valid 24 hours.
  - Digest: at most 20 papers per subscription. A hit is mailed only if it is at most 14 days old (DAILY) or 21 days
    old (WEEKLY), and created after `digest_since`.
  - Defaults: digest DAILY at local hour 7; weekly digests on MONDAY.
  - Feeds: 50 entries.
  - Sessions: about 30 days.
- **Never touch local ports 8080/8081.** The dev run uses 18080/18081, and Vite uses 5173 for the dev run (Task 12).
- **Commits:**
  - conventional style, explicit paths;
  - no tool attribution or co-author lines;
  - never commit `eval/work/`, `data/`, `.env` or PDFs.
- **Every new public `GET /api/**`** goes into `web/src/main/resources/static/api/openapi.yaml`.
  `OpenApiCoverageTest` enforces this, and now covers `/api/me/**` and `/api/auth/**` too (cookie auth).

## Review Focus

1. **Digest due time across time zones and DST.** A user in `Europe/Berlin` with hour 7 gets the digest once on the
   day clocks change. A user in `Asia/Kolkata` (+05:30) gets it once per local day.
   - Owner: Task 8, `DigestScheduleTest`.
2. **Papers with missing or odd dates:** no `publication_date`, a year of 1886 from a LIVE harvest, a date 30 days in
   the future. The window falls back to `first_seen_at` for a missing date and rejects the others.
   - Owner: Task 2, `HitWindowTest`.
3. **The same address written differently.** `" Foo@Example.org "` and `foo@example.org` are one account.
   - Owner: Task 4 (`emailIsNormalised`) and Task 5 (`verifyNormalisesAddress`).
4. **Existing clients after Spring Security is added.**
   - The public GETs, `/api/openapi.yaml`, `/actuator/health` and the admin-token POSTs work exactly as before.
   - CSRF applies only to cookie-authenticated requests.
   - Owner: Task 4. All existing web ITs must pass unchanged, plus `publicAndAdminUnchanged`.
5. **A redelivered or concurrent `EVALUATE_HITS`** for the same work creates no duplicate hit and no second
   `NEW_NAMES`.
   - Owner: Task 3, `evaluateIsIdempotent`.

---

### Task 1: Schema V7 and store mappers

**Files:**
- Create: `store/src/main/resources/db/migration/V7__accounts_and_alerts.sql`
- Create, in package `org.catalogueoflife.garrulus.store.account`:
  - mappers `AccountMapper`, `SubscriptionMapper`, `HitMapper`, `DigestMapper`, `EmailTokenMapper`, with XML next to
    the existing ones;
  - records `AppUser`, `Subscription`, `Hit`, `Digest`, `EmailToken`.
- Modify: `store/.../index/IndexMapper.xml` (`setIndexed` also sets `first_indexed_at = coalesce(first_indexed_at, now())`).
- Modify: `store/.../search/SearchMapper.{java,xml}` (add `workMatches`).
- Test:
  - `store/src/test/.../account/AccountMappersIT.java`
  - `SchemaIT` (new tables)
  - `IndexMapper` coverage in the existing store/indexer ITs

**Interfaces (produces):**
- **Tables, as in the spec's data model:**
  - `app_user`:
    - `email` is `citext` (or `lower()` unique), unique and nullable;
    - `digest_frequency` is CHECK `DAILY|WEEKLY|OFF`;
    - `digest_weekday` is smallint 1–7 (ISO, default 1);
    - `digest_hour` is smallint 0–23 (default 7).
  - `subscription`:
    - `type` is CHECK `TAXON|SOURCE|NAME|TAXGROUP`;
    - `target` is text;
    - `filters` is jsonb (default `{}`);
    - `since_year` is a nullable smallint;
    - unique (`user_id`, `type`, `target`); index (`type`, `target`); `user_id` cascades.
  - `hit`:
    - unique (`subscription_id`, `work_id`, `kind`);
    - index (`subscription_id`, `digest_id`).
  - `digest`, `email_token` as in the spec.
  - The Spring Session tables, with Spring's PostgreSQL DDL.
  - `one_time_tokens`: `token_value` varchar(36) PK, `username` varchar(320), `expires_at` timestamp. Wider than
    Spring's 50, because the username is an email address.
  - `work.first_indexed_at`, backfilled from `indexed_at`.
- **`AccountMapper`:**
  - `findByEmail(String normalisedEmail)`, `findByOrcid(String)`, `findById(long)`, `insert(AppUser)`;
  - `updateSettings(...)`, `setEmail(long id, String email)`, `setOrcid(long id, String orcid)`, `touchLogin(long id)`;
  - `delete(long id)`.
- **`SubscriptionMapper`:**
  - `listByUser(long userId)`, `countByUser(long userId)`, `insert(Subscription)`, `update(Subscription)`;
  - `delete(long id, long userId)`, `byFeedToken(String)`, `setFeedToken(long id, String)`;
  - `candidates(String sourceId, List<String> taxonIds, List<String> nameIds, List<String> taxgroups)`: unpaused
    subscriptions of any user whose (type, target) matches one of the keys.
- **`HitMapper`:**
  - `insertIfAbsent(Hit)` returns the number of rows (0 or 1), using `ON CONFLICT DO NOTHING`;
  - `find(long subscriptionId, long workId, String kind)`;
  - `listByUser(long userId, @Nullable Long subscriptionId, int limit, int offset)`;
  - `undeliveredForDigest(long userId, Instant since, Instant minCreated)`;
  - `markDelivered(List<Long> hitIds, long digestId)`, `unmark(long digestId)`;
  - `byFeed(long subscriptionId, int limit)`.
- **`DigestMapper`:** `insert(Digest)`, `setStatus(long id, String status, @Nullable String error)`,
  `lastSent(long userId)`.
- **`EmailTokenMapper`:** `insert(long userId, String email, String tokenHash, Instant expires)`,
  `consume(String tokenHash)` returns the row and deletes it.
- **`SearchMapper.workMatches(long workId, WorkFilter f)`** returns boolean. It reuses the `from`/`where` fragments,
  restricted to `w.id = #{workId}`.

- [ ] **Step 1: Write the failing tests** in `AccountMappersIT`:
  - `emailIsUniqueIgnoringCase`: insert `Foo@example.org`; inserting `foo@EXAMPLE.org` violates the unique key.
  - `subscriptionUniquePerUserTypeTarget`
  - `hitInsertIfAbsentReturnsZeroOnDuplicate`
  - `candidatesFindsTaxonNameSourceAndGroupKeysAndSkipsPaused`
  - `deleteUserCascadesSubscriptionsHitsDigestsTokens`
  - `firstIndexedAtIsSetOnceAndKept`: `setIndexed` twice keeps the first timestamp and moves `indexed_at`.
  - `workMatchesAppliesFilter`: a code filter that excludes the work gives false.
- [ ] **Step 2: Run them and see them fail.**
  `mvn -B -pl store -am verify -Dit.test='AccountMappersIT,SchemaIT'`
- [ ] **Step 3: Write V7 and the mappers.**
- [ ] **Step 4: Run them and see them pass.** Then run `mvn -B -pl store,indexer -am verify`; the existing ITs must
  pass, since `setIndexed` changed.
- [ ] **Step 5: Commit.** `feat(store): V7 accounts, subscriptions, hits, digests; work.first_indexed_at`

### Task 2: Hit rules (pure)

**Files:**
- Create, in `indexer/src/main/java/.../indexer/hits/`:
  - `HitWindow.java`
  - `SubscriptionMatcher.java`
  - `HitProperties.java` (`garrulus.hits.window-days`, default 90; `future-days`, 7)
- Test: `indexer/src/test/.../hits/HitWindowTest.java`, `SubscriptionMatcherTest.java`

**Interfaces (produces):**
- `HitWindow.contains(@Nullable LocalDate publicationDate, @Nullable Integer year, Instant firstSeenAt,
  @Nullable Integer sinceYear, LocalDate todayUtc, HitProperties p)` returns boolean.
  - When the date is missing, use `firstSeenAt`'s UTC date.
  - With `sinceYear`, compare `coalesce(year, year of firstSeenAt)` with it.
  - Both modes reject a date more than `future-days` ahead.
- `SubscriptionMatcher.match(Subscription s, WorkInfo work, List<WorkNameView> names)` returns
  `Match(boolean matches, List<WorkNameView> names)`, where `names` are the matching names for the hit snapshot.
  - `WorkInfo(long id, @Nullable Long sourceId, String textLevel)`
  - `WorkNameView(long nameStringId, String name, String[] taxonPath, @Nullable String taxgroup, boolean mainSubject,
    boolean onlyReference, String[] actTypes, String placementMethod)`
- **Rules,** exactly as in the spec ("Which subscriptions match"):
  - `TAXGROUP` matches a name whose taxgroup is the target or a descendant. Use the existing ancestor expansion in
    `indexer/.../match/TaxGroups`.
  - `SOURCE` with default options matches every paper and lists its non-reference names. Its name options apply only
    when set.

- [ ] **Step 1: Write the failing tests.**
  - `HitWindowTest`:
    - `rollingWindowAccepts89RejectsOver90`
    - `missingDateUsesFirstSeen`
    - `year1886FromLiveIsOutside`
    - `thirtyDaysInFutureRejected`, `sixDaysInFutureAccepted`
    - `sinceYearUsesYearAndAcceptsOldPapers`
  - `SubscriptionMatcherTest`:
    - `taxonMatchesDescendantViaPath`
    - `taxonDefaultIgnoresNonMainSubject`
    - `referenceOnlyNamesNeverMatchByDefault`
    - `includeReferencesMatchesThem`
    - `newNamesOnlyNeedsActTypes`
    - `abstractOnlyExcludedWhenOptionFalse`
    - `sourceDefaultMatchesEveryPaper`
    - `nameMatchesByNameStringId`
    - `taxgroupMatchesChildGroup`: a follow of `Arthropods` matches a name with taxgroup `Coleoptera`.
- [ ] **Step 2: Run them and see them fail.** `mvn -B -pl indexer -am test -Dtest='HitWindowTest,SubscriptionMatcherTest'`
- [ ] **Step 3: Implement the rules.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit.** `feat(indexer): hit window and subscription matching rules`

### Task 3: `EVALUATE_HITS` handler and its triggers

**Files:**
- Create: `indexer/.../hits/EvaluateHitsHandler.java`.
- Modify: `indexer/.../match/MatchNamesHandler.java`. After `indexing.index(...)`, enqueue `EVALUATE_HITS`
  `{"workId":n}` at the task's priority and `run_after`, with no dedup key. Follow the pattern of
  `FindNamesHandler` line 153.
- Modify: `indexer/.../index/RescoreHandler.java`. Enqueue `EVALUATE_HITS` only for works where some `work_name` went
  from `main_subject=false` to true.
- Modify: `IndexMapper` (add a read of a work's `work_name` rows as `WorkNameView`, plus work info with `indexed_at`).
- Test: `indexer/src/test/.../hits/EvaluateHitsHandlerIT.java`

**Interfaces:**
- Consumes: Task 1's mappers; Task 2's `HitWindow`, `SubscriptionMatcher` and `HitProperties`.
- Produces: task type `"EVALUATE_HITS"` (constant `EvaluateHitsHandler.TYPE`). The hit `names` jsonb is an array of
  `{"nameStringId":n,"name":"…","actTypes":[…],"placement":"…","mainSubject":true}`.
- **Algorithm:**
  1. Lock the work row (`index.lockWork`).
  2. Load the work and its names.
  3. Get `candidates(...)` from the source id, the union of the names' `taxon_path` elements, the name ids, and the
     taxgroups with their ancestors.
  4. For each candidate:
     - skip it when `created >= work.indexed_at` or the work is outside the follow's window;
     - skip it when `!searchMapper.workMatches(workId, filter)` (filters only when non-empty);
     - run `SubscriptionMatcher`.
  5. If there is no `ARTICLE` hit yet, insert one with a `names` snapshot.
  6. Otherwise, compute the new (nameStringId, actType) pairs that are not in the `ARTICLE` snapshot. If there are
     any, `insertIfAbsent` a `NEW_NAMES` hit holding only those pairs.
  7. Never change `work.state`.

- [ ] **Step 1: Write the failing ITs** (fixtures are works with `work_name` rows inserted directly):
  - `articleHitForFollowCreatedBeforeIndexing`
  - `noHitWhenFollowCreatedAfterIndexing`
  - `onboardedOldPaperOutsideWindowGivesNoHit`, `onboardedPaperInsideWindowGivesHit` (origin ONBOARDING)
  - `backfillPaperHitsSinceYearFollowOnly`
  - `reindexDoesNotDuplicateArticle`
  - `newNamesWhenFullTextAddsSpNov`: the abstract has *Carabus xus* with no act; the re-index has `SP_NOV`. Expect one
    `NEW_NAMES` hit whose names hold only that pair, and no second `NEW_NAMES` on a further re-index.
  - `pausedFollowGetsNothing`
  - `filterExcludes`
  - `evaluateIsIdempotent`: run the same task twice; the hit count is unchanged.
  - `matchNamesEnqueuesEvaluateHits`
  - `rescoreEnqueuesOnlyWhenNameBecameMainSubject`
- [ ] **Step 2: Run them and see them fail.**
  `mvn -B -pl indexer -am verify -Dit.test=EvaluateHitsHandlerIT`
- [ ] **Step 3: Implement the handler and register it** like the other handlers.
- [ ] **Step 4: Run them and see them pass.** Then run `mvn -B -pl indexer -am verify`.
- [ ] **Step 5: Commit.** `feat(indexer): EVALUATE_HITS records hits after each indexing`

### Task 4: Security, sessions, mail and email-link sign-in (web)

**Files:**
- Modify: `web/pom.xml`. Add:
  - the security starter and the OAuth2 client starter;
  - Spring Session JDBC;
  - mail;
  - `shedlock-spring` and `shedlock-provider-jdbc-template` at `${shedlock.version}`;
  - `spring-security-test` (test scope).
- Create, in `web/.../auth/`:
  - `SecurityConfig.java`
  - `CsrfCookieFilter.java`, `SpaCsrfTokenRequestHandler.java` (adapted from `~/code/col/blixa/backend/.../auth/`)
  - `GarrulusPrincipal.java` (record `(long userId)`, implements `Principal`, `getName()` = the id as a string)
  - `CurrentUser.java`
  - `SignInLinkController.java` (`POST /api/auth/link`, `GET /api/auth/config`)
  - `SignInRateLimiter.java`
  - `AccountService.java` (`findOrCreateByEmail`, `normaliseEmail`)
  - `MeController.java` (`GET`/`PATCH /api/me`)
- Create: `web/.../mail/MailService.java`, adapted from Blixa's `EmailService`. Multipart text and HTML; logs the user
  id, never the address.
- Modify: `web/src/main/resources/application.yml`. Add:
  - the session timeout (30d), with `spring.session.jdbc.initialize-schema: never`;
  - `garrulus.public-url`, `garrulus.mail.from` and `garrulus.signing-key`;
  - the cookie settings.
- Modify: `docker-compose.yml` (service `mailpit`, profile `mail`, image `axllent/mailpit` at a pinned existing tag,
  ports `127.0.0.1:1025:1025` and `127.0.0.1:8025:8025`) and `.env.example`.
- Modify: `openapi.yaml` (`/api/me`, `/api/auth/config`).
- Test, in `web/src/test/.../auth/`:
  - `SignInLinkIT`
  - `SecurityIT`
  - `SignInRateLimiterTest`
  - a test configuration with a capturing `JavaMailSender`

**Interfaces (produces):**
- **`CurrentUser.require()`** returns `long userId`, or throws a 401. It works for any authentication whose
  principal is a `GarrulusPrincipal`.
- **Both sign-in paths replace the authentication** with `GarrulusPrincipal(userId)` and save it to the session.
  Task 5 does the same for ORCID.
- **`AccountService.normaliseEmail(String)`** trims, lower-cases and validates (one `@`, at most 254 characters).
  An invalid address is a 400.
- **The sign-in link:**
  1. `POST /api/auth/link {"email":…}` always answers `202 {}`, rate limit or not, account or not.
  2. If the limiter allows it, the server generates a token with Spring's `JdbcOneTimeTokenService` (15 minutes) and
     mails `GARRULUS_PUBLIC_URL/signin/verify?token=…`.
  3. The SPA posts the token to `POST /login/ott`. On success, the account is found or created with the email
     verified (setting `digest_since` on the first verification). The answer is `200 {"userId":…}`, not a redirect;
     failure is a 401.
  4. Spring's default `/ott/generate` page and endpoint must not be reachable.
- **Authorization rules:**
  - permitAll: `GET /api/**` except `/api/me/**`; `/api/openapi.yaml`; `/actuator/health`; `/feeds/**`; `/login/**`;
    `/oauth2/**`; `/api/auth/**`; `/api/unsubscribe`;
  - `/api/me/**` needs authentication (401 JSON, never a redirect);
  - `/api/admin/**` stays on `AdminAuth`.
- **CSRF:**
  - cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`;
  - ignored for `/api/admin/**` (header token, no cookie), `/api/unsubscribe` (signed token) and `/api/auth/link`
    (no session yet; it is rate limited).
- **`GET /api/auth/config`** returns `{"orcid":bool}`, true only when `ORCID_CLIENT_ID` is set.
- **`GET /api/me`** returns `{id, email, emailVerified, orcid, displayName, timeZone, digestFrequency, digestWeekday,
  digestHour}`.
- **`PATCH /api/me`** sets those settings. It validates the time zone (IANA), the hour (0–23) and the weekday (1–7).
  Changing `digestFrequency` from OFF to on sets `digest_since = now()`.

- [ ] **Step 1: Write the failing tests.**
  - `SignInLinkIT`:
    - `linkSentAndTokenSignsIn`: request a link, take the token from the captured mail, `POST /login/ott`, then
      `GET /api/me` with the session cookie gives 200.
    - `sameAnswerForUnknownAndRateLimited`
    - `tokenSingleUse`
    - `tokenExpiresAfter15Minutes` (inject the clock or set `expires_at` directly)
    - `emailIsNormalised`
    - `sessionStoredInDatabase` (a row in `spring_session`)
    - `ottGenerateEndpointNotExposed`
  - `SecurityIT`:
    - `publicAndAdminUnchanged`: a public GET without a cookie gives 200; an admin POST with the token and no CSRF
      gives 202.
    - `meWithoutSessionIs401`
    - `patchMeNeedsCsrf`
    - `authConfigReportsOrcidOff`
  - `SignInRateLimiterTest`: the 3-per-address and 20-per-IP limits, and the windows sliding.
- [ ] **Step 2: Run them and see them fail.**
  `mvn -B -pl web -am verify -Dit.test='SignInLinkIT,SecurityIT' -Dtest=SignInRateLimiterTest`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass,** then run the whole `mvn -B -pl web -am verify`. Every existing web IT
  must pass unchanged.
- [ ] **Step 5: Commit.** `feat(web): Spring Security with email sign-in links, JDBC sessions, mail service`

### Task 5: ORCID sign-in, email verification and account merge

**Files:**
- Create, in `web/.../auth/`:
  - `OrcidConfig.java`. It builds the `orcid` `ClientRegistration` only when `ORCID_CLIENT_ID` is non-blank:
    - endpoints `ORCID_BASE_URL` + `/oauth/authorize`, `/oauth/token`, `/oauth/userinfo`, `/oauth/jwks`;
    - issuer `ORCID_BASE_URL`, scope `openid`, user-name attribute `sub`.
    - `oauth2Login` is enabled only then.
  - `OrcidLoginSuccessHandler.java`. It finds or creates the account by ORCID iD, takes `display_name` from the ID
    token's name claims, swaps in `GarrulusPrincipal`, then redirects to `GARRULUS_PUBLIC_URL/` (or to
    `/account?addEmail=1` when the account has no email).
  - `EmailVerificationService.java`
  - `AccountMerger.java`
- Modify: `MeController` (`POST /api/me/email {"email":…}` returns 202; `POST /api/auth/verify-email {"token":…}`
  returns the outcome).
- Test: `web/src/test/.../auth/OrcidLoginIT.java` (uses `spring-security-test`'s `oidcLogin()` and calls the success
  handler), `EmailVerificationIT.java`, `AccountMergerIT.java`

**Interfaces:**
- **`POST /api/me/email`** stores a hashed token (SHA-256 of 32 random bytes, 24 hours) and mails
  `PUBLIC_URL/verify-email?token=…`. It answers 202 and never says whether the address is in use.
- **`POST /api/auth/verify-email`** returns `{"outcome":"SET"|"MERGED"|"REFUSED_OTHER_ORCID"}`. It needs the session
  of the account that requested it; otherwise it answers 401.
- **Merge rules** (spec, "Address changes and merges"):
  - The survivor is the account that holds the address. The older `created` keeps its id.
  - A duplicate (`type`, `target`) keeps the older subscription row and its options.
  - The other account's hits move where the unique key allows.
  - The 100 cap is not applied.
  - The loser is deleted; its sessions go too (found by principal name).
  - The current session's principal becomes the survivor.
  - The first verification sets `digest_since`.

- [ ] **Step 1: Write the failing tests.**
  - `OrcidLoginIT`:
    - `firstOrcidLoginCreatesAccountWithoutEmail`
    - `secondLoginFindsSameAccount`
    - `orcidOffMeansNoOauthEndpoints`: `/oauth2/authorization/orcid` gives 404 when unconfigured.
  - `EmailVerificationIT`:
    - `verifySetsUnusedAddress`
    - `verifyNormalisesAddress`
    - `tokenExpiresAfter24Hours`
    - `requestAnswerIdenticalForUsedAndUnusedAddress`
  - `AccountMergerIT`:
    - `mergesEmailOnlyAccountIntoOrcidSession`
    - `mergeKeepsOlderDuplicateSubscription`
    - `mergeMovesHitsWithoutClash`
    - `refusesWhenOtherAccountHasDifferentOrcid`
    - `mergeIgnoresCap` (two accounts with 60 follows each)
- [ ] **Step 2: Run them and see them fail.**
  `mvn -B -pl web -am verify -Dit.test='OrcidLoginIT,EmailVerificationIT,AccountMergerIT'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass,** then run the web module.
- [ ] **Step 5: Commit.** `feat(web): ORCID sign-in, email verification and account merge`

### Task 6: Subscriptions, hits, export and delete API

**Files:**
- Create, in `web/.../me/`:
  - `SubscriptionController.java`
  - `HitController.java`
  - `AccountDataController.java` (`GET /api/me/export`, `DELETE /api/me`)
  - DTOs
- Modify: `openapi.yaml`.
- Test: `web/src/test/.../me/SubscriptionControllerIT.java`, `HitControllerIT.java`, `AccountDataControllerIT.java`

**Interfaces:**
- **`POST /api/me/subscriptions`** takes `{type, target, label, mainSubjectOnly?, newNamesOnly?,
  includeAbstractOnly?, includeReferences?, filters?, sinceYear?}`.
  - Omitted options take the spec defaults for the type.
  - It validates:
    - `TAXGROUP` against the `TaxGroup` enum;
    - `SOURCE` and `NAME` as existing ids;
    - `TAXON` as a non-blank string of at most 50 characters, not checked against CLB (web never calls out);
    - the label: 1–200 characters, trimmed;
    - `sinceYear`: 1750 up to the current year;
    - the filter keys: only the seven named in the spec.
  - It returns 201 with the subscription, including `feedUrl` (`PUBLIC_URL/feeds/s/{token}.atom`).
  - A 101st subscription gives 409 `{"error":"limit"}`. A duplicate (type, target) gives 409
    `{"error":"exists","id":n}`.
- **`PATCH /api/me/subscriptions/{id}`** takes options, filters, `sinceYear`, `paused` and `label`. Another user's id
  gives 404.
- **`DELETE /api/me/subscriptions/{id}`** deletes; its hits cascade.
- **`POST /api/me/subscriptions/{id}/feed-token`** issues a new token; the old feed URL then gives 404.
- **`GET /api/me/hits?limit<=100&offset<=10000&subscription=`** returns hits newest first, each with a `WorkSummary`
  (reuse the existing DTO and query), `kind`, `names`, the subscription id and label, and `created`.
- **`GET /api/me/export`** returns JSON of the user's row, subscriptions (feed tokens included), hits and digests.
- **`DELETE /api/me`** deletes the user (the cascade does the rest) and their sessions, and answers 204.

- [ ] **Step 1: Write the failing tests.**
  - `createAppliesTypeDefaults`
  - `capIs100`
  - `duplicateIs409WithId`
  - `otherUsersSubscriptionIs404`
  - `sinceYearValidated`
  - `unknownFilterKeyIs400`
  - `newFeedTokenRevokesOld`
  - `hitsPagedNewestFirstWithWork`
  - `exportContainsEverything`
  - `deleteRemovesUserAndSessions`
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass,** together with `OpenApiCoverageTest`.
- [ ] **Step 5: Commit.** `feat(web): subscription, hit, export and account deletion endpoints`

### Task 7: Atom feeds

**Files:**
- Create: `web/.../feed/FeedController.java`, `web/.../feed/AtomWriter.java`. Write the XML with the JDK's StAX
  (`XMLStreamWriter`); no new dependency.
- Modify: `store/.../search/SearchMapper` (if needed, a query for feed works: works matching a `WorkFilter`, published
  in the last 90 days, by `first_indexed_at` desc, limit 50).
- Test: `web/src/test/.../feed/FeedControllerIT.java`, `AtomWriterTest.java`

**Interfaces:**
- **Public feeds:**
  - `GET /feeds/taxon/{colId}.atom`, `/feeds/source/{id}.atom`, `/feeds/name/{id}.atom`, `/feeds/taxgroup/{group}.atom`;
  - content type `application/atom+xml`;
  - the default options of the type (main subject for taxon and taxgroup; no reference-only names), any origin,
    published in the last 90 days;
  - a 404 for an unknown source, name or group.
- **Private feed:** `GET /feeds/s/{token}.atom` gives the subscription's hits; an unknown token gives 404.
- **Entries:**
  - `id` = `PUBLIC_URL/work/{id}`;
  - `updated` = `first_indexed_at`;
  - `title`, a link to the Garrulus page, a DOI link, the journal and date in the summary;
  - for hits, the matching names, with new names marked.
- **Feed metadata:**
  - feed `title` "Garrulus: <label>";
  - feed `updated` = the newest entry;
  - no email address anywhere.

- [ ] **Step 1: Write the failing tests.**
  - `taxonFeedListsRecentMainSubjectWorks`
  - `oldPaperNotInFeed`
  - `privateFeedListsHits`
  - `revokedTokenIs404`
  - `atomIsWellFormedAndEscapes`: a title holding `<i>` and `&` comes out escaped, and the output parses.
- [ ] **Step 2: Run them and see them fail.** **Step 3: Implement.** **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit.** `feat(web): public and private Atom feeds`

### Task 8: Digests

**Files:**
- Create, in `web/.../digest/`:
  - `DigestSchedule.java` (pure: is a user due)
  - `DigestComposer.java` (pure: text and HTML)
  - `DigestJob.java` (`@Scheduled(cron = "${garrulus.digest.cron:0 5 * * * *}")` with ShedLock; configure the
    ShedLock JDBC provider in web like the indexer's)
  - `UnsubscribeController.java`
  - `UnsubscribeTokens.java` (HMAC-SHA256)
- Create: `web/.../api/AdminDigestController.java` (`POST /api/admin/digests/run {"userId":n}`, admin token).
- Test: `DigestScheduleTest`, `DigestComposerTest`, `UnsubscribeTokensTest`, `DigestJobIT`, `UnsubscribeIT`

**Interfaces:**
- **`DigestSchedule.isDue(AppUser u, @Nullable Instant lastSent, Instant now)`** is true when all of these hold:
  - the email is verified and the frequency is not OFF;
  - local now (`u.timeZone`) has `hour >= digest_hour`;
  - DAILY: no digest sent earlier on this local date;
  - WEEKLY: the local ISO weekday is `>= digest_weekday` and no digest was sent in this local ISO week.
- **`DigestComposer.compose(AppUser u, List<SubscriptionHits> groups, String publicUrl)`** returns
  `Mail(subject, text, html)`.
  - Groups are ordered by subscription label.
  - Within a group: `NEW_NAMES` and papers with acts first, then newest. At most 20, then "and N more" with a link to
    `PUBLIC_URL/hits?subscription=id`.
  - The footer has the settings link and the unsubscribe link.
  - Subject: "Garrulus: N new papers" (or "1 new paper").
- **`DigestJob.runFor(long userId, boolean force)`:**
  1. Collect the hits with `undeliveredForDigest(userId, digest_since, now − 14d or 21d)`. If there are none, send
     nothing and write no row.
  2. In one transaction, insert a `digest` row (SENT) and `markDelivered`.
  3. Send.
  4. On a send exception: `unmark`, then `setStatus(FAILED, message)`.
  - The scheduled run calls it for every due user. The admin endpoint calls it with `force=true`, which skips the
    due check only.
- **The mail** has the headers `List-Unsubscribe: <PUBLIC_URL/api/unsubscribe?t=…>` and
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
- **Unsubscribing:**
  - `POST /api/unsubscribe?t=` verifies the HMAC of `unsub:<userId>`, sets `digest_frequency=OFF` and answers 200.
  - `GET` does not change anything; the SPA page `/unsubscribe` posts.
  - A bad token gives 400.

- [ ] **Step 1: Write the failing tests.**
  - `DigestScheduleTest`:
    - `dailyDueOnceAfterHour`
    - `berlinDstChangeDayOnce` (2026-10-25)
    - `kolkataHalfHourOffset`
    - `weeklyDueFromWeekdayOncePerWeek`
    - `offAndUnverifiedNeverDue`
  - `DigestComposerTest`:
    - `capsAt20WithMoreLink`
    - `newNamesFirst`
    - `subjectPlural`
    - `htmlEscapesTitles`
  - `UnsubscribeTokensTest`: `roundTrip`, `tamperedRejected`.
  - `DigestJobIT`:
    - `sendsUndeliveredHitsOnceAndMarksThem`
    - `noMailWithoutHits`
    - `hitsBeforeDigestSinceNeverMailed`
    - `hitsOlderThan14DaysNotMailedButListed`
    - `sendFailureUnmarksAndRecordsFailed`
    - `adminRunForcesSend`
  - `UnsubscribeIT`: `oneClickPostTurnsOff`, `getDoesNotTurnOff`.
- [ ] **Step 2: Run them and see them fail.** **Step 3: Implement.** **Step 4: Run them and see them pass.**
- [ ] **Step 5: Commit.** `feat(web): hourly email digests with one-click unsubscribe`

### Task 9: Frontend, sign-in and account

**Files:**
- Modify: `frontend/src/api.ts`. Add a `send()` for non-GET requests that sends `X-XSRF-TOKEN` from the cookie and
  `credentials: 'same-origin'`; add types for `Me`, `Subscription` and `Hit`; add the auth calls.
- Create:
  - `frontend/src/auth/useMe.ts` (TanStack query `['me']`; 401 means signed out)
  - `frontend/src/components/AccountMenu.tsx`
  - pages `SignInPage.tsx` (`/signin`), `SignInVerifyPage.tsx` (`/signin/verify`), `VerifyEmailPage.tsx`
    (`/verify-email`), `AccountPage.tsx` (`/account`), `UnsubscribePage.tsx` (`/unsubscribe`)
- Modify: `routes.tsx`, `App.tsx` (the header holds the account menu or "Sign in").
- Test: one `*.test.tsx` per page with MSW handlers in `frontend/src/test/server.ts`.

**Behaviour:**
- **Sign-in page:**
  - "Continue with ORCID" (a link to `/oauth2/authorization/orcid`) only when `/api/auth/config` says so;
  - an email form, then "Check your inbox" whatever the answer;
  - `?next=` is kept through sign-in.
- **The verify pages** post their token once on mount, then go to `next` or `/`.
- **Account page:**
  - email (add or change, then "we sent a link"), time zone (select, defaulting to the browser's zone when unset),
    frequency, weekday and hour;
  - "Download my data" (export);
  - "Delete account" behind a confirmation dialog that asks the user to type DELETE.
- **The unsubscribe page** shows a button that posts, then a confirmation.

- [ ] **Step 1: Write the failing tests.**
  - `SignInPage`: `hidesOrcidWhenOff`, `showsCheckInboxAfterSubmit`.
  - `SignInVerifyPage`: `postsTokenOnceAndRedirects`.
  - `AccountPage`: `savesDigestSettings`, `deleteNeedsTypedConfirmation`.
  - `api.send`: `addsXsrfHeader`.
- [ ] **Step 2: Run them and see them fail.** `cd frontend && npm test`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and see them pass.** Then run `npm test && npm run build`.
- [ ] **Step 5: Commit.** `feat(frontend): sign-in, account settings and unsubscribe pages`

### Task 10: Frontend, follows, hits and feed links

**Files:**
- Create: `frontend/src/components/FollowButton.tsx` (with its dialog), `FeedLink.tsx`; pages `FollowingPage.tsx`
  (`/following`) and `HitsPage.tsx` (`/hits`).
- Modify: `TaxonPage.tsx`, `SourcePage.tsx`, `NamePage.tsx` (Follow button and feed link), `SearchPage.tsx` (Follow
  when exactly one taxgroup is the only filter), `routes.tsx`.
- Test: `FollowButton.test.tsx`, `FollowingPage.test.tsx`, `HitsPage.test.tsx`

**Behaviour:**
- **Follow button:**
  - Signed out, it goes to `/signin?next=<current path>`.
  - Already followed, it shows "Following" and opens the edit dialog.
- **The dialog:**
  - the type's default options;
  - "Only new names";
  - "Papers published: in the last 90 days / since year [input]";
  - a collapsed "Narrow down" with code, taxgroup, journal type and subfield, reusing `FilterSidebar` pieces where
    they fit.
  - A 409 limit answer shows "You follow 100 things already".
- **Following page:** a list with the label, type, a paused switch, edit, delete, and "Copy feed URL".
- **Hits page:**
  - newest first, with a "New names" badge;
  - the subscription filter comes from `?subscription=`;
  - it uses the existing `WorkList` row where it fits.

- [ ] **Step 1: Write the failing tests.**
  - `FollowButton`: `signedOutGoesToSignIn`, `createsWithDefaultsAndSinceYear`, `showsLimitMessage`.
  - `FollowingPage`: `pauseAndDelete`.
  - `HitsPage`: `filtersBySubscriptionFromUrl`.
- [ ] **Step 2: Run them and see them fail.** **Step 3: Implement.** **Step 4: Run them and see them pass,** plus
  `npm run build`.
- [ ] **Step 5: Commit.** `feat(frontend): follow dialog, following and hits pages, feed links`

### Task 11: Docs

**Files:**
- Create: `docs/adr/0006-accounts-and-alerts.md`. Record:
  - the hit rules and window (Markus 2026-11-16: any origin; 90 days or `since_year`; follows never pull in papers
    that are already indexed);
  - that `NOTIFIED` is not used;
  - email-link sign-in with one-time tokens, and JDBC sessions;
  - the merge policy;
  - the digest rules;
  - unsubscribe;
  - CSRF scope;
  - deployment is out of M4.
- Modify:
  - `CLAUDE.md`: status; the configuration list (ORCID, mail, public URL, signing key, digest cron, hit window,
    Mailpit); the task type `EVALUATE_HITS`; the `/api/me`, `/api/auth` and `/feeds` endpoints; the dev sign-in
    notes;
  - `docs/design.md`: the M4 status and the changed "done when";
  - `.env.example`.

- [ ] **Step 1: Write them. Step 2: Commit.** `docs: ADR-0006 accounts and alerts; CLAUDE.md for M4`

### Task 12: Dev check (controller, not a subagent)

- [ ] **Build and restart the dev run.**
  1. Run `mvn -B -DskipTests clean install`, then `npm run build`.
  2. Copy the jars to `data/run/`.
  3. Start Mailpit (`docker compose --profile mail up -d mailpit`).
  4. Restart the indexer (V7 migrates), then `web` on 18080 with `GARRULUS_PUBLIC_URL=http://localhost:5173`,
     `SPRING_MAIL_HOST=localhost`, `SPRING_MAIL_PORT=1025`, and ORCID from `.env`.
  5. Run Vite from a scratch config that proxies to 18080 on port 5173.
- [ ] **Sign in and follow.** Sign in by email link with a test address (Mailpit catches it). Follow:
  - Carabidae `8KTL7` (default window);
  - Zootaxa;
  - one name;
  - one taxon with `sinceYear` 2020.
  Then promote one active, not yet onboarded source to CORE through the admin API, so its onboarding indexes older
  papers.
- [ ] **ORCID sign-in by Markus.** Ask him to sign in with ORCID at `http://localhost:5173` and add an email address.
  This is the only step that needs him.
- [ ] **Get the digest.** After the onboarding and the next nightly harvest, trigger it with
  `POST /api/admin/digests/run` (or wait for the hour).
- [ ] **Check the results by SQL.**
  - Every hit was created after its follow and lies inside its window.
  - No (subscription, work) pair has two ARTICLE hits.
  - The onboarded journal's papers older than 90 days gave no hit to the default-window follows.
  - Spot-check five hits by hand.
- [ ] **Record it** in `docs/m4-dev-check.md` and commit `docs: M4 dev check`.
