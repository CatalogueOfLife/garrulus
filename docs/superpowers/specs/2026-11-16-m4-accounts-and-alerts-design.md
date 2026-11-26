# M4 Accounts & alerts: design

Status: draft, 2026-11-16. Implements the M4 row of the roadmap in `docs/design.md` (§7 Subscriptions and
notifications, §8 Accounts, the `me/*` and `/feeds` parts of §9). Where this document is more specific than
`docs/design.md`, this document wins; decisions are recorded in a new ADR-0006 during the build.

## Intent

Researchers want to hear about new papers on the organisms they work on without reading 2,000 journals. Garrulus
already finds, places and indexes the names of each new paper (M3). M4 lets a person **follow** a COL taxon (with
everything below it), a journal, a name or a taxonomic group, and get the new papers as an **email digest**, an **Atom
feed** or a **"My hits" page**. Papers that name a new taxon in a followed group are flagged, because those are the
hits that matter most to a taxonomist. The uBioRSS idea (public feeds per taxon, journal and name, no account needed)
comes back with it.

**Done when:** a test user receives a correct digest; no hit is for a paper outside its follow's alert window, and no
follow gets the same paper twice. (The roadmap said "no backfill hits". Markus changed that on 2026-11-16: alerts may
also come for older papers that enter the index or are found or placed better later, such as a newly followed and
indexed journal or an improved finder, within an alert window that defaults to 90 days and can start at a chosen year.)

## Goals

- Sign in with ORCID or with an emailed one-time link. Open sign-up, no approval step.
- Follow `TAXON`, `SOURCE`, `NAME` and `TAXGROUP`, each with options and the search filters.
- A hit evaluator in the indexer that records hits exactly once per (subscription, work, kind).
- Daily or weekly digests in the user's time zone, one-click unsubscribe.
- Public Atom feeds per taxon, journal, name and taxonomic group; a private Atom feed per subscription.
- Account settings, data export and account deletion (GDPR).

## Non-goals (M4)

- `AUTHOR` (ORCID) subscriptions and saved searches (later, design §7).
- Admin screens in the UI and admins named by ORCID. The admin token stays the only admin access.
- Instant (per-hit) emails, push, webhooks. Digests and feeds only.
- XR switching: subscriptions to taxa that disappear from a new XR are handled with re-matching in M5.
- Finder and salience tuning (final stage). M4 uses whatever `main_subject` the index holds.
- **Deployment** (Markus, 2026-11-16: "keep it all local until the end"). M4 is done in the local dev run with Mailpit.
  The deploy repo, the GBIF SMTP relay, forwarded-header handling behind Apache (real host and client IP for OAuth
  redirects and rate limits) and proxy cache rules for `/api/me`, `/feeds/s/*` and cookie-setting responses are a
  later deployment stage. M4 only keeps them possible: everything is configured by environment variables.

## Anti-goals (failure even if it works)

- **Alerting outside the window or twice:** a hit for a paper published before the follow's alert window, or a second
  `ARTICLE` hit for the same paper and follow after a re-index, a rescore or (later) an XR or finder upgrade.
- **Noise:** hits from reference-list mentions, or every *E. coli* mention for a taxon follower.
- **A flood after a pause:** turning digests back on must not deliver weeks of old hits at once. A big batch of new
  hits (a newly onboarded journal, a backfill inside a long window) is capped per follow in the digest.
- **Spam relay:** the sign-in form must not let anyone make Garrulus mail an address over and over, and it must not
  reveal whether an address has an account.
- **Leaking subscribers:** no email address in logs, feeds, URLs or public API responses.
- **Empty emails:** a digest with no hits is not sent.

## Constraints

- **Two apps, one database, they never call each other.** The indexer writes `hit` rows and `work.first_indexed_at`;
  `web` writes `app_user`, `subscription`, sessions, tokens, digests and the delivery marker on `hit`. One writer per
  column group, as in design §6.
- **Schema in `store`,** Flyway migration V7, expand-only, so a running `web` keeps working while the indexer migrates.
  `web` only validates; every table `web` needs (Spring Session, one-time tokens) is created by V7, never by framework
  auto-initialisation.
- **Free by default.** Email goes through an SMTP relay (GBIF's in production, configured in the `deploy` repo); no
  paid mail service. Without a configured mail host, `web` logs that a mail was suppressed (recipient as user id, never
  the address) and sends nothing.
- **No secrets in the repo.** ORCID client id and secret, SMTP credentials and the session settings come from the
  environment (`.env` locally, EnvironmentFile in production).
- **Public name "Garrulus"** in every email, page and feed; colours from `docs/brand/`.

## Approaches

| Approach | Trade-off | Verdict |
|---|---|---|
| **A. Indexer writes `hit` rows when a work is indexed; `web` delivers digests from them hourly** (design §7, §10) | Hits are decided once, at the moment the paper enters the index; "exactly once" is a unique key; the NEW_NAMES follow-up can compare against the stored hit. Costs a table and an extra task per indexed work. | **Chosen** (Markus, 2026-11-16) |
| B. `web` computes each digest by querying the index at send time | No hit table, but "first indexed after the subscription", exactly-once delivery and the NEW_NAMES comparison all become fragile time-window queries, and `web` grows heavy queries. | Rejected |

Within A, two calls made with Markus on 2026-11-16:

- **Email sign-in uses Spring Security's built-in one-time-token login** (`oneTimeTokenLogin()`, JDBC token service in
  Postgres), not hand-written token code.
- **Sessions live in Postgres (Spring Session JDBC), about 30 days,** so a redeploy of `web` doesn't sign everyone out.
- **ORCID:** a new ORCID public-API client "Garrulus" is registered by Markus (not Blixa's). Its id and secret go into
  `.env` as `ORCID_CLIENT_ID` / `ORCID_CLIENT_SECRET`. The ORCID base URL is configurable (`ORCID_BASE_URL`, default
  `https://orcid.org`; `https://sandbox.orcid.org` for a sandbox client). With no client configured, the ORCID button
  is hidden and email sign-in still works.

## Design

### Data model (V7)

- **`work.first_indexed_at`** (timestamptz): set by the indexer the first time a work becomes `INDEXED`, never changed
  afterwards (`coalesce`). V7 backfills it from `indexed_at`. `indexed_at` keeps its meaning (last indexing).
- **`app_user`**: id, `email` (unique, case-insensitive, nullable until known), `email_verified` timestamp, `orcid`
  (unique, nullable), `display_name`, `time_zone` (IANA id, default `UTC`, the SPA proposes the browser's zone),
  `digest_frequency` (`DAILY` | `WEEKLY` | `OFF`, default `DAILY`), `digest_weekday` (default Monday), `digest_hour`
  (local hour, default 7), `digest_since` (timestamptz: set when the email is first verified and whenever
  `digest_frequency` changes from `OFF` to on; hits created before it are never mailed), created, `last_login`.
  `OFF` is the only way to pause digests.
- **`subscription`**: id, `user_id`, `type` (`TAXON` | `SOURCE` | `NAME` | `TAXGROUP`), `target` (COL id, source id,
  name_string id, or `TaxGroup` name), `label` (display text given by the client, at most 200 characters),
  options (`main_subject_only`, `new_names_only`, `include_abstract_only`, `include_references`), `filters` (jsonb with
  the `/api/works` filter names: `code`, `taxgroup`, `subfield`, `journalType`, `tier`, `oa`, `country`), `feed_token`
  (random, 128 bits, URL-safe), `since_year` (nullable smallint: the alert start year; null means the rolling window
  of the last 90 days, `GARRULUS_HIT_WINDOW_DAYS`), `paused`, created. Unique (user, type, target). Index on
  (type, target).
  - Option defaults: `main_subject_only` true for `TAXON` and `TAXGROUP`, false for `NAME` and `SOURCE`;
    `new_names_only` false; `include_abstract_only` true; `include_references` false.
  - At most 100 subscriptions per user (Markus, 2026-11-16).
- **`hit`**: id, `subscription_id` (cascade), `work_id`, `kind` (`ARTICLE` | `NEW_NAMES`), created, `names` (jsonb
  snapshot of the matching names: name, name_string id, act types, placement, main subject), `digest_id` (null until
  delivered; written by `web`). Unique (subscription, work, kind).
- **`digest`**: id, `user_id`, `sent_at`, `hits` count, `status` (`SENT` | `FAILED`), error.
- **`email_token`**: one-time tokens to verify an address that an ORCID user adds or a user changes to (user id,
  address, hash of the token, expiry 24 hours). Sign-in tokens use Spring's `one_time_tokens` table; unsubscribe
  links are signed instead (below).
- **Spring Session JDBC tables** (`spring_session`, `spring_session_attributes`) and **`one_time_tokens`**, with the
  DDL Spring ships, in V7.

### Hit evaluation (indexer)

- After `WorkIndexer` sets a work `INDEXED`, it enqueues `EVALUATE_HITS` (`{"workId":n}`), a follow-up that inherits
  priority and `run_after` like the others. Every indexing does this: the first one, a re-index when full text arrives,
  and later (M5) a re-find under a new finder version or a re-placement on a new XR, so a paper whose names are found
  or placed better reaches the followers it now matches. `RESCORE_SALIENCE` enqueues it only for works where a name
  became a main subject.
  The work's state stays `INDEXED`: `NOTIFIED` is not used (the public API lists `INDEXED` works only, and a hit is a
  per-subscription fact, not a work state). ADR-0006 records this.
- **Eligibility for an `ARTICLE` hit** (all must hold):
  - the paper is inside the follow's alert window: `coalesce(publication_date, first_seen_at::date)` within the last
    90 days, or, with `since_year`, `coalesce(year, extract(year from first_seen_at))` >= `since_year`; and in both
    cases not more than 7 days in the future;
  - the follow existed before this indexing (`subscription.created < work.indexed_at`), so following never pulls in
    papers already indexed; they come only when they are indexed again;
  - the subscription is not paused;
  - no `ARTICLE` hit exists yet for (subscription, work).
  - Any `work.origin` counts (`LIVE`, `SWEEP`, `ONBOARDING`, `BACKFILL`): the window, not the origin, keeps old papers
    out. With the default window a backfill of past years produces no hits; a follow with `since_year` 2000 will get
    the backfill's papers of its taxon, capped in the digest.
- **Which subscriptions match** a work (one query by candidate keys, then options in Java):
  - `TAXON`: a `work_name` whose `taxon_path` contains the target.
  - `SOURCE`: `work.source_id` = target (every eligible paper of the journal; options and filters narrow it).
  - `NAME`: a `work_name` with that `name_string_id`.
  - `TAXGROUP`: a `work_name` whose `taxgroup` is the target or one of its descendant groups (`TaxGroup` hierarchy).
  - Options: `main_subject_only` keeps only `main_subject` names; `include_references` false drops `only_reference`
    names; `new_names_only` keeps only names with `act_types`; `include_abstract_only` false requires full text.
    For `SOURCE`, name options apply only when set; a `SOURCE` subscription with defaults matches every eligible paper.
  - Filters: the same semantics as `/api/works` (the `WorkFilter` in `store`), applied to the one work.
- **`NEW_NAMES` follow-up:** when a work inside the follow's window is indexed again (full text arrived, a better
  finder), a subscription that already has an `ARTICLE` hit for it and now matches names
  with nomenclatural acts that are not in that hit's `names` snapshot gets one `NEW_NAMES` hit (once per subscription
  and work). "Not in the snapshot" compares (name_string id, act type) pairs: *Carabus xus* in the abstract without a
  marker and `SP_NOV` in the full text is new. The check runs on every re-index inside the window, whatever changed;
  the `NEW_NAMES` hit's own `names` holds only the new pairs, which the digest marks as new names. A subscription without an earlier `ARTICLE` hit simply goes through the `ARTICLE` rules above; a paper is never sent
  twice to the same follow as an `ARTICLE`.
- Idempotent: inserts use the unique key (`ON CONFLICT DO NOTHING`); a redelivered task changes nothing.

### Delivery (web)

- **Digest job:** every hour on the hour (`GARRULUS_DIGEST_CRON`, default `0 5 * * * *`), single instance via
  ShedLock (the `shedlock` table exists). A user is due when their email is verified, `digest_frequency` is not `OFF`,
  their local hour has reached `digest_hour` on a due day (any day for `DAILY`, `digest_weekday` for `WEEKLY`), and no
  digest went out in this period.
- **Content:** undelivered hits of the user's subscriptions created after `digest_since` and at most 14 days old for
  `DAILY`, 21 days for `WEEKLY` (older undelivered hits stay on "My hits" but are never mailed). So turning digests
  back on, or verifying an address weeks after signing up, starts with an empty slate. Grouped by subscription; within each, `NEW_NAMES`
  and papers with acts first, then newest first; at most 20 papers per subscription, then "and N more" linking to
  "My hits". Each paper: title, journal, date, the matching names (new names marked), a link to its Garrulus page and
  to the DOI. Multipart plain text and simple HTML in the brand colours.
- **Sending:** the hits are marked with a new `digest` row in one transaction, then the mail is sent; if sending
  fails, the marking is rolled back and the digest row is kept as `FAILED`, so the next hour tries again.
- **Unsubscribe:** `List-Unsubscribe` with an HTTPS URL and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`
  (RFC 8058). The URL carries an HMAC-signed token (user id, purpose; key from `GARRULUS_SIGNING_KEY`) and sets
  `digest_frequency=OFF` without sign-in. The footer also links to the subscription settings.
- **Mail settings:** `GARRULUS_MAIL_FROM`, Spring's `spring.mail.*` (host, port, credentials). Development uses
  Mailpit (`docker compose --profile mail up -d mailpit`, SMTP 1025, web UI 8025).
- **Admin:** `POST /api/admin/digests/run` (`{"userId":n}`, admin token) sends a due-or-not digest now, for testing.

### Feeds (web)

- Public, no account: `/feeds/taxon/{colId}.atom`, `/feeds/source/{id}.atom`, `/feeds/name/{id}.atom`,
  `/feeds/taxgroup/{group}.atom`. They list the papers a default subscription of that type would match: works of any
  origin published in the last 90 days, newest `first_indexed_at` first, 50 entries. Entry id = the work's
  Garrulus URL, updated = `first_indexed_at`, so a feed reader sees each paper once.
- Private: `/feeds/s/{feedToken}.atom`, the subscription's hits (both kinds), newest first, 50 entries. A new token
  can be generated from the subscription settings.
- Linked from the taxon, journal and name pages with the standard feed icon.

### Accounts and sign-in (web)

- **Spring Security** is added to `web`. Public `GET /api/**` (everything that exists today) stays open; `/api/me/**`
  needs a session; `/api/admin/**` keeps the admin token. CSRF via a cookie token for the SPA (Blixa's
  `CsrfCookieFilter` / `SpaCsrfTokenRequestHandler`). Session cookie HttpOnly, SameSite=Lax, Secure in production.
- **Email link:** `POST /api/auth/link` with an address. The response is the same whether or not the address has an
  account. Rate limits: 3 links per address per 15 minutes and 20 per client IP per hour (in memory; `web` runs as one
  instance). The link opens the SPA page `/signin/verify?token=…`, which posts the token to Spring's one-time-token
  login. Token valid 15 minutes, single use. The first successful sign-in creates the account with a verified email.
- **ORCID:** OIDC as in Blixa (`OrcidUserService`), scope `openid`. ORCID does not return an email, so a new ORCID
  account has none; the SPA asks for one and sends a verification link (`VERIFY_EMAIL`). When the verified address
  already belongs to another account (signed up by email), the ORCID iD is attached to that account, the new account's
  subscriptions move over, and the new account is deleted.
- **Address changes and merges** (my calls, 2026-11-16). Every new address, for any account, is confirmed by a
  `VERIFY_EMAIL` link before it is stored, and nothing is said about the address until the link is used. Whoever
  uses the link has proved they own the address, so the result may then say what happened:
  - address unused: it becomes the account's address;
  - address on an account without ORCID, or with the same ORCID iD: the two accounts merge into the one that holds the
    address (the older account keeps its id);
  - address on an account linked to a *different* ORCID iD: refused, with a message saying so; nothing changes.
  - In a merge, a duplicate (type, target) subscription keeps the older row (its `created` and options) and the
    other's hits move to it where they don't clash; the 100 cap applies to creating subscriptions, not to merges.
- **Public base URL** for links in emails and feeds: `GARRULUS_PUBLIC_URL` (dev `http://localhost:5173`).
- **Development:** the Vite dev server already proxies `/api`, `/oauth2`, `/login` and `/feeds` to `web` and keeps the
  `Host` header, so sign-in is same-origin as in production. The ORCID client therefore needs the redirect URI
  `http://localhost:5173/login/oauth2/code/orcid` (Vite's port) in addition to the production ones. For the dev run
  (`web` on 18080) the scratch Vite config proxies to 18080 and also listens on 5173, so the same redirect URI works;
  `GARRULUS_PUBLIC_URL=http://localhost:5173`. If production ORCID refuses an http localhost redirect, a sandbox
  client is used for dev (`ORCID_BASE_URL=https://sandbox.orcid.org`).

### API (web, `/api/me`, session required)

`GET /api/me` (401 when signed out), `PATCH /api/me` (display name, time zone, digest settings), `POST /api/me/email`
(start verification), `DELETE /api/me` (hard delete of the user, subscriptions, hits, digests and sessions),
`GET /api/me/export` (JSON of all of it), `GET|POST /api/me/subscriptions`, `PATCH|DELETE /api/me/subscriptions/{id}`,
`POST /api/me/subscriptions/{id}/feed-token`, `GET /api/me/hits?limit&offset&subscription` (hits with work summaries).
`GET /api/auth/config` tells the SPA whether ORCID is configured. The `/api/me` and `/api/auth` GETs are added to
`openapi.yaml` (cookie auth), so `OpenApiCoverageTest` keeps covering every GET.

### Frontend

- Header: "Sign in" or an account menu (My hits, Following, Account, Sign out).
- **Sign-in page:** "Continue with ORCID" (when configured) and "Email me a sign-in link"; a "check your inbox" state;
  `/signin/verify` page.
- **Follow button** on the taxon, journal and name pages, and on the search page when exactly one taxonomic group is
  the only filter. It opens a small dialog: the defaults above, "Only new names", "Papers published: in the last 90
  days (default) / since year …", and a collapsed "Narrow down" with the search filters. Signed out, it leads to sign-in and back.
- **Following** page (subscriptions: edit, pause, delete, copy private feed URL), **My hits** page (newest first,
  "New names" badge, filter by subscription), **Account** page (email, time zone, digest frequency, day and hour,
  export, delete with confirmation).

### Done-when check (dev run)

Mailpit running; indexer and `web` restarted with M4; Markus (or a test address) signs in by email link and by
ORCID, follows Carabidae (8KTL7), Zootaxa and one name with the default window, and a second taxon with a start year,
with a daily digest. Then a CORE journal not yet onboarded is promoted (its onboarding indexes about 120 days of older
papers). After that and the next nightly harvest, the digest arrives in Mailpit (or is triggered with the admin
endpoint). Checked and recorded in `docs/m4-dev-check.md`:
- every paper in the digest was indexed after its follow was created, lies inside the follow's window, and is placed
  under the followed taxon (or is in the journal, or names the name);
- the onboarded journal's papers older than 90 days produced no hit for the default-window follows;
- `select subscription_id, work_id, count(*) from hit where kind = 'ARTICLE' group by 1, 2 having count(*) > 1` is
  empty, and no hit lies outside its follow's window (one SQL check each).

## Left to the builder

Package layout in `web` and `indexer`; the Atom library (or hand-written XML); the HTML mail template; the exact
query shape of the candidate lookup; the rate limiter; the merge transaction; test fixtures (Mailpit or GreenMail in
ITs, a stub OIDC provider for ORCID ITs).
