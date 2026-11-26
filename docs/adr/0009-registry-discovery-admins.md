# ADR-0009: Journal registry, discovery, registration, admins

Date: 2026-11-20. Status: accepted.

## Context

M6a opened more doors for works. M6b decides which journals are followed at all: a registry with one row per journal,
automatic discovery of candidates, user registration of missing journals, an admin role with admin pages, candidate
sampling, ceased journals and a discipline vocabulary. The design is
`docs/superpowers/specs/2026-11-19-m6-layered-harvesting-design.md` (M6b sections); this ADR records what was built and
the rulings made on the way, which override the spec and the plan where they differ. Configuration, task types and
endpoints are in CLAUDE.md and are not repeated here. The dev check is `docs/m6b-dev-check.md`. M6c follows.

Binding constraints: **free only**, every handler idempotent, every outage defers without using an attempt (ADR-0004),
**nothing a user registers or discovery finds is harvested before an admin promotes it** (a discovered journal is a
`CANDIDATE`), expand-only migrations: **one migration, V19** (plus the fix wave's V20, below).

## Decisions

### V19 and the registry

V19 adds, in one expand-only file: the source's publication status (`publication_status`, `ceased_reason`,
`last_issue_year`, `continued_by`, `status_set_by`, `status_changed`), dormancy (`dormant_*`), `candidate_score`, the
discipline columns, DOAJ's `doaj_discontinued` and `successor_issns`, and the tables `source_signal`, `issn_link`,
`pkp_beacon`, `discovery_title`, `discipline`, `discipline_mapping`, `source_classification_raw`, `source_sample`,
`source_registration`, `admin_notice`, plus `source_route.long_restarts`. The origin check gains `DOAJ`, `PKP_BEACON`,
`PLAZI`, `COL_REFERENCES`, `BLR`, `REGISTERED`.

**`CandidateRegistry` is the only door** for a journal that is not already a followed one: discovery, registration and
the seeding offer a candidate and never insert a `source` row themselves.

- **Matching order:** any ISSN, then the ISSN-L (own `issn_link` table, filled by `REFRESH_ISSNL`), then the OpenAlex
  id; the first hit is the existing source and the offer adds its signal.
- **Only evidence widens an identity** (fix wave): an offer whose ISSNs come from Crossref, OpenAlex or DOAJ (DOAJ
  discovery, a Plazi or COL title resolved by Crossref; completion and seeding write directly) unions its new ISSNs into
  a live existing source and folds what then shares a key. Every other offer (PKP Beacon, COL ISSN groups, BLR, a
  registration) is **signal-only** on an existing source: self-declared ISSNs typed by an OJS manager or found on a
  page are no identity evidence, and one wrong cell must not widen a followed journal or cascade into folds. A new
  candidate may still start from them; `IDENTIFY_SOURCE` then confirms it from the services.
- **Placeholder ISSNs:** `Issn.normalise` rejects `0000-0000` (it passes the check digit; forms and OJS use it for
  "none") through a small blocklist, like an invalid ISSN.
- An offer with no valid ISSN and no OpenAlex id is rejected with a WARN. Without the ISSN-L table the registry matches
  by plain ISSN and OpenAlex id; print/e-ISSN pairs then meet only through `IDENTIFY_SOURCE`.
- **Locks:** one transaction takes `pg_advisory_xact_lock` on **all identity keys** (every ISSN, every known ISSN-L, the
  OpenAlex id) **sorted** (`IdentityLocks`), so two offers with overlapping keys serialise and cannot both create a
  row. A lost deadlock (40P01) defers the task by a minute without an attempt.
- **Fold:** after every identity write inside the locked transaction, any other source sharing an ISSN, the ISSN-L or
  the OpenAlex id is a duplicate. `SourceFold` runs **to a fixpoint** (the survivor's grown keys are scanned again).
  **The loser is chosen by foldability, not by age** (fix wave; it was always younger into older, so a followed
  journal younger than its candidate duplicate never folded): only a `CANDIDATE` without works, without an `ACTIVE`
  route, with no admin curation (`tier_set_by` or `discipline_set_by` = ADMIN) and without a different OpenAlex id can
  fold. A candidate therefore folds into a followed, curated or EXCLUDED partner whatever the ids; of two foldable
  candidates the younger folds into the older; when neither can fold, WARN and both stay (a held OpenAlex id is then
  not written, nor are the ISSNs of that OpenAlex answer, which would cement two sources sharing them). ISSNs, OpenAlex
  id, signals, routes, raw rows, samples, registrations and follows move; the loser is deleted. A fold into a
  CORE/BROAD survivor verifies the moved PENDING routes. The holder of an OpenAlex id is re-read after its keys are
  locked. An `ACTIVE` route counts against folding only once it harvested (`last_ok` or `onboarded_at`). The fold's
  delete only removes a candidate without admin curation, so a concurrent admin promotion wins (the fold rolls back; the
  admin PATCH locks the row in one transaction). A registration that waited as a candidate and lands on an EXCLUDED
  survivor counts as a direct hit on it: no "declined" mail, since no admin decided anything.
- **Seeding and `ADD_SOURCE`** write through the same sequence (`SeedUpsert`, fix wave): one transaction under the
  identity locks finds the source by its OpenAlex id, else adopts an ISSN-only source found through its ISSNs, upserts
  the row, writes the tier (an admin's for `ADD_SOURCE`) and folds the duplicates. An admin adding a journal that
  discovery or a user registered gets one source, and the registration is covered.
- **Deadlocks:** only SQLState 40P01 in the cause chain defers (a minute, no attempt); a lock timeout or another
  pessimistic failure uses attempts like any error. The completion stops when its source was folded into a ceased
  journal (ceased journals get no calls).
- **ISSN-L table off by default.** `REFRESH_ISSNL` streams the ISSN IC zip; its URL is the undocumented file the "on
  demand" form serves. `garrulus.discovery.issnl.enabled` is **false** until Markus confirms the URL may be used.
- **Free-text ISSN extraction** (`Issn.all`) accepts only the **hyphenated** form (hyphen or en dash) anywhere, and
  space, NBSP or glued forms only directly after an ISSN **label** (ISSN, ISSN-L, eISSN, pISSN, e-ISSN, p-ISSN, optional
  colon); never after `/` or `.` (DOI suffixes, page ranges, dates). Structured fields go through `Issn.normalise`.

### V20 (fix wave)

Expand-only: stored ISSNs are upper case in the hyphenated form (seeding stores OpenAlex's ISSNs through `Issn.stored`;
V20 back-fills existing rows, idempotently), so `findByIssn` compares `issn_l = x OR issns @> ARRAY[x]` and uses
`source_issn_l_idx` and the GIN `source_issns_idx` (it was a sequential scan through `upper()` over `unnest`). V20 also
indexes the foreign keys V19 added: `source_registration (source_id)` (every fold's move, `ON DELETE SET NULL`, the
admin list) and `source (continued_by)` (partial, the FK check of every fold's delete). M6c's migrations start at V21.

### Identification (`IDENTIFY_SOURCE`)

Queued by the registry for every new candidate; completes the source step by step, each step written at once:
ISSN-L from `issn_link`, Crossref `/journals/{issn}`, OpenAlex (a spent budget skips the step), DOAJ, a PKP Beacon
OAI route hint, then the Europe PMC probe and the derived disciplines. **DOAJ is the `in_doaj` authority** while its
switch is on (OpenAlex's flag is then ignored); DOAJ's pissn/eissn are unioned into the identity, so a shared ISSN
folds duplicates. A CEASED or deleted source is skipped.

### Discovery origins

`DISCOVER_SOURCES` per origin, monthly (`DiscoveryScheduler`), each with its switch; all offers at `BACKFILL`; start
values to tune after the first real month:

- **DOAJ** by LCC subject codes (QL, QK, QH1-278.5, QR, QH540-549.5, QE701-760; about 600 journals; Biology (General)
  is left out), one page of 100 per task.
- **PKP Beacon** table (CC0) loaded into `pkp_beacon` in one transaction; offered when the context name passes the
  keyword filter (include/exclude terms at word starts, accent-folded) and the endpoint is alive (`records2025 >= 5` or
  a response within 365 days), with a PENDING OAI route hint. The hint also reaches a journal the registry already
  holds (fix wave): its OAI route is added PENDING/DISCOVERED unless it has an OAI route with that base and set, and
  `VERIFY_ROUTE` runs at once only when the journal is followed. A registration's route hint never reaches a known
  journal.
- **Plazi** treatment statistics for three years, summed per journal label, at least 3 articles. **COL references**
  grouped by ISSN, else by container-title key, at least 10 references (the 2000 biggest title groups). Titles go to
  `discovery_title`; phase 2 asks Crossref `/journals?query=`.
- **Ambiguous-title rule:** two or more exact-key Crossref hits with **different ISSN sets** leave the title
  unresolved (`resolved_at` set, no ISSNs). The spec forbids attaching evidence to the wrong journal; the plan's
  "first item" was dropped. A resolved or unresolved title is looked at again after 180 days.
- **BLR**: `layer_unmatched` DOIs of the last 12 months by ISSN, at least 2 DOIs; `lastSeen` is dated evidence of life.
- **Seeding** (`SEED_TOPIC`) now writes a signal, adopts an ISSN-only candidate (sets its OpenAlex id) instead of
  adding a row and never re-tiers a CEASED source.
- A per-row failure of an offer is logged (WARN with ISSN and title) and counted; outages and deadlock deferrals
  propagate. The snapshot backfill still imports works of ceased journals.

### Signals

`source_signal (source, origin, signal_key)` records why a journal is a candidate, with a JSON detail. **No user data
in a signal** except for `REGISTERED`, whose key is the registration id and whose detail is the registration id only;
the user's note and name are read from the registration when an admin opens the journal. A withdrawn registration
deletes its signal.

### Registration

`POST /api/sources/register` needs a **sign-in** (session and XSRF), exactly one of ISSN, DOI (at most 300 characters)
or URL, a note of at most 500, a body of at most 8 KB (413), **at most 20 a day** per user (429) and, fix wave, **at
most 200 a day of all users together** (`garrulus.registration.daily-cap`, 429 "paused for today"): email accounts
are free and each registration is a `LIVE` task with up to a 60 s page fetch ahead of the harvest. The global count is
not locked, so parallel requests can pass it by a few. `web` makes no
outbound call: it stores the row `IDENTIFYING`, enqueues `IDENTIFY_SOURCE {"registrationId"}` at `LIVE` and answers 202.
Outcomes `NEW_CANDIDATE`, `EXISTING`, `CEASED`; reasons for no result: `DOI_UNKNOWN`, `ADDRESS_NOT_PUBLIC`,
`ADDRESS_UNKNOWN`, `PAGE_UNREACHABLE`, `NO_ISSN_FOUND` and **`LOOKUP_FAILED`** (any non-outage, non-retryable lookup
error ends `NOT_IDENTIFIED`, never stuck `IDENTIFYING`; outages defer).

- **A page offers at most 4 ISSNs.** When they hit more than one source or a non-CANDIDATE, the offer only **adds the
  signal** to the best match: **no identity widening and no fold** from a user-supplied page. A hit on a single
  candidate is an ordinary offer, which is signal-only on an existing source too (fix wave). `IDENTIFY_SOURCE` widens
  an identity only from Crossref, OpenAlex or DOAJ evidence.
- Deleting an account deletes its registrations (cascade) and, fix wave, their `REGISTERED` signals in the same
  transaction, as a withdrawal does: they no longer count as an origin in the score or propose a revival.
- An account **merge moves registrations** to the survivor before the loser is deleted; `/api/me/export` includes
  registrations with their notes. The user's status is read from the holder's current tier: `COVERED`, `CANDIDATE`,
  `DECLINED`, `CEASED`. Mails: once when a registered candidate becomes CORE/BROAD (accepted) or EXCLUDED (declined),
  never for withdrawn ones; a daily admin note (`REGISTRATIONS_WAITING`) lists the registrations still waiting, with
  the typed address and links in page titles defanged (`hxxps://`).

### SSRF design

A user-supplied URL and the hosts of discovered routes are hostile input. Layers of defence:

- **`PublicHost`** (store) is the rule: a host must resolve only to public addresses, on the ports 80, 443, 8080, 8443
  for registered addresses. Private, loopback, link-local, metadata, `::/96`, and the IANA special-purpose IPv4 ranges
  (198.18/15, 192.0.0/24, 240/4, 0/8) are refused. **NAT64 (64:ff9b::/96) and IPv4-mapped addresses are judged by their
  embedded IPv4** (refused when that is private, metadata or special); refusing the whole NAT64 prefix would block every
  fetch on a DNS64 network. `web` applies the same rule to admin-added routes.
- **`JournalPage`** (the registration fetch) uses its own Apache HttpClient with the DNS answer **pinned to the checked
  addresses** (no rebinding), follows at most 3 redirects by hand with every hop checked, bounds status and header
  lines (8 KB, 100 headers), accepts `text/html` only, at most 2 MB, one **60 s deadline for the whole exchange**, no
  retries. The page is scanned linearly (`PageMarkup`: first 512 KB, tags at most 4 KB, 2 s budget; else
  `LOOKUP_FAILED`), no backtracking regex on hostile input; the title is capped at 500 code points.
- **`PoliteHttp`** follows redirects itself (`Redirect.NEVER` client; at most 5, never https to http) and checks the
  start URL and **every hop with `PublicHost`**. **Trusted authorities** (exempt from the check) are exactly the
  scheme/host/port of the configured service URLs PoliteHttp fetches, plus the entries of `garrulus.http.trusted-hosts`;
  **never `garrulus.public-url`**, gnfinder or a finder service. Trust applies **only while every hop so far was
  trusted**: a public site that redirects to `http://localhost:<port>/` is refused with no request sent. Production uses
  `host:port` in `garrulus.http.trusted-hosts`; a bare host trusts every port.
- **The route gate:** `VERIFY_ROUTE` is not enqueued for a route whose source is a CANDIDATE (origin `REGISTERED` or
  `DISCOVERED`), so a hostile route host is never contacted before an admin promotes the journal; a promotion (tier to
  CORE/BROAD) and a fold into a CORE/BROAD survivor enqueue `VERIFY_ROUTE` for the PENDING routes. The Europe PMC
  probe gives a candidate's route VERIFIED, never ACTIVE (fix wave). The journal page asks before an admin activates a
  PENDING or BROKEN route (that skips `VERIFY_ROUTE`).
- **Unresolved names** (fix wave): `PoliteHttp` looks a name up once more after 2 s, in its check and at connect,
  before it reports an unknown host, so a resolver hiccup no longer breaks a route. A followed journal without an
  OpenAlex id that loses its last usable route before onboarding gets `ONBOARD_SOURCE` again at once.

### Admins and notices

- **Admin role only from an ORCID sign-in** of an iD in `GARRULUS_ADMIN_ORCIDS` (ADR-0006, amendment M6b), re-checked
  on every request; the token (`X-Admin-Token`) or an `ADMIN` session with XSRF for writes opens `/api/admin/**`.
- **`admin_notice` is the mail queue.** The indexer writes a row with a dedup key (layer failing, still
  failing after 7 days, recovered, OAI restart cap, registrations waiting); `web` claims unmailed rows (`mailed_at` set
  at the claim, at most 20 a run) and mails every verified address of an account whose ORCID iD is an admin. With no
  such address or no `spring.mail.host` the outcome is `SUPPRESSED` and is logged. This realises the spec's "web sends
  from rows not mailed yet" as notices so layer mail, the route restart cap and the registration note share one path.
  A claim without an outcome after the job's lock time (10 minutes) is claimed again (fix wave): a crash between claim
  and send no longer loses the mail (a crash after the send may repeat it). A coverage check of a day older than the
  layer's latest notice writes no notice.
- **Zero-yield rule by weekday:** a layer's zero-yield day counts for FAILING only on a day with a `DONE` run whose
  expected yield (median of that weekday over 8 weeks) is at least 3.0; quiet days are transparent and a day without
  a DONE run breaks the streak; 3 such days make FAILING; the yield-ratio rule applies from a weekly median of 14.
  Start values.
- **OAI restart cap 3:** an ONBOARDING or BACKFILL OAI pass that loses its resumption token again restarts a day later
  at most 3 times (`source_route.long_restarts`, reset by the route's last page and a passing `VERIFY_ROUTE`); the next
  loss makes the route BROKEN and writes a `ROUTE_RESTART_CAP` notice.

### Disciplines

A vocabulary `discipline` (9 codes: the OpenAlex subfields the seeding keeps, equal to `subfields.ts`) with
**crosswalks** `discipline_mapping` (OpenAlex subfield, DOAJ LCC, taxonomic group) and `replaced_by`: a retired code is
never written, a follow or filter with one resolves to its replacement. `DisciplineDeriver`: the first rule that yields
codes wins: OpenAlex subfields (share at least 0.05), DOAJ LCC (weight 0.5), then the taxonomic focus (share 0.20);
raw classifications are kept in `source_classification_raw`. **Admin override:** disciplines set by an admin
(`discipline_set_by` = ADMIN) are never touched; `releaseDisciplines` returns to the derivation. The public API adds
`/api/vocab/discipline` and `discipline` as an alias of the `subfield` filter.

### Sampling and the candidate score

`SAMPLE_SOURCE` reads the newest articles of a candidate through the finder and CLB matching (the COL dictionary
carries no classification) and stores **one aggregate row** in `source_sample`; it **never writes `work`**, mentions,
name strings or matches. Routes in order, the first with at least 5 articles of 2 years wins: Crossref by ISSN, an
**OAI route that is VERIFIED or ACTIVE only** (an unverified host is never fetched), Europe PMC, OpenAlex (budget). It
runs **once per candidate while a task is live** (the nightly selection excludes a source with a QUEUED or RUNNING
`SAMPLE_SOURCE`), skips a source sampled within **365 days** (re-sample interval) unless the payload says `force` (the
admin button), 200 a night, registered first, then never sampled, then by score.

The **candidate score** (0 to 1): `0.7 * sample + 0.3 * signals` with a sample of at least 5 articles, else
`0.5 * signals`; the signal part is the sum of repeated signals (distinct origins, Plazi articles and COL references
summed over signal keys, a registration), through a log and a cap. Weights are start values (properties).

### Ceased journals

`publication_status` ACTIVE or CEASED (reason, last issue year, successor), set by an admin or proposed from evidence.
A CEASED source is **skipped everywhere**: no layer job, no harvest, no sampling, no identification; a batch trimmed of
a ceased source while a cursor is in flight **restarts with cursor `*`** and the trimmed ids (works are upserts). Follows
of a ceased journal show a note and the successor. **Dormancy proposals** (coverage check) only for active CORE/BROAD
journals with ACTIVE status, in one indexed pass per source; an admin can dismiss. **Revival proposals:** a CEASED
journal with a **dated** sign of life newer than its status change (BLR `lastSeen`, or a REGISTERED signal); an
**EXCLUDED** one **only through a registration** of the last 90 days (a user asking again); **no PKP-only revival**
(Beacon rows are not dated against the status change). Proposals are shown and never applied.

### Crossref-only promotion

A journal without an OpenAlex id and without a PENDING, VERIFIED or ACTIVE route that an admin promotes starts a
120-day Crossref `ONBOARDING` pass (key `onboard-crossref:<id>`), whose last page onboards it; so a Crossref-only
journal is harvested without OpenAlex.

## Consequences / known limits

- **The ISSN-L URL is undocumented** and the feature is off by default; without it print/e-ISSN pairs meet only via
  `IDENTIFY_SOURCE`. A **theoretical race** remains during the monthly table refresh (an offer looks up the ISSN-L
  before locking).
- **DNS rebinding** between `PublicHost`'s check and the JDK client's connect is possible for `PoliteHttp` (JournalPage
  pins the address). A URL equal to a configured local service authority (a dev setup) is fetched with trust, also for
  a route URL.
- **Beacon titles in many languages** pass the English and Romance keyword filter only partly; the admin view and the
  sample judge the rest.
- **COL references without container title or ISSN** are skipped.
- **About 12,000 CLB calls a night** at 200 samples (up to 60 names per candidate, unthrottled and uncached).
- **A notice before any admin exists** is `SUPPRESSED` and not mailed later; a crash after a send but before its
  outcome is written mails it again.
- Many weights, terms and thresholds are start values (above) to tune after the first real month.
