# Frontend redesign: design system — design

Date: 2026-11-17. Status: draft for Markus' review. Branch `frontend-redesign` (worktree
`.claude/worktrees/frontend-redesign`), merged back to main when done.

## Intent

Garrulus gets a new visual identity and a proper design system that every page is built on. The change is about the
look: colours, typography, layout and the logo. The pages and what they show stay as they are, apart from small
changes to labels and arrangement.

The current look (jay blue, fawn, the feather logo, Georgia italic headings, Mantine defaults) goes. The new one is
black and white with a little red, after Markus' references: the Archive Books site and its 2015 "Books News" sheet,
the European Review of Books and, for the logo, the Corax AI and Corax Software marks.

## Decided

All by Markus on 2026-11-17, from mockups shown in the browser:

1. **Colour:** black and white with a bit of red. A black-and-white logo only. No jay blue, no fawn, no feather.
2. **Direction B ("Archive")** of three: one grotesk for everything, black rules, outline pills, a large intro text
   beside the list, red only where something needs attention. "The clean look of B is most appealing."
3. **Typeface: Schibsted Grotesk**, shipped with the app (open licence), over Instrument Sans, Hanken Grotesk and
   system fonts.
4. **Logo: a jay head in bold swept shapes**, variant "3m": a solid head with a swept-back crest, a pale eye cut out,
   two cuts that run the crest feathers on into the head, and a third cut that draws the moustache stripe running on
   from the bill. ("From the looks I also prefer 3. The moustache was nice to have though.")
5. **Approach 1:** restyle Mantine on top of our own CSS tokens (see Approaches).
6. **API additions in this branch** (see API additions): rank, usage rank and act types for the main names in lists,
   volume/issue/pages in lists, and the title's name positions for every work.

## Goals

1. A design system in code: tokens (colour, type, space, rules) as CSS variables, a Mantine theme mapped onto them,
   and a small set of Garrulus components for what Mantine lacks.
2. Every page of the SPA rebuilt on it, light and dark.
3. A living specimen page at `/design` that shows the tokens and every component in both schemes.
4. The new logo as final SVG files, the favicon, and `docs/brand/README.md` rewritten as the brand reference.
5. Everything else users see that carries the old brand is updated: the digest email.
6. The public API serves what the lists and titles need for the italics rule and act markers (API additions).

## Non-goals

- New features, new pages or new data on the pages.
- Upgrading Mantine (stays 7.17), React or the router.
- The portal-components "Literature" widget. The tokens are kept portable so it can use them later.

## Anti-goals

- A look that reads as generic Mantine or generic SaaS: cards, shadows, grey fills, rounded boxes everywhere.
- Colour beyond black, white, one grey and one red. No green for success, no amber for warnings.
- Any request to a third party for fonts or assets (no Google Fonts at runtime).
- Wrong italics on a science site: a family name in italic or a genus in roman.

## Constraints

- Brand rules from CLAUDE.md stay in force with new content: colours come only from `docs/brand/`, the public name is
  Garrulus.
- Free by default: the font is open licence and self-hosted.
- WCAG 2.1 AA: 4.5:1 for text, visible keyboard focus, `prefers-reduced-motion` respected.
- The 97 existing frontend tests keep passing, adjusted only where they assert old colours or old markup.
- The other session works on main in the Java modules. This branch's Java changes stay small and additive (the API
  additions and the digest mail), and conflicts with main are resolved at merge time.

## Approaches

1. **Restyle Mantine on top of our own tokens (picked).** `tokens.css` holds the design decisions as CSS variables; the
   Mantine theme maps onto them and sets component defaults; a few Garrulus components cover what Mantine lacks. Pages
   keep Mantine for menus, modals, the combobox and pagination, with their keyboard and screen-reader behaviour. Least
   churn, most tests keep working, tokens stay portable to portal-components.
2. **Own component kit without Mantine.** Full control and a smaller bundle, but menu, modal, combobox and pagination
   behaviour would be rebuilt by hand, and every page and most tests rewritten. Much work for little visible gain,
   since B is mostly type, rules and whitespace.
3. **Headless library (Radix or React Aria) plus own CSS.** Full visual control without rebuilding accessibility, but
   it replaces the whole UI dependency and touches every page.

Markus picked 1 on 2026-11-17.

## The design system

### Colour

Tokens (CSS variables on `:root`, redefined for dark):

| Token | Light | Dark | Use |
|---|---|---|---|
| `--gr-paper` | `#FFFFFF` | `#000000` | page background |
| `--gr-ink` | `#000000` | `#FFFFFF` | text, rules, logo, outlines |
| `--gr-grey` | `#6E6E6E` (5.1:1 on white) | `#9A9A9A` (7.4:1 on black) | secondary text: meta lines, counts, hints |
| `--gr-rule-soft` | `#D6D6D6` | `#3A3A3A` | the rare minor separator inside dense blocks (tables), never between list items |
| `--gr-red` | `#D2381C` (4.9:1 on white) | `#E8553A` (5.8:1 on black) | attention, see below |

**Red means "look here", and only that:** new names (sp. nov., comb. nov. and other acts), the current place (active
nav item, a chosen filter, the current page in pagination), errors, and the keyboard focus ring. Red is never a fill
behind text and never decoration. Success messages are plain ink. Links are ink with an underline (see Type).

The dark scheme is the light one inverted: black paper, white ink. Its values above are my call; Markus saw only the
light scheme.

### Type

- **Family:** Schibsted Grotesk, self-hosted through `@fontsource/schibsted-grotesk` (5.3.x), weights 400 and 500,
  upright and italic, Latin and Latin Extended subsets (authors such as Pražák, place names, diacritics in names). The
  fallback stack is `'Helvetica Neue', Arial, sans-serif`.
- **Figures:** proportional figures everywhere. Schibsted's tabular figures look like a typewriter's (seen in the
  typeface mockup), so counts are right-aligned instead of tabular.
- **Scale** (px, line-height):

| Token | Size | Line | Weight | Use |
|---|---|---|---|---|
| `--gr-type-display` | 132 (88 below 768 px, 56 below 480) | 0.9, tracking −0.045em | 500 | the big wordmark on the search page |
| `--gr-type-title` | 40 | 1.1, −0.02em | 500 | page title (taxon, name, work, journal) |
| `--gr-type-lede` | 22 | 1.3, −0.01em | 400 | intro text in the side column, the abstract's first lines |
| `--gr-type-head` | 22 | 1.25, −0.01em | 500 | list item titles, section headings |
| `--gr-type-body` | 16 | 1.5 | 400 | running text, article full text |
| `--gr-type-ui` | 15 | 1.4 | 400 | nav, filters, names lists, buttons |
| `--gr-type-meta` | 14 | 1.4 | 400 | authors, journal, date, text level (grey) |
| `--gr-type-small` | 13 | 1.4 | 400/500 | facet group headings, footnotes, header line |

- **Scientific names:** genus and below are set in italic; family and above in roman; authorship and
  qualifiers ("sp. nov.", "cf.", "Bleeker, 1860") in roman. This is a rule of the system, implemented once in
  `sciNameStyle()` and used by the `SciName` component (below), by `NameMark` in article text, and by the digest mail.
- **When the rank is unknown** (my call; in the dev data 12,753 of 68,195 uninomials are `UNRANKED`, OTU codes have no
  rank), the style is decided in this order:
  1. The name's own rank.
  2. The COL usage rank (`usageRank`) where the name is placed.
  3. The name's shape. Binomials and trinomials are italic. A uninomial with a family-group or higher suffix is roman
     (-idae, -inae, -ini, -oidea, -aceae, -oideae, -eae, -ales, -ineae, -mycetes, -mycota, -phyta, -opsida,
     -phyceae). Any other uninomial is roman, because roman makes no claim.
  4. OTU codes (BOLD BINs, UNITE SHs) are always roman.
- **Act markers** ("sp. nov.", "comb. nov.", "stat. rev." …) follow the name in red, roman, same size.
- **Links:** ink, underlined only on hover and in running text; the current place is underlined in red (2px,
  offset 6px in the nav, 4px in filter lists). No blue anywhere.
- No all-caps labels and no letter-spaced capitals. Sentence case throughout.

### Shape, rules and space

- **Rules:** 1px ink rules carry the structure: above and below the main nav, between list items, above each facet
  group and the side column's intro. No boxes around content, no cards, no shadows, no grey fills.
- **Corners:** buttons, inputs, the search field, chips and the segmented control are pills (radius 999). Everything
  else is square (radius 0): menus, modals, popovers, alerts, tables.
- **Buttons:** outline pill in ink is the default; the one primary action of a page or dialog is a filled pill (ink
  background, paper text); destructive actions are outline pills in red. Hover inverts the outline pill.
- **Space** (px): 4, 8, 12, 16, 24, 32, 48, 64 as `--gr-space-1` … `--gr-space-8`.
- **Focus:** a 2px red outline with a 2px offset on every focusable element (Mantine's focus ring replaced).
- **Motion:** none beyond Mantine's menu and modal transitions, which are off under `prefers-reduced-motion`.

### Layout

- **Width:** content up to 1240 px, side gutters 40 px (16 px on phones).
- **Two columns:** a main column and a 340 px side column on the right with a 56 px gap, from 1024 px up. Below that
  the side column moves above the main column on search-like pages (filters behind a "Filters" pill that opens them in
  place) and below it on detail pages.
- **Header:** two forms.
  - *Big* (search page `/` only): a thin top line (the tagline "Names in the literature of biodiversity science" left,
    account right), the mark and the display wordmark, then the nav between two rules with the search field on the
    right, as in the B mockup.
  - *Compact* (all other pages): mark (38 px) and wordmark (30 px) left, nav and account right, one rule below.
  - Below 768 px the nav collapses into a menu (as today's `SiteMenu`).
- **Footer:** one rule, then a single line: "Garrulus, part of the Catalogue of Life", About, API, feeds. My call;
  today there is no footer.

### Logo

- **Mark:** variant 3m, geometry in the appendix. One colour, `currentColor`; black on paper, white on black.
- **Lockups:** mark left of the wordmark "Garrulus" set in Schibsted Grotesk 500. Compact: mark 1.27× the
  wordmark's size; big: mark 96 px beside the 92–132 px wordmark, bottoms aligned.
- **Sizes:** at 16 px the moustache cut disappears and the favicon shows the silhouette with the eye. A separate 16 px
  drawing is made only if the final pass shows the 16 px render blurring.
- **Files** (`docs/brand/`, copied to `frontend/src/assets/` and `frontend/public/` as needed):
  `garrulus-mark.svg` (currentColor), `garrulus-mark-black.svg`, `garrulus-mark-white.svg`,
  `garrulus-lockup.svg` (mark and wordmark as outlined paths, for use outside the app), `favicon.svg` (black mark on
  white that swaps to white on black through a `prefers-color-scheme` media query inside the SVG).
- **Final pass:** the draft geometry gets one careful pass on its curves (crest tips, the cut ends at the crest
  notches, the throat line) before the files are written. The cuts may stay as an SVG mask or be merged into one
  compound path; either is fine as long as the files render the same in Chrome, Firefox and Safari and as a favicon.
- The old files go: `garrulus-mark*.svg` (feather) and `garrulus-jay*.svg` (illustration). Empty states and error
  pages use the large mark instead of the jay illustration.

### Components

**Mantine, restyled through the theme** (`theme.ts`: colours mapped to tokens, `fontFamily`, `headings`, `radius`,
`defaultRadius: 0`, `primaryColor` an ink scale, and `components` overrides for default props and classes):
Button, ActionIcon, TextInput, NativeSelect, Select, Checkbox, Radio, Switch, SegmentedControl, Pagination, Menu,
Modal, Alert, Badge, Anchor, Loader, Skeleton, Burger, Collapse, CloseButton, Divider, Title, Text, AppShell. Each
gets its look from the tokens; the specimen page shows each in every state (default, hover, focus, active, disabled,
error).

- Checkbox and Radio: square/round ink outline, ink fill when checked, red focus ring.
- Alert: no fill; a 1px rule above and below, title in 500; red title for errors, ink otherwise.
- Badge: replaced in pages by plain text or `ActMarker`; kept themed for the few places that need a pill label.
- Loader: a thin ink bar or the text "Loading…"; Skeleton: rules at the height of the content, no grey blocks.

**Garrulus components** (`frontend/src/ui/`):

| Component | What it does |
|---|---|
| `SciName` | renders a name with italics by rank (genus and below italic), optional link and `ActMarker` |
| `ActMarker` | "sp. nov." etc. in red after a name |
| `PageHeader` | the big and compact headers |
| `Rule` | the 1px ink rule (and the soft variant) |
| `SideColumn` | the right column with its lede and facet groups |
| `FacetGroup` | a titled list of filter values with counts, current value underlined in red |
| `WorkItem` | one work in a list: title (names in italic), meta line in grey, main names with act markers |
| `MetaLine` | the grey line of authors, journal, volume and pages, date, text level |
| `NameMark` | a found name in article text: italic by the rank rule (each `MentionSpan.nameId` joined to `WorkDetail.names[].rank`/`usageRank` on the client, no API change), a 1px ink underline, red underline when it carries an act; links to the name. Other italics of the source (e.g. "et al.") are not reproduced: the API does not serve block styles, and that stays so (my call) |
| `EmptyState` | the large mark, one sentence and an action |
| `Specimen` | the `/design` page |

`WorkList`, `HighlightedText`, `FilterSidebar`, `Brand`, `SiteMenu`, `Section`, `YearBars` and `ClassificationPath`
move onto these. `YearBars` draws its bars in ink, the selected range in red.

## Pages

Every page gets the compact header and footer and its content restyled with the system. Arrangement changes beyond
that:

| Page | Changes |
|---|---|
| Search `/` | big header; results left, side column right with the lede ("Garrulus reads N journals of biodiversity science every day and lists the scientific names it finds, placed in the Catalogue of Life.") and the facet groups; Follow and feed as outline pills under the lede |
| Taxon, Name, Source | title in `--gr-type-title`, stats and follow in the side column, works list left |
| Work | title in `--gr-type-title` with names in italic (from `titleMentions`), meta line, then the text with `NameMark`; names, placements and the verbatim classification in the side column |
| Journals | a ruled list, counts right-aligned |
| About | lede-sized intro, then body text; the large mark |
| Sign in and the email pages | one narrow column, one filled primary pill |
| Account, Following, Hits | ruled lists; destructive actions as red outline pills |
| Not found | `EmptyState` (today a bare "Page not found") |

Label changes are made where a page is touched, if the new layout needs them, and listed in the commit.

## Outside the SPA

- `docs/brand/README.md` rewritten: name, logo and its files, colour tokens and roles with the contrast table,
  typeface, the italics rule, red's single meaning. The jay plumage palette is removed.
- `CLAUDE.md`: the "Brand" line updated (no longer "jay colour palette").
- **Digest mail** (`web/.../digest/DigestComposer.java`): ink, grey and red instead of jay and fawn; the wordmark
  "Garrulus" in a system grotesk stack (`'Helvetica Neue', Arial, sans-serif`, 500), since mail clients don't load web
  fonts; links ink and underlined; rules `#D6D6D6`. Today it wraps every name in `<i>`, family names included; it
  applies the italics rule instead, by name shape (step 3), because hit snapshots carry no rank. The same goes for the
  Hits page. Its tests updated.
- Atom feeds and the OpenAPI page carry no brand colours and stay as they are.

## Code structure

```
frontend/src/
  styles/tokens.css        all --gr-* variables, light and dark
  styles/global.css        font-face imports, body, links, focus ring, name italics
  theme.ts                 Mantine theme mapped onto the tokens
  ui/                      the Garrulus components and their tests
  pages/DesignPage.tsx     the specimen page at /design (always on; it shows no data)
  assets/garrulus-mark.svg the new mark
```

The colour scheme follows the system (`defaultColorScheme="auto"` as today), with Mantine's scheme attribute driving
the token switch.

## Testing and verification

- Unit tests for `SciName` (italics by rank), `ActMarker`, `FacetGroup` (current value marked), `NameMark` (offsets in
  code points, as `HighlightedText` today).
- A token test that checks every text/background pair in the colour table against 4.5:1 (computed, not hard-coded).
- The existing 97 tests pass; `theme.test.ts` is rewritten for the new tokens.
- `npm run build` clean (TypeScript).
- Visual check with Playwright screenshots of every page in light and dark at 1440, 1024, 768 and 390 px against the
  dev data (Vite on 5174 with the dev-run proxy to 18080), plus the `/design` page. If this branch changes `web`, its
  own `web` runs on 28080 against the same dev database with `NAMETRACKER_DIGEST_CRON=-` (so it never sends digests
  twice) and no `ADMIN_TOKEN`, and Vite on 5174 proxies there instead. Main's dev run on 18080/18081 is never touched.
- Keyboard pass: tab through the search page, a menu, a modal and the taxon picker; the focus ring is visible
  everywhere.
- `mvn -B -pl web -am verify -Dtest=DigestComposer*` for the mail.

## API additions

Decided by Markus on 2026-11-17: made in this branch.

1. **What the lists and titles need.** The B mockup needs data that the public API doesn't serve today:
   - **Main names in lists:** `WorkSummary.mainNames` has no `rank`, `usageRank` or `actTypes`. Without them a list
     can't apply the rank rule (only the name-shape fallback) and can't mark a name as new (only the work as a whole).
   - **Volume and pages in lists:** `WorkSummary` has no volume, issue or pages, so the meta line would read "ZooKeys,
     16 Nov 2026" instead of "ZooKeys 1297: 311–337, 16 Nov 2026".
   - **Names in titles:** lists have no name positions in the title. On the work page the title's mention spans come
     only with the full text, which is served for retained works only (19,412 of 47,049 in the dev data). For the
     others the title would show no italics. A `titleMentions` field on both the summary and the detail fixes that.
     It is built from the stored title-block mentions and located in `work.title` by their verbatim text, since
     offsets in a JATS title need not line up with the cleaned `work.title`.
   - **Not in scope either way:** rank in hit snapshots, which the indexer writes. The Hits page and the digest use the
     name-shape fallback.

   These are small, additive changes in `web` and `store`: the summary and detail SQL, the DTOs, `openapi.yaml`
   (`OpenApiCoverageTest`), and their tests. The frontend types in `api.ts` follow. Nothing needs a migration.

## Left to the builder

- Exact Mantine override mechanics (theme `components` with `classNames` and CSS modules versus `vars`).
- Static or variable font package, as long as only the four styles ship.
- Breakpoint values within the ranges given; responsive details of each page.
- The footer's exact links.
- Whether the logo cuts stay a mask or become one compound path.

## Appendix A: logo geometry (draft 3m, viewBox 0 0 100 100)

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
  <defs>
    <mask id="cuts" maskUnits="userSpaceOnUse" x="0" y="0" width="100" height="100">
      <rect width="100" height="100" fill="#fff"/>
      <circle cx="61" cy="41" r="4.6" fill="#000"/>                                          <!-- eye -->
      <path fill="#000" d="M 26 28 C 40 29.5 49 40 48 62 C 45 45 38 35.5 27 32.5 Z"/>         <!-- crest cut 1 -->
      <path fill="#000" d="M 28 42.5 C 36 48 39.5 60 38 82 C 36 66 33 55 27 47 Z"/>           <!-- crest cut 2 -->
      <path fill="#000" d="M 72 52 C 63 55.5 55 63 49 73.5 C 56.5 65.5 64.5 60.5 74 57.5 Z"/> <!-- moustache cut -->
    </mask>
  </defs>
  <path fill="currentColor" mask="url(#cuts)" d="M 97 52 C 90 47.5 82 45 74 44.5 C 71 35 63 28 52 26
    C 40 23 24 16 5 15 C 17 21 24 26 28 30 C 19 30 12 33 6 39 C 16 39 24 41 30 45 C 22 57 23 74 36 95
    C 42 84 50 74 60 67 C 66 63 72 60.5 78 59 C 85 57.5 91 55.5 97 52 Z"/>
</svg>
```

## Appendix B: the B mockup in numbers

Header top line 13 px; wordmark 132 px, 500, tracking −0.045em, 30 px above and 26 px below; nav 16 px between 1px
rules, 11 px vertical padding, 28 px gaps, search pill 340 px wide on the right; body grid `1fr 340px`, gap 56 px,
30 px below the nav; count line 14 px grey; list item: rule above, 18/22 px padding, title 22 px 500 −0.01em, meta
14 px grey, names 15 px with 18 px gaps; side column: lede 22 px/1.3 under a rule, follow pill 15 px with 6/16 px
padding, facet group heading 13 px 500 under a rule with 30 px above, facet rows 15 px with grey counts right.
