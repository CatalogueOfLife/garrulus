# M0 Platform Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Naming (decided 2026-11-14):**
> - The internal id is `nametracker`: packages, artifactIds, the database, systemd units and the repo.
> - The public name is **Garrulus** (the European jay): UI, logo, page titles, emails, feeds, URLs and User-Agent.
> - See `docs/brand/README.md`.

**Goal:** A buildable multi-module repo with the shared `store` module (schema v1 + task queue), two bootable Spring Boot apps (`indexer`, `web`) that cooperate only through the database, local Docker services, CI build and a frontend shell.

**Architecture:** Maven multi-module build on Spring Boot 4.1 / JDK 25, versions of CLB modules from `clb-bom`. `store` owns the Flyway migrations, MyBatis mappers and the Postgres task queue. `indexer` applies migrations and runs task workers; `web` validates the schema, serves `/api` and can enqueue tasks. The finder and eval modules are created empty here and filled by the finder plan.

**Tech Stack:** Java 25 (Liberica 25.0.3), Maven 3.9, Spring Boot 4.1.0, MyBatis Spring Boot starter 4.0.1, Flyway, PostgreSQL 17, ShedLock (JDBC provider), Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`), JUnit 5 + AssertJ; frontend Vite 6 + React 18 + TypeScript 5 + Mantine 7.17 + TanStack Query 5 + vitest 3.

**Spec:** `docs/design.md` (sections *Two applications, one database*, §6, §10, *Repo layout and stack*, *Roadmap* M0, *Verification*).

## Global Constraints

- **Public name:** "Garrulus" everywhere users see the app: UI, page titles, logo, favicon, OpenAPI title, emails,
  feed titles. The outgoing HTTP User-Agent is `Garrulus/<version> (+https://www.catalogueoflife.org)`. Colours and logo
  come only from `docs/brand/README.md` and `docs/brand/*.svg`.
- **Internal id `nametracker`:** Maven groupId `org.catalogueoflife`, artifactIds `nametracker-parent`, `nametracker-finder-api`, `nametracker-finder-core`, `nametracker-finder-service`, `nametracker-store`, `nametracker-indexer`, `nametracker-web`, `nametracker-eval`; base package `org.catalogueoflife.garrulus`; local DB/user/password `nametracker`.
- JDK 25 everywhere; `.sdkmanrc` = `java=25.0.3-librca` (copy of Blixa's). `name-parser-rust` needs JDK 25 (FFM).
- Parent: `spring-boot-starter-parent` **4.1.0**; property `clb.version` = **1.7.1-SNAPSHOT** (current `~/code/col/backend/bom/pom.xml`), imported as `org.catalogueoflife:clb-bom` (scope import). No other CoL parent POM.
- GBIF repositories copied verbatim from `~/code/col/blixa/backend/pom.xml` (`gbif-releases`, `gbif-snapshot`). Add this repo to the "must change together" list in `~/.claude/CLAUDE.md`.
- Configuration only through environment variables with defaults in `application.yml`; no secrets in the repo.
- Free by default: nothing in this plan calls a paid service.
- The two apps never call each other; all coordination goes through tables in `store`.
- Migrations live only in `store/src/main/resources/db/migration`, named `V<n>__<snake_case>.sql`, and must be backward compatible with the running `web` (expand/contract).
- Tests: unit tests `*Test` (surefire), integration tests `*IT` (failsafe) on one shared `postgres:17` Testcontainer per JVM, using Blixa's singleton pattern (`AbstractPostgresIT`).
- Commit messages: conventional style (`feat:`, `chore:` …), no tool attribution lines.

## Review Focus

- **Indexer killed mid-task (SIGKILL):** tasks left `RUNNING` must run again after restart, not hang forever → test `requeuesRunningTasksOnStartup` (Task 4).
- **`web` started against a database the indexer has already migrated further:** `web` must start (future migrations ignored), but must refuse to start if *it* has migrations the DB lacks → tests `startsWhenDatabaseIsAhead`, `failsWhenMigrationsPending` (Task 5).
- **The same task enqueued twice** (e.g. web re-requests an onboarding while one is queued): one queued task, the second call returns the existing id → test `enqueueWithSameDedupKeyReturnsExistingTask` (Task 3).
- **A task that always throws:** retried with exponential backoff, then parked as `DEAD`, never blocking other tasks → test `failingTaskBecomesDeadAfterMaxAttempts` (Task 3) and `failingHandlerDoesNotStopDispatcher` (Task 4).
- **Admin endpoint with no token configured:** must not be reachable at all (404), so a forgotten env var never opens it → test `adminEndpointHiddenWithoutToken` (Task 5).

---

### Task 1: Parent POM, module layout and repo basics

**Files:**
- Create: `pom.xml`, `.sdkmanrc`, `.gitignore`, `README.md`
- Create: `finder-api/pom.xml`, `finder-core/pom.xml`, `finder-service/pom.xml`, `store/pom.xml`, `indexer/pom.xml`, `web/pom.xml`, `eval/pom.xml`
- Modify: `~/.claude/CLAUDE.md` (Conventions → Maven line: add this repo to the list of repos whose GBIF repository blocks must change together)

**Interfaces:**
- Produces: module coordinates `org.catalogueoflife:nametracker-<module>:0.1.0-SNAPSHOT`; module dependency graph `finder-core → finder-api`, `finder-service → finder-core`, `eval → finder-core`, `indexer → store, finder-core`, `web → store`.

- [ ] **Step 1: Write the parent `pom.xml`**
  `packaging=pom`, parent `spring-boot-starter-parent:4.1.0`, properties `java.version=25`, `clb.version=1.7.1-SNAPSHOT`, `mybatis-starter.version=4.0.1`; `dependencyManagement` imports `clb-bom` and declares all `nametracker-*` modules at `${project.version}`; `repositories` as in Global Constraints; `pluginManagement` for `maven-compiler-plugin` (`<parameters>true</parameters>`) and `maven-failsafe-plugin` (goals `integration-test`, `verify`). Only `indexer`, `web` and `finder-service` apply `spring-boot-maven-plugin` (repackage).
- [ ] **Step 2: Write the seven module POMs** with the dependency graph above; libraries (`finder-api`, `finder-core`, `store`, `eval`) declare no Boot plugin. `eval` gets `exec-maven-plugin` later (finder plan).
- [ ] **Step 3: `.sdkmanrc`, `.gitignore`** (`target/`, `node_modules/`, `frontend/dist/`, `.idea/`, `*.iml`, `eval/work/`, `.env`), README with one paragraph + pointer to `docs/design.md`.
- [ ] **Step 4: Build**
  Run: `sdk env && mvn -B -q -DskipTests package`
  Expected: BUILD SUCCESS for all 8 reactor projects.
- [ ] **Step 5: Commit** `chore: multi-module maven skeleton`

### Task 2: Local services (docker-compose)

**Files:**
- Create: `docker-compose.yml`, `.env.example`

**Interfaces:**
- Produces: Postgres on `localhost:5434` (db/user/password `nametracker`), gnfinder on `localhost:8778`, GROBID on `localhost:8070` (profile `grobid`), Label Studio on `localhost:8090` (profile `annotate`).

- [ ] **Step 1: Write `docker-compose.yml`** (`name: nametracker`), all images pinned:
  - `db`: `postgres:17`, `5434:5432`, healthcheck `pg_isready`, volume `pg`
  - `gnfinder`: `gnames/gnfinder:v1.4.0` (v1.5.0 is tagged but has no image yet), `8778:8999` (the image's default command is `-p 8999`)
  - `grobid`: `grobid/grobid:0.9.1-crf`, `8070:8070`, `init: true`, `ulimits: {core: 0}`, `profiles: [grobid]`
  - `labelstudio`: `heartexlabs/label-studio:1.23.2`, `8090:8080`, volume `ls:/label-studio/data`, `profiles: [annotate]`

  `.env.example` lists `OPENALEX_API_KEY=`, `CROSSREF_MAILTO=`, `ADMIN_TOKEN=`, `GNFINDER_URL=http://localhost:8778`, `GROBID_URL=http://localhost:8070`, with comments.
- [ ] **Step 2: Verify**
  Run: `docker compose up -d db gnfinder && docker compose ps`
  Expected: `db` healthy, `gnfinder` running; `curl -s localhost:8778/api/v1/ping` answers.
- [ ] **Step 3: Commit** `chore: docker-compose for local services`

### Task 3: `store` — schema v1, mappers, task queue

**Files:**
- Create: `store/src/main/resources/db/migration/V1__initial_schema.sql`
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/task/{Task.java, TaskPriority.java, TaskState.java, TaskMapper.java, TaskQueue.java}`
- Create: `store/src/main/resources/org/catalogueoflife/garrulus/store/task/TaskMapper.xml`
- Create: `store/src/main/java/org/catalogueoflife/garrulus/store/source/{Source.java, SourceMapper.java}` + XML; `.../store/work/{Work.java, WorkMapper.java}` + XML (insert/get/update only; queries come with M2)
- Test: `store/src/test/java/org/catalogueoflife/garrulus/store/{StoreTestApplication.java, AbstractPostgresIT.java, task/TaskQueueIT.java, SchemaIT.java}`

**Interfaces:**
- Produces:
  - `enum TaskPriority { LIVE(0), REPROCESS(1), BACKFILL(2) }` (lower runs first), `enum TaskState { QUEUED, RUNNING, DONE, DEAD }`
  - `record Task(long id, String type, String payload /* JSON */, TaskPriority priority, TaskState state, int attempts, Instant runAfter, String lockedBy, Instant lockedAt, String lastError, String dedupKey)`
  - `class TaskQueue(TaskMapper mapper)` with
    - `long enqueue(String type, String payloadJson, TaskPriority priority, @Nullable String dedupKey)` — returns the existing task's id if a QUEUED/RUNNING task has the same `dedupKey`
    - `List<Task> claim(Set<String> types, String workerId, int limit)` — sets RUNNING, `locked_by`, `locked_at=now()`, `attempts+1`
    - `void complete(long id)`
    - `void fail(long id, String error, int maxAttempts, Duration baseBackoff)` — back to QUEUED with `run_after = now() + baseBackoff * 2^(attempts-1)` while `attempts < maxAttempts`, else DEAD
    - `int requeueRunning(@Nullable Duration lockedLongerThan)` — RUNNING → QUEUED (all when `null`)
    - `Map<TaskState, Long> countByState()`
  - `record Source(...)` / `record Work(...)` mirroring the V1 columns.

- [ ] **Step 1: Write `V1__initial_schema.sql`** — exactly these tables (text + CHECK instead of PG enums, for MyBatis):

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE source (
  id bigserial PRIMARY KEY,
  openalex_id text UNIQUE, issn_l text, issns text[] NOT NULL DEFAULT '{}',
  title text NOT NULL, publisher text, host text, is_oa boolean, in_doaj boolean,
  tier text NOT NULL DEFAULT 'CANDIDATE' CHECK (tier IN ('CORE','BROAD','CANDIDATE','EXCLUDED')),
  origin text NOT NULL CHECK (origin IN ('SEED_TOPIC','SEED_LIST','DISCOVERED','MANUAL')),
  adapter_config jsonb NOT NULL DEFAULT '{}', active boolean NOT NULL DEFAULT true,
  subfields text[] NOT NULL DEFAULT '{}', primary_subfield text,
  journal_type text CHECK (journal_type IN ('TAXONOMIC','GENERAL')),
  taxgroups text[] NOT NULL DEFAULT '{}', taxgroup_shares jsonb NOT NULL DEFAULT '{}', codes text[] NOT NULL DEFAULT '{}',
  oa_model text CHECK (oa_model IN ('DIAMOND','GOLD','HYBRID','CLOSED')), country text,
  name_density real, act_rate real, works_per_year integer, last_harvested timestamptz,
  created timestamptz NOT NULL DEFAULT now(), modified timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX source_subfields_idx ON source USING gin (subfields);
CREATE INDEX source_taxgroups_idx ON source USING gin (taxgroups);
CREATE INDEX source_codes_idx ON source USING gin (codes);

CREATE TABLE work (
  id bigserial PRIMARY KEY,
  openalex_id text UNIQUE, doi text UNIQUE,          -- doi lower-case, without https://doi.org/
  source_id bigint REFERENCES source,
  title text, publication_date date, year smallint, volume text, issue text, pages text,
  authors jsonb NOT NULL DEFAULT '[]', type text, language text,
  oa_status text, license text, best_oa_url text,
  abstract text, abstract_source text CHECK (abstract_source IN ('CROSSREF_JATS','OPENALEX_INVERTED','PUBLISHER')),
  origin text NOT NULL CHECK (origin IN ('LIVE','SWEEP','ONBOARDING','BACKFILL')),
  state text NOT NULL DEFAULT 'DISCOVERED'
    CHECK (state IN ('DISCOVERED','CONTENT_RESOLVED','NAMES_FOUND','MATCHED','INDEXED','NOTIFIED')),
  text_level text CHECK (text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT')),
  codes text[] NOT NULL DEFAULT '{}', taxgroups text[] NOT NULL DEFAULT '{}', has_acts boolean NOT NULL DEFAULT false,
  first_seen_at timestamptz NOT NULL DEFAULT now(), modified timestamptz NOT NULL DEFAULT now(),
  CHECK (openalex_id IS NOT NULL OR doi IS NOT NULL)
);
CREATE INDEX work_source_idx ON work (source_id);
CREATE INDEX work_pubdate_idx ON work (publication_date);
CREATE INDEX work_codes_idx ON work USING gin (codes);
CREATE INDEX work_taxgroups_idx ON work USING gin (taxgroups);

CREATE TABLE content (
  id bigserial PRIMARY KEY,
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  route text NOT NULL CHECK (route IN ('PENSOFT','EUROPEPMC','PMC','OPENALEX_TEI','GROBID_LOCAL','ABSTRACT')),
  kind text NOT NULL CHECK (kind IN ('TAXPUB','JATS','TEI','ABSTRACT')),
  license text, retained boolean NOT NULL, raw_sha256 text, doc_sha256 text, source_url text,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (work_id, route)
);

CREATE TABLE task (
  id bigserial PRIMARY KEY,
  type text NOT NULL, payload jsonb NOT NULL DEFAULT '{}',
  priority smallint NOT NULL,
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','DONE','DEAD')),
  attempts smallint NOT NULL DEFAULT 0, run_after timestamptz NOT NULL DEFAULT now(),
  locked_by text, locked_at timestamptz, last_error text, dedup_key text,
  created timestamptz NOT NULL DEFAULT now(), finished timestamptz
);
CREATE INDEX task_claim_idx ON task (priority, run_after, id) WHERE state = 'QUEUED';
CREATE UNIQUE INDEX task_dedup_idx ON task (dedup_key) WHERE state IN ('QUEUED','RUNNING');

CREATE TABLE shedlock (
  name varchar(64) PRIMARY KEY, lock_until timestamptz NOT NULL,
  locked_at timestamptz NOT NULL, locked_by varchar(255) NOT NULL
);
```

- [ ] **Step 2: Write the failing ITs** — `StoreTestApplication` is a test-only `@SpringBootApplication` with `@MapperScan("org.catalogueoflife.garrulus.store")` and Flyway enabled; `AbstractPostgresIT` copies Blixa's singleton container.

```java
class TaskQueueIT extends AbstractPostgresIT {
  @Test void claimReturnsHighestPriorityFirst() {
    long backfill = queue.enqueue("PING", "{}", TaskPriority.BACKFILL, null);
    long live = queue.enqueue("PING", "{}", TaskPriority.LIVE, null);
    assertThat(queue.claim(Set.of("PING"), "w1", 1)).extracting(Task::id).containsExactly(live);
  }
  @Test void concurrentClaimsNeverReturnTheSameTask() { /* 50 tasks, 5 threads claiming 5 each in a loop → all ids distinct, total 50 */ }
  @Test void enqueueWithSameDedupKeyReturnsExistingTask() {
    long a = queue.enqueue("ONBOARD", "{}", TaskPriority.LIVE, "onboard:S1");
    assertThat(queue.enqueue("ONBOARD", "{}", TaskPriority.LIVE, "onboard:S1")).isEqualTo(a);
  }
  @Test void failingTaskBecomesDeadAfterMaxAttempts() {
    // maxAttempts=3, baseBackoff=0s: claim+fail three times → state DEAD, last_error kept; a 4th claim returns nothing
  }
  @Test void requeueRunningResetsLockedTasks() { /* claim → requeueRunning(null) == 1 → claim again returns it */ }
}
class SchemaIT extends AbstractPostgresIT {
  @Test void workNeedsOpenAlexIdOrDoi() { /* insert with both null → DataIntegrityViolationException */ }
}
```

- [ ] **Step 3: Run to see them fail**
  Run: `mvn -B -pl store verify -Dit.test='TaskQueueIT,SchemaIT'`
  Expected: FAIL (classes missing).
- [ ] **Step 4: Implement** the records, `TaskMapper` (XML) and `TaskQueue`. `claim` is one statement: `UPDATE task SET state='RUNNING', locked_by=#{workerId}, locked_at=now(), attempts=attempts+1 WHERE id IN (SELECT id FROM task WHERE state='QUEUED' AND run_after<=now() AND type = ANY(#{types}) ORDER BY priority, run_after, id LIMIT #{limit} FOR UPDATE SKIP LOCKED) RETURNING *`. `enqueue` uses `INSERT … ON CONFLICT (dedup_key) WHERE state IN ('QUEUED','RUNNING') DO NOTHING RETURNING id`, falling back to selecting the live task's id. Payload is bound as `jsonb` via a small `JsonbStringTypeHandler`.
- [ ] **Step 5: Run to see them pass** — same command; Expected: PASS.
- [ ] **Step 6: Commit** `feat(store): schema v1 and postgres task queue`

### Task 4: `indexer` app skeleton with task dispatcher

**Files:**
- Create: `indexer/src/main/java/org/catalogueoflife/garrulus/indexer/{IndexerApplication.java, IndexerConfig.java}`
- Create: `indexer/src/main/java/org/catalogueoflife/garrulus/indexer/task/{TaskHandler.java, TaskDispatcher.java, PingTaskHandler.java}`
- Create: `indexer/src/main/resources/application.yml`
- Test: `indexer/src/test/java/org/catalogueoflife/garrulus/indexer/{AbstractPostgresIT.java, task/TaskDispatcherIT.java}`

**Interfaces:**
- Consumes: `TaskQueue`, `Task`, `TaskPriority` (Task 3).
- Produces:
  - `interface TaskHandler { String type(); void handle(Task task) throws Exception; }` — every indexer pipeline stage later implements this.
  - `TaskDispatcher` polls every `garrulus.indexer.poll-interval` (default `2s`), claims up to the free worker slots (`garrulus.indexer.workers`, default `8`) for the types of all registered handlers, runs each on a virtual thread; on exception calls `fail(id, msg, garrulus.indexer.max-attempts (5), garrulus.indexer.backoff (1m))`.
  - Startup: `requeueRunning(null)` before the first poll (single indexer instance). Periodically: `requeueRunning(1h)`. Shutdown: stop claiming, wait up to 30 s for in-flight handlers.
  - Config: Flyway **enabled** (the indexer migrates), locations `classpath:db/migration`; `server.port=8081`, `server.address=127.0.0.1`, actuator `health,info` only; ShedLock `JdbcTemplateLockProvider` bean for later scheduled jobs.

- [ ] **Step 1: Write the failing IT**

```java
class TaskDispatcherIT extends AbstractPostgresIT {
  @Test void pingTaskIsHandled() { long id = queue.enqueue("PING", "{}", TaskPriority.LIVE, null); await().atMost(10, SECONDS).until(() -> state(id) == TaskState.DONE); }
  @Test void requeuesRunningTasksOnStartup() { /* insert a RUNNING task locked by "dead-worker", call dispatcher.onStartup() → task ends DONE */ }
  @Test void failingHandlerDoesNotStopDispatcher() { /* test-scoped handler type "BOOM" always throws; enqueue BOOM then PING → PING DONE, BOOM QUEUED with attempts=1 and last_error set */ }
}
```
- [ ] **Step 2: Run to see it fail** — `mvn -B -pl indexer -am verify -Dit.test=TaskDispatcherIT`; Expected: FAIL.
- [ ] **Step 3: Implement** `IndexerApplication` (`@SpringBootApplication`, `@MapperScan` of the store package, `@EnableScheduling`), `IndexerConfig` (beans `TaskQueue`, `LockProvider`), `TaskDispatcher`, `PingTaskHandler` (logs and returns).
- [ ] **Step 4: Run to see it pass** — same command; Expected: PASS.
- [ ] **Step 5: Commit** `feat(indexer): bootable indexer with task dispatcher`

### Task 5: `web` app skeleton — schema check, ping, admin enqueue

**Files:**
- Create: `web/src/main/java/org/catalogueoflife/garrulus/web/{WebApplication.java, WebConfig.java, SchemaCheck.java}`
- Create: `web/src/main/java/org/catalogueoflife/garrulus/web/api/{PingController.java, AdminTaskController.java, AdminTokenFilter.java}`
- Create: `web/src/main/resources/application.yml`
- Test: `web/src/test/java/org/catalogueoflife/garrulus/web/{AbstractPostgresIT.java, SchemaCheckIT.java, api/AdminTaskControllerIT.java}`

**Interfaces:**
- Consumes: `TaskQueue` (Task 3).
- Produces:
  - `GET /api/ping` → `200 "pong"` (health path used by deploy scripts, as in Blixa).
  - `POST /api/admin/tasks` body `{"type":"PING","payload":{},"priority":"LIVE","dedupKey":null}` → `202 {"id":<long>}`; requires header `X-Admin-Token` equal to `garrulus.admin.token` (env `ADMIN_TOKEN`). If the property is blank the endpoint returns `404`. Temporary until ORCID admin roles (M4).
  - `SchemaCheck` (`ApplicationRunner`): Flyway with `spring.flyway.enabled=false` for auto-migrate, and on startup `Flyway.configure().dataSource(ds).locations("classpath:db/migration").ignoreMigrationPatterns("*:future").load().validateWithResult()`; pending migrations → startup fails with message `"database schema is behind this web build; start the indexer first to migrate"`.
  - `server.port=8080`.

- [ ] **Step 1: Write the failing ITs**

```java
class SchemaCheckIT {   // own containers, no shared Spring context
  @Test void failsWhenMigrationsPending() { /* empty DB → starting WebApplication throws, message contains "start the indexer first" */ }
  @Test void startsWhenDatabaseIsAhead() { /* migrate with store's V1 plus a test-only V999__future.sql → web starts */ }
}
class AdminTaskControllerIT extends AbstractPostgresIT {
  @Test void enqueuesWithValidToken() { /* 202, task row QUEUED type PING */ }
  @Test void rejectsWrongToken() { /* 401 */ }
  @Test void adminEndpointHiddenWithoutToken() { /* context with blank token → 404 */ }
}
```
- [ ] **Step 2: Run to see them fail** — `mvn -B -pl web -am verify -Dit.test='SchemaCheckIT,AdminTaskControllerIT'`; Expected: FAIL.
- [ ] **Step 3: Implement** the classes above.
- [ ] **Step 4: Run to see them pass**; Expected: PASS.
- [ ] **Step 5: Manual cross-app check (the M0 verification line)**
  Run: `docker compose up -d db`, start indexer (`mvn -pl indexer spring-boot:run`) until migrated, stop it; start web (`ADMIN_TOKEN=dev mvn -pl web spring-boot:run`); `curl -s -XPOST -H 'X-Admin-Token: dev' -H 'Content-Type: application/json' -d '{"type":"PING"}' localhost:8080/api/admin/tasks`; `curl localhost:8080/actuator/health` → `UP`; start the indexer again.
  Expected: the PING task goes QUEUED → DONE after the indexer restarts (`psql … -c "select state from task"`), web stays UP throughout.
- [ ] **Step 6: Commit** `feat(web): bootable web app with schema check and admin task enqueue`

### Task 6: Frontend shell

**Files:**
- Create: `frontend/` (Vite React-TS template): `package.json`, `vite.config.ts`, `postcss.config.cjs`, `tsconfig*.json`, `index.html`, `src/main.tsx`, `src/App.tsx`, `src/theme.ts`, `src/components/Brand.tsx`, `src/test/setup.ts`, `src/App.test.tsx`, `src/theme.test.ts`
- Create (copied from `docs/brand/`): `frontend/public/favicon.svg` (= `garrulus-mark.svg`), `frontend/src/assets/garrulus-mark.svg`, `frontend/src/assets/garrulus-mark-dark.svg`

**Interfaces:**
- Produces:
  - A dev server on `:5173` proxying `/api`, `/oauth2`, `/login` and `/feeds` to `http://localhost:8080`.
  - Versions exactly as Blixa's `frontend/package.json`: Mantine 7.17.8, React 18.3, TanStack Query 5, Vite 6, vitest 3, msw 2.
  - `theme` (Mantine `createTheme`) exactly as in `docs/brand/README.md`, *Mantine theme*:
    - `primaryColor: 'jay'`, `primaryShade {light: 6, dark: 6}`
    - colours `jay`, `fawn`, `dark` (= ink)
    - `defaultColorScheme="auto"`
  - `<Brand/>`: the mark (`garrulus-mark.svg` in light mode, `garrulus-mark-dark.svg` in dark mode, `alt=""`) next to the wordmark "Garrulus" in Georgia serif italic, weight 600.
  - `index.html` `<title>Garrulus</title>`, favicon `/favicon.svg`.

- [ ] **Step 1: Failing tests**
  - `App.test.tsx`:
    - renders `<App/>` inside `MantineProvider theme={theme}` + `QueryClientProvider`
    - MSW handler `GET /api/ping → "pong"`
    - expects the text `API: pong` and the heading text `Garrulus`
  - `theme.test.ts`:
    - expects `theme.primaryColor === 'jay'`
    - expects `theme.colors.jay[6] === '#2A6FBA'`, `theme.colors.fawn[0] === '#FAF4F1'` and `theme.colors.dark[7] === '#201E1D'`
- [ ] **Step 2: Run** `cd frontend && npm test` → FAIL.
- [ ] **Step 3: Implement** `theme.ts`, `Brand` and `App`. `App` is a Mantine `AppShell` whose header holds `<Brand/>`; the body shows `API: <ping result>` via TanStack Query. `main.tsx` wraps `App` in `MantineProvider theme={theme} defaultColorScheme="auto"`.
- [ ] **Step 4: Run** `npm test && npm run build` → PASS, `dist/` built.
- [ ] **Step 5: Commit** `feat(frontend): vite/react/mantine shell`

### Task 7: CI build and developer docs

**Files:**
- Create: `Jenkinsfile`
- Modify: `CLAUDE.md` (Commands section), `README.md`

**Interfaces:**
- Produces: Jenkins stage `Build` running in parallel `mvn -B -DskipITs clean verify` (tools `Maven 3.9.9`, `LibericaJDK25` as in Blixa) and the frontend build in `node:24` Docker exactly like Blixa's `Frontend SPA` stage. No deploy stage yet (added in M2 with the two systemd units `col-nametracker-web` / `col-nametracker-indexer`).

- [ ] **Step 1: Write `Jenkinsfile`** (copy Blixa's structure, build stage only, artifacts `indexer/target/*.jar`, `web/target/*.jar`, `frontend/dist/**` archived).
- [ ] **Step 2: Fill `CLAUDE.md` Commands:** `sdk env`; `docker compose up -d db gnfinder`; `mvn -B verify`; single IT `mvn -B -pl <module> -am verify -Dit.test=<Class>`; run apps `mvn -pl indexer spring-boot:run`, `mvn -pl web spring-boot:run`; frontend `npm run dev|test|build`; ports 5434/8778/8070/8090/8080/8081/5173.
- [ ] **Step 3: Full verification**
  Run: `mvn -B clean verify && (cd frontend && npm test)`
  Expected: all unit tests and ITs green.
- [ ] **Step 4: Commit** `chore: jenkins build and developer docs`
