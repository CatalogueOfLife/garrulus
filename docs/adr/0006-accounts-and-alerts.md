# ADR-0006: Accounts and alerts — hits, window, sign-in, digests, feeds

Date: 2026-11-16. Status: accepted.

## Context

M4 lets a person follow a COL taxon, a journal, a name or a taxonomic group and hear about new papers by email digest,
Atom feed or a "My hits" page. The design is `docs/superpowers/specs/2026-11-16-m4-accounts-and-alerts-design.md`;
this ADR records the calls that shape behaviour, including those made while building it. Everything runs locally with
Mailpit; deployment is a later stage.

## Decisions

### The indexer decides hits once; `web` delivers them

- After a work becomes `INDEXED`, the indexer enqueues `EVALUATE_HITS` (`workId`), a follow-up that inherits priority and
  `run_after`. `RescoreHandler` enqueues it in the same transaction as its write, and only for works where a name became
  a main subject. `hit` rows are unique per (subscription, work, kind) and inserted with `ON CONFLICT DO NOTHING`, so a
  redelivered task changes nothing. `work.first_indexed_at` is set once (`coalesce`).
- `web` writes accounts, subscriptions, sessions, tokens, digests and `hit.digest_id`. The two apps never call each other.
- `EVALUATE_HITS` skips works that are not `INDEXED` and calls the work filter only for non-empty filters, with
  `mainSubjectOnly=false` (the subscription's name options are applied in Java). Candidate lookups, name strings and
  the digest's delivery marking bind each id list as one array (`= ANY(?)`), so a checklist paper with many names needs
  no chunking; only the digest's work summaries are loaded in chunks of 1000.

### Hit rules and alert window

- **Window** (Markus, 2026-11-16, replacing "no backfill hits"): a paper is eligible when its date
  (`coalesce(publication_date, first_seen_at::date)`) is at most `nametracker.hits.window-days` (90) days old, inclusive,
  or, for a follow with `since_year`, its year (`coalesce(year, year of first_seen_at)`) is at least `since_year`. In both
  cases it is at most `future-days` (7) ahead. In the rolling window a paper without a publication date but with a
  `year` before the year of the window start is outside, even when it was first seen today (an old paper harvested now
  is not news); the public feeds apply the same guard.
- **Any `work.origin` counts.** The window, not the origin, keeps old papers out: an onboarded journal's older papers
  give no hits to default-window follows; a follow with an early `since_year` gets the backfill's papers of its taxon
  (capped in the digest).
- **Following never pulls in papers that are already indexed:** the subscription must exist before the work's
  `indexed_at`. They come only when the paper is indexed again (full text arrives, a better finder).
- Paused subscriptions get no hits. One `ARTICLE` hit per (subscription, work), however often the work is re-indexed.
- **`NEW_NAMES`:** when a re-indexed work inside the window has an `ARTICLE` hit and now has (name, act type) pairs
  missing from that hit's `names` snapshot, the subscription gets one `NEW_NAMES` hit holding only the new pairs.
- Matching: `TAXON` by `taxon_path`, `SOURCE` by journal, `NAME` by name string, `TAXGROUP` by the group or a descendant.
  Options (`main_subject_only`, `new_names_only`, `include_abstract_only`, `include_references`) and the `/api/works`
  filters apply on top; a `SOURCE` follow with defaults matches every eligible paper. At most 100 subscriptions per
  user, labels at most 200 characters.
- **`NOTIFIED` is not used.** The public API lists `INDEXED` works only and a hit is a fact per subscription, not a state
  of the work.

### Sign-in: ORCID or an emailed one-time link, sessions in Postgres

- **Email link:** Spring Security's one-time-token login with the JDBC token service (table `one_time_tokens`, created by
  V7). `POST /api/auth/link` answers the same whatever the address, so it reveals no account. Limits: 3 links per
  address per 15 minutes and 20 per client IP per hour, in memory (`web` is one instance; per-IP limits behind a proxy
  need forwarded headers, a deployment task). The token is valid 15 minutes and single use. A custom authentication
  provider returns the `GarrulusPrincipal` (the user id), so an email-named principal never stays in a session.
- **Confirm buttons:** the SPA pages `/signin/verify` and `/verify-email` show an explicit confirm button and post only on
  click. A foreign link therefore cannot silently sign someone in (login CSRF). The pages fetch the XSRF cookie first.
- **ORCID:** OIDC with scope `openid`, offered only when `ORCID_CLIENT_ID` is set. ORCID gives no email, so a new ORCID
  account asks for one. Garrulus never calls ORCID after the login, so no authorized client (access token) is kept.
- **Time zone:** a new account starts on `UTC` (the server cannot know better). `GET /api/me` returns `created`; on the
  first signed-in load of an account created in the last 10 minutes that is still on `UTC`, the SPA sets the browser's
  zone once (a `localStorage` flag per user id keeps it from being repeated).
- `/verify-email` opened while signed out sends the visitor to sign in first, with `next` pointing back to the link.
- **`next`:** the page to return to after sign-in is kept in `localStorage` for 1 hour, not `sessionStorage`, because an
  email link opens in a new tab. When the user turns signed-in and a fresh `next` exists, the SPA navigates there once
  and clears it.
- **Sessions:** Spring Session JDBC, about 30 days, so a redeploy does not sign anyone out. The tables come from V7
  (`spring.session.jdbc.initialize-schema=never`). The cookie is HttpOnly, SameSite=Lax, Secure when
  `NAMETRACKER_COOKIE_SECURE=true`.
- `DELETE /api/me` hard-deletes the user, subscriptions, hits, digests and the user's sessions. Unused sign-in tokens
  have no foreign key and expire within 15 minutes; the hourly job purges expired token rows.

### Address changes and the merge policy

- Every new address is confirmed by a `VERIFY_EMAIL` link (24 hours) before it is stored; nothing is said about the
  address until the link is used.
- Address unused: it becomes the account's address. Address held by an account without ORCID or with the same ORCID
  iD: the accounts merge into the holder (the older id survives). In a merge a duplicate (type, target) subscription keeps
  the older row and the other's hits move over where they do not clash; the 100 cap does not apply to merges.
- Address held by an account with a different ORCID iD, **or** a requester without ORCID verifying an address held by an
  ORCID account: refused (`REFUSED_OTHER_ORCID`). The safe side; that person can sign in to the holder by email link.
- An address taken by another account between the lookup and the update is a `409 {"status":409,"error":"conflict"}`;
  the change is rolled back and the token stays usable. The log names the user id and the exception class only (the
  database's message holds the address).

### Digests

- The hourly job (`nametracker.digest.cron`, default `0 5 * * * *`, UTC, `-` switches it off) runs on one instance
  under ShedLock. A user is due when the email is verified, the frequency is not `OFF`, the local hour (user time zone)
  has reached `digest_hour` on a due day (any day for `DAILY`, `digest_weekday` for `WEEKLY`) and none went out in the
  period.
- Content: undelivered hits created after `digest_since` and at most 14 days (`DAILY`) or 21 days (`WEEKLY`) old; older
  ones stay on "My hits" and are never mailed, so turning digests back on starts empty. At most 20 papers per
  subscription, then "and N more". New names and papers with acts come first. A digest with no hits is not sent.
- Names per paper: the hit snapshots keep every matching name (hundreds for a checklist in a followed journal); only
  the composition is cut. Names with acts come first, then main-subject names, then the rest; at most 10, then
  "+N more". A name with acts carries their labels (sp. nov., gen. nov., subgen. nov., ssp. nov., comb. nov.,
  stat. nov., stat. rev., syn. nov., nom. nov., type designation, higher taxon nov.) rather than a generic "new". The
  private feed and the "My hits" page do the same; a `NEW_NAMES` hit keeps its "New names" badge.
- **At-most-once delivery by design:** the hits are marked with a `digest` row in one committed transaction, then the
  mail is sent. A send that fails (or is suppressed) clears the marks and keeps the row as `FAILED`, so the next hour
  tries again; a crash between the commit and the send loses that digest rather than sending it twice (the hits stay on
  "My hits"). Mail connect, read and write timeouts are set so a hung mail host cannot block the job.
- Without `spring.mail.host` nothing is sent; the log says "mail suppressed" with the user id, never the address.

### Unsubscribe

Each digest carries `List-Unsubscribe` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (RFC 8058). The URL holds
an HMAC-signed token (user id, purpose) keyed by `NAMETRACKER_SIGNING_KEY` and sets `digest_frequency` to `OFF`
without sign-in. `POST /api/unsubscribe` does it; a browser `GET` redirects to the SPA page `/unsubscribe?t=`. A blank
key means a random key per start with a WARN, so links stop working after a restart; set it outside development.

### Feeds

Public Atom feeds `/feeds/{taxon,source,name,taxgroup}/…atom` list the papers a default follow would match (any origin,
published within the window, newest `first_indexed_at` first, 50 entries, entry id = the work's Garrulus URL). The
private feed `/feeds/s/{token}.atom` lists a subscription's hits, one entry per hit: entry id
`<public URL>/work/{id}#hit-{hitId}` and `updated` = the hit's `created`, so a `NEW_NAMES` hit of a paper already in the
feed is a new entry that readers do not merge away; the feed's `updated` is the newest hit. Its token has 128 bits and
can be regenerated ("New feed URL" on the Following page; the old URL stops working). It sends
`Cache-Control: private, no-store`, `X-Robots-Tag: noindex` and `Referrer-Policy: no-referrer`. Public feeds send
`Cache-Control: public, max-age=900`. DOI links are percent-encoded. Addresses never appear in feeds.

### CSRF scope and cache headers

- The `XSRF-TOKEN` cookie is issued only on `/api/me/**`, `/api/auth/**` and `/login/**`. The CSRF check applies to every
  non-GET request except `/api/admin/**` (admin token, which remains the only admin access), `/api/unsubscribe`
  (signed token) and `/api/auth/link` (no session yet, rate limited).
- The public `GET /api/**` and `/feeds/**` responses set no cookie and no cache-busting headers: Spring Security's
  default `Cache-Control: no-cache, no-store, …`, `Pragma` and `Expires` are switched off, so they stay cacheable for the
  portal and proxies. `/api/me/**`, `/api/auth/**`, `/login/**` and `/api/admin/**` send `Cache-Control: no-store`;
  the feeds set their own (above).

### Deployment is out of M4

Markus, 2026-11-16: keep it all local until the end. M4 is built and checked in the local dev run with Mailpit. The
deploy repo, the GBIF SMTP relay, forwarded headers behind Apache (real host and client IP for OAuth redirects and the
per-IP limit) and proxy cache rules for `/api/me`, `/feeds/s/*` and cookie-setting responses are a later stage.
Everything is configured by environment variables so that stays possible.

## Consequences

- A hit is a stored fact: changing the window or a subscription later does not rewrite old hits.
- A crash between marking and sending loses that digest's mail (the hits stay on "My hits").
- Purging expired tokens and the in-memory rate limiter assume one `web` instance.
