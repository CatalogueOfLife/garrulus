# M4 dev check

The M4 finish line: a test user receives a correct digest, no hit is for a paper outside its follow's alert window, and
no follow gets the same paper twice (the "no backfill hits" of the roadmap was replaced by the alert window, Markus
2026-11-16; ADR-0006). This file records the switch of the dev run to the M4 code and the check on 2026-11-16.

## Build and restart (2026-11-16, 12:10 UTC)

- At `9478313`: a full `mvn -B verify` passed before the final-review fix wave at `1b28448` (1,021 tests, 0 failures).
  After it, `mvn -B -pl store,indexer,web -am verify` and `cd frontend && npm test && npm run build` (73 tests) passed.
- `mvn -B -DskipTests clean install`, jars copied to `data/run/`, old logs kept as `indexer-9.log` and `web-6.log`.
- Mailpit: `docker compose --profile mail up -d mailpit` (SMTP 1025, UI 8025).
- **Indexer** restarted with the same environment as before. Flyway applied V6 and V7 (accounts and alerts) in 5 s.
- **Web** restarted on 18080 with `NAMETRACKER_PUBLIC_URL=http://localhost:5173`, `SPRING_MAIL_HOST=localhost`,
  `SPRING_MAIL_PORT=1025`. ORCID is not configured yet, and `NAMETRACKER_SIGNING_KEY` is blank: web logged the
  expected WARN and uses a random key per start.
- **SPA**: Vite from a scratch config (`frontend/vite.devrun.config.ts`, not committed) on 5173, proxying `/api`,
  `/oauth2`, `/login` and `/feeds` to 18080.

## Sign-in and follows

- **Email link** through the Vite proxy as `devcheck@example.org`. The sign-in link arrived in Mailpit, and
  `POST /login/ott` with the token answered 200. `GET /api/me` showed the new account (id 1) with a verified address
  and `digest_since` set.
- **Follows**, created at 12:11:21 UTC:
  1. Carabidae `8KTL7` (TAXON, default window);
  2. Zootaxa (SOURCE 1);
  3. Lepidoptera (NAME 100);
  4. Coleoptera `C2L` with `sinceYear` 2020 (TAXON).
- **Onboarding test**: a follow of *Journal of Fish Biology* (SOURCE 36) at 12:11:41, then the journal was promoted
  from CANDIDATE to CORE through the admin API. Its onboarding indexed 130 papers published 2026-07-19 to 2026-10-29
  by 12:18 UTC. No task became DEAD, and both logs hold no ERROR.

## Checks (SQL on the dev database, 12:19 UTC)

| Check | Result |
|---|---|
| Hits per follow | Journal of Fish Biology 100, Coleoptera since 2020 1, Lepidoptera 1 (all ARTICLE) |
| Hits created before their follow, or for a work indexed before it | 0 |
| Hits outside their follow's window | 0 |
| (subscription, work) pairs with more than one ARTICLE hit | 0 |
| Fish Biology papers older than 90 days / with a hit | 30 / 0 |
| Fish Biology papers inside the window, indexed after the follow / with a hit | 100 / 100 |

The onboarded journal's older papers produced no hit for the default-window follow, and every paper inside the window
did. Carabidae and Zootaxa got no hit yet: no paper of theirs was indexed after the follows (the next live harvest is
the nightly run).

Hit quality, by hand:
- **Lepidoptera**: a brook trout paper (work 36231) that mentions Lepidoptera (diet), not as a main subject. That is
  correct for a NAME follow, which takes every mention outside the references.
- **Coleoptera since 2020**: a silver carp paper (work 36211). The finder took "Spurgeon", an author name, as a
  uninomial, CLB matched it to a beetle genus, and salience made it a main subject. This is a finder false positive
  of the kind listed in `docs/m3-dev-check.md` (author names, capitalised words), for the finder-tuning stage. The hit
  logic itself is right.

## Digest

`POST /api/admin/digests/run {"userId":1}` answered `{"sent":true,"hits":102}`. Mailpit received one mail:
- **Subject**: "Garrulus: 100 new papers". It counts distinct papers; the Coleoptera and Lepidoptera hits are papers
  of the journal too.
- **Headers**: `List-Unsubscribe: <http://localhost:5173/api/unsubscribe?t=…>` and
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
- **Grouping**: by follow, in label order. The Journal of Fish Biology group lists 20 papers, then "and 80 more" with
  a link to `/hits?subscription=5`. Papers with nomenclatural acts come first, for example *Dysommina rugosa* (sp. nov.)
  and four *Chiloglanis* sp. nov. Each paper shows at most 10 names, acts and main subjects first, then "+N more".
  Each paper has its Garrulus link and its DOI.
- **Footer**: digest settings and unsubscribe links.
- **Database**: one `digest` row with status SENT and 102 hits; all 102 hits are marked delivered.

A second admin run answered `{"sent":false,"hits":0}`, and no mail was sent.

**Unsubscribe.**
- `GET /api/unsubscribe?t=…` answered 302 to the SPA page `/unsubscribe?t=…` and changed nothing.
- `POST` with `List-Unsubscribe=One-Click` answered 200, and set `digest_frequency` to OFF.
- Switching back to DAILY through `PATCH /api/me` (time zone Europe/Berlin) moved `digest_since` to that moment, so
  no hit from before the switch can be mailed.

## Headers and feeds (through the Vite proxy)

- `GET /api/works` carries no Cache-Control, Pragma or Set-Cookie. `GET /api/me` carries `Cache-Control: no-store`.
- `/feeds/taxon/8KTL7.atom`: `application/atom+xml`, `Cache-Control: public, max-age=900`, recent Carabidae papers.
- Private feed `/feeds/s/{token}.atom`: `Cache-Control: private, no-store`, `X-Robots-Tag: noindex`,
  `Referrer-Policy: no-referrer`.

## Open

- **ORCID sign-in**: the client is configured (the existing ORCID public client, with the redirect URI
  `http://127.0.0.1:5173/login/oauth2/code/orcid`; production ORCID rejected `http://localhost`, and ORCID allows one
  public client per person). Vite's string proxy shorthand rewrote the `Host` header to `localhost:18080`, so the
  redirect URI pointed at web; the proxy now uses `changeOrigin: false` and Vite listens on 127.0.0.1. Web restarted at
  13:00 UTC with `NAMETRACKER_PUBLIC_URL=http://127.0.0.1:5173`; `/oauth2/authorization/orcid` now redirects to
  orcid.org with `redirect_uri=http://127.0.0.1:5173/login/oauth2/code/orcid`. **Checked 2026-11-17:** Markus signed in
  with ORCID at `http://127.0.0.1:5173/signin`; it works.
- **The scheduled digest**: the test account now has a daily digest at 07:00 Europe/Berlin. The first scheduled mail,
  with the nightly harvest's Carabidae and Zootaxa papers if there are any, goes to Mailpit on 2026-11-17.

**Verdict:** met. Sign-in by email link and by ORCID works, the digest is correct, there is no hit outside a window, and
no paper reached a follow twice.
