# Frontend redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A black, white and red design system for Garrulus (tokens, Mantine theme, Garrulus components, logo), every
page of the SPA rebuilt on it, the brand docs and digest mail updated, and the API additions the lists and titles need.

**Architecture:** `styles/tokens.css` holds every design decision as `--gr-*` CSS variables (light, and dark under
`[data-mantine-color-scheme='dark']`). The Mantine theme and a `cssVariablesResolver` map Mantine onto those variables;
Garrulus components in `frontend/src/ui/` cover names, lists, facets and headers. The API gains rank, usage rank and
act types on list names, volume/issue/pages on summaries, and `titleMentions` on summaries and details.

**Tech Stack:** React 18, Mantine 7.17, Vite 6, Vitest + MSW, `@fontsource/schibsted-grotesk` 5.3.x; Spring Boot,
MyBatis, Postgres 17 (Testcontainers ITs) for the API part.

**Spec:** `docs/superpowers/specs/2026-11-17-frontend-redesign-design.md` (read it first; the plan argues from it).

## Global Constraints

- Work in the worktree `/Users/markus/code/col/nametracker/.claude/worktrees/frontend-redesign`, branch `frontend-redesign`. Never touch main's checkout, `data/run/`, or the dev run on 18080/18081.
- Colours: only `#FFFFFF`, `#000000`, `#6E6E6E`, `#D6D6D6`, `#D2381C` (light) and `#000000`, `#FFFFFF`, `#9A9A9A`, `#3A3A3A`, `#E8553A` (dark), through `--gr-paper`, `--gr-ink`, `--gr-grey`, `--gr-rule-soft`, `--gr-red`. No other colour literal in `frontend/src` outside `styles/tokens.css` (the `YearBars`/`TaxGroupIcon` CSS included).
- Red means only: new names (act markers), the current place, errors, the focus ring. Never a fill behind text, never decoration.
- Typeface: Schibsted Grotesk 400/500, upright and italic, self-hosted via `@fontsource/schibsted-grotesk`; fallback `'Helvetica Neue', Arial, sans-serif`. No runtime request to a third party.
- No all-caps labels, no letter-spaced capitals, sentence case. No cards, shadows or grey fills. Pills (radius 999) for buttons, inputs, chips, segmented control; radius 0 for everything else.
- Italics: genus and below italic; family and above roman; unknown rank → usage rank → shape (binomial/trinomial italic, uninomial roman); OTU always roman. Ranks arrive in either case (`SPECIES`, `genus`); compare case-insensitively.
- Public name "Garrulus" in everything users see. No mention of Claude in commits.
- WCAG 2.1 AA: text ≥ 4.5:1, visible 2px red focus ring with 2px offset, `prefers-reduced-motion` respected.
- Every new public GET field goes into `web/src/main/resources/static/api/openapi.yaml`.
- Commit after each task on the branch, message style `feat(frontend): …`, `feat(web): …`, `docs: …`.

## Review Focus

1. **A title mention whose verbatim no longer occurs in the (cleaned) title** must be dropped, not misplaced or thrown — pinned in Task 2 (`TitleSpansTest.dropsMentionNotInTitle`).
2. **Characters outside the BMP before a name in a title** (code-point offsets, not UTF-16) must put the italics on the right letters — pinned in Task 2 (`TitleSpansTest.astralCharacterBeforeName`) and Task 6 (`SciTitle` test).
3. **Overlapping title mentions** (a genus inside its binomial) render the outer one once — pinned in Task 2 (`overlappingMentionsKeepFirstLongest`) and Task 6.
4. **Works with no source, no authors, no volume or no date** show a meta line without dangling separators — pinned in Task 7 (`MetaLine` tests).
5. **Long unbroken strings at 390 px** (long DOIs, long genus names, long journal titles) wrap instead of scrolling the page sideways — `overflow-wrap: anywhere` on titles, meta and names (Task 4 global CSS), checked at 390 px in Task 13.

---

### Task 1: API — names, rank and acts, volume and pages in work summaries

**Files:**
- Modify: `store/src/main/java/org/catalogueoflife/nametracker/store/search/MainNameRow.java`, `.../store/search/WorkHit.java`
- Modify: `store/src/main/resources/org/catalogueoflife/nametracker/store/search/SearchMapper.xml` (resultMaps `hit`, `mainName`; selects `search`, `byIds`, `mainNames`)
- Modify: `web/src/main/java/org/catalogueoflife/nametracker/web/api/public_/dto/NameRef.java`, `.../dto/WorkSummary.java`, `.../WorkController.java` (`summarise`, `summary`)
- Modify: `web/src/main/resources/static/api/openapi.yaml` (schemas `NameRef`, `WorkSummary`)
- Modify: `web/src/test/java/org/catalogueoflife/nametracker/web/digest/DigestComposerTest.java` (two `new WorkSummary(` calls)
- Test: `web/src/test/java/org/catalogueoflife/nametracker/web/api/public_/WorkControllerIT.java`

**Interfaces:**
- Produces (Java): `MainNameRow(long workId, long nameStringId, String name, String placementMethod, @Nullable String usageId, @Nullable String kind, @Nullable String rank, @Nullable String usageRank, List<String> actTypes)`; `WorkHit(..., boolean hasActs, @Nullable String volume, @Nullable String issue, @Nullable String pages)`.
- Produces (JSON): `NameRef {id, name, placementMethod, usageId?, kind?, rank?, usageRank?, actTypes[]}`; `WorkSummary {... , volume?, issue?, pages?}` (fields appended; `titleMentions` comes in Task 2).

- [ ] **Step 1: Write the failing IT** `searchSummaryCarriesRankActsAndPages` in `WorkControllerIT`: insert a work, `UPDATE work SET volume='1297', issue='3', pages='311-337'`; a name string with rank `species`; a `work_name` row with `act_types = '{SP_NOV}'`, `usage_id='U1'`; an `xr_release` row `(key 1, alias 'x', current true)` and a `name_match` row `(name_string_id, '', 1, 'EXACT', usage_id 'U1', usage_rank 'species')`. GET `/api/works`. Assert `results[0].volume == "1297"`, `.issue == "3"`, `.pages == "311-337"`, `mainNames[0].rank == "species"`, `.kind == "SCIENTIFIC"`, `.usageRank == "species"`, `.actTypes[0] == "SP_NOV"`. (Check the exact `name_match` columns in `V4__index.sql` and later migrations.)
- [ ] **Step 2: Run** `mvn -B -pl web -am verify -Dit.test=WorkControllerIT` — expect the new test to FAIL (missing fields).
- [ ] **Step 3: Implement.** SQL `search` and `byIds` select `w.volume, w.issue, w.pages`; `mainNames` selects `ns.kind, ns.rank, wn.act_types` and `usage_rank` as a scalar subquery: `(SELECT nm.usage_rank FROM name_match nm JOIN xr_release r ON r.key = nm.release_key AND r.current WHERE wn.usage_id IS NOT NULL AND nm.usage_id = wn.usage_id ORDER BY (nm.name_string_id = wn.name_string_id) DESC, nm.hint_key LIMIT 1)`. `act_types` uses `StringArrayTypeHandler` as in `workNameRow`. `NameRef` gains `@Nullable String kind, @Nullable String rank, @Nullable String usageRank, List<String> actTypes`; `WorkSummary` appends `@Nullable String volume, @Nullable String issue, @Nullable String pages`. Update both `DigestComposerTest` constructions with nulls. OpenAPI: add the fields (`actTypes` required array of string; the rest nullable strings).
- [ ] **Step 4: Run** `mvn -B -pl web -am verify -Dit.test='WorkControllerIT,OpenApiCoverageTest' -Dtest='DigestComposerTest,OpenApiCoverageTest'` — expect PASS.
- [ ] **Step 5: Commit** `feat(web): rank, usage rank and acts of list names; volume and pages in work summaries`.

### Task 2: API — `titleMentions` on summaries and details

**Files:**
- Create: `store/src/main/java/org/catalogueoflife/nametracker/store/search/TitleMentionRow.java`
- Modify: `.../store/search/SearchMapper.java` + `SearchMapper.xml` (new select `titleMentions`)
- Create: `web/src/main/java/org/catalogueoflife/nametracker/web/api/public_/TitleSpans.java`
- Modify: `.../dto/WorkSummary.java`, `.../dto/WorkDetail.java`, `.../WorkController.java`, `openapi.yaml`, `DigestComposerTest.java`
- Test: `web/src/test/java/org/catalogueoflife/nametracker/web/api/public_/TitleSpansTest.java`, `WorkControllerIT.java`

**Interfaces:**
- Consumes: Task 1's `WorkSummary`.
- Produces (Java): `record TitleMentionRow(long workId, long id, long nameStringId, int startCp, int endCp, String verbatim, @Nullable String actType)`; `List<TitleMentionRow> SearchMapper.titleMentions(@Param("ids") Collection<Long> workIds)` — rows of `mention` with `block_type = 'TITLE'`, ordered by `work_id, start_cp, end_cp DESC`; `static List<MentionSpan> TitleSpans.locate(@Nullable String title, List<TitleMentionRow> rows)`.
- Produces (JSON): `WorkSummary.titleMentions: MentionSpan[]` and `WorkDetail.titleMentions: MentionSpan[]` (required, possibly empty), offsets in code points into `title`.

- [ ] **Step 1: Write failing unit tests** in `TitleSpansTest`:
  - `locatesNamesInOrder`: title `"A new species of Hemiphyllodactylus Bleeker, 1860 from Vietnam"`, one row verbatim `Hemiphyllodactylus` → span start 17, end 35.
  - `dropsMentionNotInTitle`: verbatim `Carabus` not in title → empty list.
  - `astralCharacterBeforeName`: title `"𝔄 Abies alba"`, verbatim `Abies alba` → start 2, end 12 (code points).
  - `overlappingMentionsKeepFirstLongest`: rows `Abies alba` (startCp 0) and `Abies` (startCp 0) for title `"Abies alba in Spain"` → one span 0–10.
  - `repeatedNameLocatesEachOccurrence`: title `"Abies and Abies"`, two rows `Abies` → spans 0–5 and 10–15.
  - `nullTitleGivesEmpty`.
- [ ] **Step 2: Run** `mvn -B -pl web -am test -Dtest=TitleSpansTest` — FAIL (class missing).
- [ ] **Step 3: Implement `TitleSpans.locate`.** Walk the rows in order (stored `startCp`, then longest first). Skip a row whose stored `startCp` lies before the stored `endCp` of the last kept row (overlap in the source document). Find the verbatim with `title.indexOf(verbatim, cursorCharIndex)`, skip it when not found, convert char indices to code points with `codePointCount`, and advance the cursor to the span end. Stored offsets only order the rows and detect overlaps; they are never used as offsets into `work.title`.
- [ ] **Step 4: Run** the unit test — PASS.
- [ ] **Step 5: Write the failing IT** `titleMentionsServedForClosedWork` in `WorkControllerIT`: a work titled `"Revision of Syntelia"` with no content row (not retained) and a `mention` row (`block_id 't'`, `block_type 'TITLE'`, `start_cp 12`, `end_cp 20`, `verbatim 'Syntelia'`, other NOT NULL columns as in `IndexMapper.xml` `mentionColumns`). Assert `/api/works` `results[0].titleMentions[0]` has `startCp 12`, `endCp 20`, `nameId` = the name string id, and `/api/works/{id}` `titleMentions` the same, while its `text` is null.
- [ ] **Step 6: Implement.** `summarise` loads `titleMentions(ids)` once per page (like `mainNames`) and passes each work's rows through `TitleSpans.locate(h.title(), rows)`; `get` does the same for one id. Append `List<MentionSpan> titleMentions` to `WorkSummary` and `WorkDetail`; add to OpenAPI (`items: $ref MentionSpan`); fix the `DigestComposerTest` constructors (`List.of()`).
- [ ] **Step 7: Run** `mvn -B -pl web -am verify -Dit.test=WorkControllerIT -Dtest='TitleSpansTest,DigestComposerTest,OpenApiCoverageTest'` — PASS.
- [ ] **Step 8: Commit** `feat(web): title mention spans on work summaries and details`.

### Task 3: Digest mail in the new brand

**Files:**
- Create: `web/src/main/java/org/catalogueoflife/nametracker/web/digest/SciNames.java`
- Modify: `web/src/main/java/org/catalogueoflife/nametracker/web/digest/DigestComposer.java`
- Test: `web/src/test/java/org/catalogueoflife/nametracker/web/digest/SciNamesTest.java`, `DigestComposerTest.java`

**Interfaces:**
- Produces: `static boolean SciNames.italicByShape(String name)` — true for binomials and trinomials (`^[A-Z][a-z-]+( \([A-Z][a-z-]+\))? [a-z-]+( [a-z-]+)?$` after trimming; an infraspecific marker like `subsp.`/`var.` between epithets is allowed), false for uninomials and everything else.

- [ ] **Step 1: Write failing tests.** `SciNamesTest`: `Abies alba` true; `Carabus (Megodontus) violaceus` true; `Poa annua var. supina` true; `Synteliidae` false; `Syntelia` false; `BOLD:AAA1234` false. `DigestComposerTest` `htmlUsesBrandColoursAndNameRule`: compose a digest with names `Abies alba` (act `SP_NOV`) and `Pinaceae`; assert the HTML contains `<i>Abies alba</i>`, contains `Pinaceae` not wrapped in `<i>`, contains `#D2381C` next to `sp. nov.`, and contains none of `#2A6FBA`, `#FAF4F1`, `#E6CDC2`, `#82594A`, `Georgia`.
- [ ] **Step 2: Run** `mvn -B -pl web -am test -Dtest='SciNamesTest,DigestComposerTest'` — FAIL.
- [ ] **Step 3: Implement.** Replace the jay/fawn constants with `INK = "#000000"`, `GREY = "#6E6E6E"`, `RULE = "#D6D6D6"`, `RED = "#D2381C"`. Wordmark: `font-family:'Helvetica Neue',Arial,sans-serif;font-weight:500;font-size:24px;letter-spacing:-0.02em;color:#000000`. Body font the same stack. Paper entries: no box, no background; a 1px `RULE` top border, title link ink with underline, meta grey 13px; names italic only if `SciNames.italicByShape`; act labels plain red text after the name (no badge); links ink and underlined; the closing `<hr>` in `RULE`. Plain-text part unchanged.
- [ ] **Step 4: Run** the tests — PASS; then `mvn -B -pl web -am verify -Dit.test='Digest*'` — PASS.
- [ ] **Step 5: Commit** `feat(web): digest mail in the black, white and red brand; italics by name shape`.

### Task 4: Tokens, font and Mantine theme

**Files:**
- Modify: `frontend/package.json` (dependency `@fontsource/schibsted-grotesk` `^5.3.0`)
- Create: `frontend/src/styles/tokens.css`, `frontend/src/styles/global.css`, `frontend/src/styles/contrast.test.ts`
- Modify: `frontend/src/theme.ts`, `frontend/src/theme.test.ts`, `frontend/src/main.tsx`, `frontend/src/test/render.tsx`, `frontend/src/test/setup.ts` (if CSS imports need stubbing)

**Interfaces:**
- Produces: CSS variables `--gr-paper --gr-ink --gr-grey --gr-rule-soft --gr-red`, `--gr-font`, `--gr-type-{display,title,lede,head,body,ui,meta,small}-{size,line}` with the spec's values, `--gr-space-1`…`--gr-space-8` = 4, 8, 12, 16, 24, 32, 48, 64 px, `--gr-width: 1240px`, `--gr-side: 340px`.
- Produces (TS): `export const theme` and `export const cssVariablesResolver: CSSVariablesResolver` from `theme.ts`; both passed to every `MantineProvider` (app and `renderApp`).

- [ ] **Step 1: Write the failing tests.** `contrast.test.ts` imports `tokens.css?raw`, parses the `:root` block and the `[data-mantine-color-scheme='dark']` block, and asserts WCAG contrast ≥ 4.5 for ink/paper, grey/paper and red/paper in both schemes (compute relative luminance; no hard-coded ratios). `theme.test.ts` (rewritten): `theme.fontFamily` starts with `'Schibsted Grotesk'`; `theme.defaultRadius` is `0`; `cssVariablesResolver(theme).variables['--mantine-color-error']` is `'var(--gr-red)'`; neither `theme.colors.jay` nor `theme.colors.fawn` exists.
- [ ] **Step 2: Run** `cd frontend && npm install && npx vitest run src/styles src/theme.test.ts` — FAIL.
- [ ] **Step 3: Implement.**
  - `tokens.css`: the variables above; dark values under `:root[data-mantine-color-scheme='dark']`.
  - `global.css`: imports `@fontsource/schibsted-grotesk/{400,500,400-italic,500-italic}.css`; `body` paper/ink/`--gr-font`/body size; `:focus-visible { outline: 2px solid var(--gr-red); outline-offset: 2px }`; `@media (prefers-reduced-motion: reduce)` turns transitions off; `.gr-wrap { overflow-wrap: anywhere }`; links `a { color: var(--gr-ink); text-decoration: none }`, `a:hover` and links inside running text (`.gr-text a`) underlined 1px with offset 3px.
  - `theme.ts`: `fontFamily` and `headings.fontFamily` `"'Schibsted Grotesk', 'Helvetica Neue', Arial, sans-serif"`, headings weight 500; `defaultRadius: 0`; `focusRing: 'never'` (global CSS owns focus); `primaryColor: 'ink'` with a 10-shade `ink` tuple of `#000000`; `components` defaults: `Button` and `TextInput`/`Select`/`NativeSelect`/`SegmentedControl`/`Pagination` radius `xl`, `Button` `variant: 'default'`; `Alert`, `Menu`, `Modal`, `Popover` radius 0 and no shadow. Component classes (a `theme.module.css` passed through `classNames`): `Checkbox`/`Radio` ink outline, ink fill when checked; `Alert` no fill, 1px ink rules above and below, title 500, red title when `color="red"`; `Loader` replaced by `type: 'dots'` in ink; `Skeleton` drawn as a 1px ink rule at the content height (no grey block); `Pagination` current page as a red-underlined number, no fill; `Badge` ink outline pill, no fill.
  - `cssVariablesResolver`: map `--mantine-color-body`→`var(--gr-paper)`, `--mantine-color-text`→`var(--gr-ink)`, `--mantine-color-dimmed`→`var(--gr-grey)`, `--mantine-color-default-border`→`var(--gr-ink)`, `--mantine-color-anchor`→`var(--gr-ink)`, `--mantine-color-error`→`var(--gr-red)`, `--mantine-primary-color-filled` and `-hover`→`var(--gr-ink)`, `--mantine-primary-color-contrast`→`var(--gr-paper)`, for both `light` and `dark`.
  - `main.tsx` imports `@mantine/core/styles.css` first, then `styles/tokens.css` and `styles/global.css`, and passes `cssVariablesResolver` to `MantineProvider`; `renderApp` passes it too.
- [ ] **Step 4: Run** `npm test` (all 97+ tests) and `npm run build` — PASS. Fix tests that asserted old colours only.
- [ ] **Step 5: Commit** `feat(frontend): design tokens, Schibsted Grotesk and the Mantine theme on them`.

### Task 5: Logo, favicon and brand docs

**Files:**
- Create: `docs/brand/garrulus-mark.svg` (replace), `garrulus-mark-black.svg`, `garrulus-mark-white.svg`, `garrulus-lockup.svg`, `frontend/public/favicon.svg` (replace), `frontend/src/ui/Mark.tsx`, `frontend/src/ui/Mark.test.tsx`
- Delete: `docs/brand/garrulus-mark-dark.svg`, `docs/brand/garrulus-jay.svg`, `docs/brand/garrulus-jay-dark.svg` (the frontend's old assets go in Task 7 with `Brand.tsx`, which imports them)
- Modify: `docs/brand/README.md` (rewrite), `CLAUDE.md` (the "Brand:" bullet)

**Interfaces:**
- Produces: `Mark({ size = 38, title }: { size?: number; title?: string })` — inline SVG of the 3m mark in `currentColor`, `role="img"` with `aria-label` when `title` is given, else `aria-hidden`; mask ids made unique per instance with `useId()`.

- [ ] **Step 1: Final pass on the geometry.** Start from spec Appendix A. Serve a scratch page (scratchpad + `python3 -m http.server`, Playwright screenshot) showing the mark at 260, 64, 32 and 16 px on white and black. Fix: crest cut 1 starts exactly in the notch at (28, 30) and cut 2 in the notch at (30, 45) so no sliver remains; the cut ends taper to points inside the shape; the throat curve is smooth into the moustache band. Keep the overall outline. Record the final path data.
- [ ] **Step 2: Write `Mark.test.tsx`**: renders an `svg` with `aria-label="Garrulus"` when `title="Garrulus"`; two `Mark`s on one page have different mask ids.
- [ ] **Step 3: Run** `npx vitest run src/ui/Mark.test.tsx` — FAIL; implement `Mark.tsx`; run — PASS.
- [ ] **Step 4: Write the files.** `garrulus-mark.svg` in `currentColor`; `-black`/`-white` with fixed fills; `favicon.svg` black on white with `<style>@media (prefers-color-scheme: dark){path{fill:#fff}}</style>`; `garrulus-lockup.svg` mark plus "Garrulus" in Schibsted Grotesk 500 converted to outlines (one-off script in the scratchpad with `opentype.js` reading the fontsource `.woff`; commit only the SVG). Check each file renders in Chrome via Playwright.
- [ ] **Step 5: Rewrite `docs/brand/README.md`**: name and why; the mark and its files; lockups and sizes (16 px shows the silhouette); colour tokens with light/dark values, roles and a contrast table (computed values from Task 4's test); typeface and the type scale; the italics rule; red's single meaning; "no blue, no fawn". Update CLAUDE.md's Brand bullet to: "logo, colour tokens (black, white, one red), typeface and the Mantine theme are in `docs/brand/` and `frontend/src/styles/tokens.css`. Take colours from there; never invent new ones."
- [ ] **Step 6: Commit** `feat(brand): jay-head mark, favicon and brand guide for the new identity`.

### Task 6: Scientific names — rule, `SciName`, `ActMarker`, `SciTitle`

**Files:**
- Create: `frontend/src/ui/sciName.ts`, `frontend/src/ui/SciName.tsx`, `frontend/src/ui/ActMarker.tsx`, `frontend/src/ui/SciTitle.tsx`, `frontend/src/ui/sciName.test.ts`, `frontend/src/ui/SciName.test.tsx`
- Modify: `frontend/src/api.ts` (types from Tasks 1–2), `frontend/src/test/server.ts` (`work()` fixture gains `titleMentions: []`)

**Interfaces:**
- Consumes: `actLabel(type: string): string` from `frontend/src/hitNames.ts`.
- Produces:
  - `api.ts`: `NameRef` gains `kind?: string | null; rank?: string | null; usageRank?: string | null; actTypes?: string[]`; `WorkSummary` gains `volume?, issue?, pages?: string | null; titleMentions?: MentionSpan[]`; `WorkDetail` gains `titleMentions?: MentionSpan[]`.
  - `type NameInfo = { rank?: string | null; usageRank?: string | null; kind?: string | null }` and `isItalicName(name: string, info?: NameInfo): boolean`, both exported from `ui/sciName.ts`.
  - `SciName({ name, rank, usageRank, kind, to, acts }: { name: string; rank?; usageRank?; kind?; to?: string; acts?: string[] })` — italic or roman text, a router `Link` when `to` is set, then one `ActMarker` per act.
  - `ActMarker({ type }: { type: string })` — `<span class="gr-act">` with `actLabel(type)`, red.
  - `SciTitle({ title, mentions, names }: { title: string; mentions: MentionSpan[]; names?: Map<number, NameInfo> })` — the title with each mention span rendered italic or roman by `isItalicName` (looked up by `nameId`, falling back to the span's verbatim shape); code-point offsets; overlapping or out-of-range spans skipped.

- [ ] **Step 1: Write failing tests.** `sciName.test.ts`: rank `SPECIES` → italic; rank `genus` → italic; rank `FAMILY` → roman; rank `UNRANKED`, usageRank `genus` → italic; no rank, `Abies alba` → italic; no rank, `Synteliidae` → roman; no rank, `Syntelia` → roman; kind `OTU` (`BOLD:AAA1234`) → roman; rank `section` → italic; rank `order` → roman. `SciName.test.tsx`: `Abies alba` with acts `['SP_NOV']` renders an `<i>` element with the name and a following `sp. nov.`; `Pinaceae` with rank `FAMILY` has no italic element; `SciTitle` with title `"𝔄 Abies alba"` and span 2–12 italicises exactly `Abies alba`; overlapping spans render the first once.
- [ ] **Step 2: Run** `npx vitest run src/ui` — FAIL.
- [ ] **Step 3: Implement.** `ITALIC_RANKS` (lower case, `_` → space): genus, subgenus, infrageneric name, section, section botany, subsection, subsection botany, series, subseries, species aggregate, species, infraspecific name, subspecies, infrasubspecific name, variety, subvariety, form, subform, natio, proles, morph, aberration. Rank `unranked`/`other`/empty means unknown. Shape test as in Task 3's `SciNames.italicByShape` (same regex). Italic names render as `<i>` everywhere (a typographic convention, not emphasis); tests query `i` elements.
- [ ] **Step 4: Run** — PASS; `npm test` all green.
- [ ] **Step 5: Commit** `feat(frontend): scientific-name rule with SciName, ActMarker and SciTitle`.

### Task 7: Page frame — `PageHeader`, `Footer`, `Rule`, `MetaLine`, app shell

**Files:**
- Create: `frontend/src/ui/PageHeader.tsx`, `frontend/src/ui/Footer.tsx`, `frontend/src/ui/Rule.tsx`, `frontend/src/ui/MetaLine.tsx`, `frontend/src/ui/frame.module.css`, `frontend/src/ui/MetaLine.test.tsx`, `frontend/src/ui/PageHeader.test.tsx`
- Modify: `frontend/src/App.tsx` (drop `AppShell`), `frontend/src/components/SiteMenu.tsx`, `frontend/src/components/AccountMenu.tsx`, `frontend/src/App.test.tsx`, `frontend/src/components/SiteMenu.test.tsx`
- Delete: `frontend/src/components/Brand.tsx`, `frontend/src/assets/garrulus-mark.svg`, `frontend/src/assets/garrulus-mark-dark.svg` (the mark is inline in `Mark.tsx`)

**Interfaces:**
- Consumes: `Mark` (Task 5), `SITE_LINKS` from `SiteMenu.tsx`.
- Produces:
  - `PageHeader({ variant }: { variant: 'big' | 'compact' })`. Big: top line (tagline "Names in the literature of biodiversity science" left, `AccountMenu` right), `Mark` 96 px + wordmark at `--gr-type-display` as an `h1` "Garrulus" linking home, nav between two ink rules with the search field slot on the right (`searchSlot?: ReactNode`). Compact: `Mark` 38 px + wordmark 30 px left, nav and account right, one rule below. Nav collapses to `SiteMenu`'s burger below 768 px. Active link underlined in red (2px, offset 6px).
  - `Footer()` — one rule, then "Garrulus, part of the Catalogue of Life" (link to `https://www.catalogueoflife.org`), About, API (`/api/openapi.yaml`).
  - `Rule({ soft }: { soft?: boolean })` — `<hr>` 1px ink or `--gr-rule-soft`.
  - `MetaLine({ authors, authorCount, source, volume, issue, pages, date, year, textLevel })` — grey, joined with two spaces as in mockup B: `Dinh, Nishikawa, Luu et al.  ZooKeys 1297(3): 311–337  16 Nov 2026  Full text`; empty parts dropped; journal links to `/source/:id`; text level labels `Full text`, `Abstract`, nothing for `NO_TEXT`; pages hyphen shown as an en dash.
- [ ] **Step 1: Write failing tests.** `MetaLine.test.tsx`: full input renders the example string above (normalise whitespace); no source, no authors, no volume → only date and text level, no stray separators or colons; `authorCount` 6 with 3 authors → "et al."; volume without pages → `ZooKeys 1297`. `PageHeader.test.tsx`: big renders a heading "Garrulus" and the tagline; compact renders the wordmark as a link to `/` and no tagline; the current nav link has `aria-current="page"`. `App.test.tsx` keeps its heading assertion (now from `PageHeader`).
- [ ] **Step 2: Run** `npx vitest run src/ui src/App.test.tsx src/components/SiteMenu.test.tsx` — FAIL.
- [ ] **Step 3: Implement.** `App` renders `PageHeader` (`big` on `/`, else `compact`), a `<main>` limited to `--gr-width` with 40 px gutters (16 px below 480 px), then `Footer`. Format dates with `toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })`.
- [ ] **Step 4: Run** `npm test` — PASS.
- [ ] **Step 5: Commit** `feat(frontend): page header, footer, rules and meta line`.

### Task 8: Search page — `WorkItem`, `FacetGroup`, `SideColumn`

**Files:**
- Create: `frontend/src/ui/WorkItem.tsx`, `frontend/src/ui/FacetGroup.tsx`, `frontend/src/ui/SideColumn.tsx`, `frontend/src/ui/list.module.css`, `frontend/src/ui/WorkItem.test.tsx`, `frontend/src/ui/FacetGroup.test.tsx`
- Modify: `frontend/src/components/WorkList.tsx`, `frontend/src/components/FilterSidebar.tsx`, `frontend/src/components/TaxGroupIcon.module.css`, `frontend/src/pages/SearchPage.tsx`, their tests

**Interfaces:**
- Consumes: `SciName`, `SciTitle`, `ActMarker` (Task 6), `MetaLine`, `Rule`, `PageHeader` `searchSlot` (Task 7).
- Produces:
  - `WorkItem({ work }: { work: WorkSummary })` — rule above; title (22 px, 500) as a link to `/work/:id` rendered with `SciTitle(work.title, work.titleMentions)` using `mainNames` ranks; `MetaLine`; main names as `SciName` with `to=/name/:id` and their `actTypes`, gaps of 18 px. If no main name carries acts but `hasActs`, a single red "new names" after the names.
  - `FacetGroup({ title, items, current, onSelect }: { title: string; items: { value: string; label: ReactNode; count?: number }[]; current?: string; onSelect(value?: string): void })` — 13 px 500 heading under a rule; rows 15 px, label left, count right in grey (proportional figures); current row underlined red with `aria-current="true"`; selecting the current value clears it.
  - `SideColumn({ lede, children }: { lede?: ReactNode; children: ReactNode })` — the right column; lede at `--gr-type-lede` under a rule.
- [ ] **Step 1: Write failing tests.** `WorkItem.test.tsx`: renders the title link, the meta line, `Tenumembrana parva` in italic followed by `sp. nov.`; with `hasActs` and no name acts shows "new names". `FacetGroup.test.tsx`: counts render as `11,102`; clicking a row calls `onSelect('Animals')`; the current row has `aria-current` and clicking it calls `onSelect(undefined)`. Existing `SearchPage.test.tsx` and `FilterSidebar.test.tsx` keep their behaviour assertions (filters, follow, pagination) and pass.
- [ ] **Step 2: Run** `npx vitest run src/ui src/pages/SearchPage.test.tsx src/components/FilterSidebar.test.tsx` — FAIL.
- [ ] **Step 3: Implement.** `SearchPage`: the search field moves into `PageHeader`'s `searchSlot` (pill, placeholder "Search titles and abstracts"); body grid `1fr var(--gr-side)` with 56 px gap from 1024 px; main column: count line ("11,102 works", grey 14 px) then `WorkList` of `WorkItem`s then `Pagination` (current page red); side column: lede "Garrulus reads N journals of biodiversity science every day and lists the scientific names it finds, placed in the Catalogue of Life." (N from `/api/sources` total if cheap, else omit the number), Follow and feed pills when only a taxgroup filter is set, then `FilterSidebar` rebuilt from `FacetGroup`s (taxon picker, group, code, journal type, acts, years keep their current controls, restyled). Below 1024 px the side column sits above the list behind a "Filters" pill that toggles it (`aria-expanded`). `TaxGroupIcon` icons render in ink (CSS `filter` or mask with `currentColor`).
- [ ] **Step 4: Run** `npm test` — PASS.
- [ ] **Step 5: Commit** `feat(frontend): search page on the design system`.

### Task 9: Work page — `NameMark` and the title

**Files:**
- Create: `frontend/src/ui/NameMark.tsx`, `frontend/src/ui/NameMark.test.tsx`
- Modify: `frontend/src/components/HighlightedText.tsx`, `frontend/src/components/HighlightedText.test.tsx`, `frontend/src/components/ClassificationPath.tsx`, `frontend/src/components/Section.tsx`, `frontend/src/pages/WorkPage.tsx`, `frontend/src/pages/WorkPage.test.tsx`

**Interfaces:**
- Consumes: `isItalicName`, `SciTitle`, `SciName`, `ActMarker`, `MetaLine`, `SideColumn`, `Rule`.
- Produces: `NameMark({ span, name, children }: { span: MentionSpan; name?: NameInfo; children: string })` — link to `/name/:nameId`, italic by `isItalicName`, 1px ink underline (offset 3px), red underline when `span.actType` is set. `HighlightedText({ block, names })` gains `names?: Map<number, NameInfo>` built once in `WorkPage` from `WorkDetail.names`.
- [ ] **Step 1: Write failing tests.** `NameMark.test.tsx`: family-rank name renders roman, species italic, an act span has the act class. `HighlightedText.test.tsx` keeps its code-point offset tests and adds: a mention of a `FAMILY` name is not italic. `WorkPage.test.tsx`: the title renders with `titleMentions` italics for a work without `text`; act labels show as `sp. nov.` (existing assertion stays).
- [ ] **Step 2: Run** `npx vitest run src/ui/NameMark.test.tsx src/components/HighlightedText.test.tsx src/pages/WorkPage.test.tsx` — FAIL.
- [ ] **Step 3: Implement.** Work page: title at `--gr-type-title` via `SciTitle`; `MetaLine` with the DOI and licence after it; body grid as on search; main column: abstract (lede size for the first paragraph), then the full text blocks (headings at `--gr-type-head`), or the snippets; side column: names grouped as today ("Placed in COL", "Placed by the verbatim classification", "Not placed") as `SciName` lists with acts, then the verbatim classification. `Section` becomes a heading under a rule. `ClassificationPath` renders ranks roman/italic by rank via `SciName`.
- [ ] **Step 4: Run** `npm test` — PASS.
- [ ] **Step 5: Commit** `feat(frontend): work page with name marks and titled names`.

### Task 10: Taxon, name and journal pages

**Files:**
- Modify: `frontend/src/pages/TaxonPage.tsx`, `NamePage.tsx`, `SourcePage.tsx`, `frontend/src/components/YearBars.tsx`, `FollowButton.tsx`, `FeedLink.tsx`, their tests

**Interfaces:**
- Consumes: `SideColumn`, `FacetGroup`, `SciName`, `WorkItem` via `WorkList`, `Rule`.
- [ ] **Step 1: Adjust tests first** where they assert layout-specific markup; add to `YearBars.test.tsx`: the selected range's bars carry the class `gr-current` (red), the others ink.
- [ ] **Step 2: Run** the page tests — FAIL where the new assertions are.
- [ ] **Step 3: Implement.** Each page: title at `--gr-type-title` (taxon and name titles through `SciName` with their rank), a grey sub-line (rank, status, journal ISSN or publisher as today), body grid; main column: the works list; side column: stats (counts as `FacetGroup`-style rows), `YearBars`, Follow (outline pill; following state filled ink pill) and feed link (plain underlined link "Atom feed"). `YearBars`: bars ink, selected range red, axis labels grey; no other colours.
- [ ] **Step 4: Run** `npm test` — PASS.
- [ ] **Step 5: Commit** `feat(frontend): taxon, name and journal pages on the design system`.

### Task 11: Remaining pages and `EmptyState`

**Files:**
- Create: `frontend/src/ui/EmptyState.tsx`, `frontend/src/ui/EmptyState.test.tsx`
- Modify: `frontend/src/pages/JournalsPage.tsx`, `AboutPage.tsx`, `SignInPage.tsx`, `SignInVerifyPage.tsx`, `VerifyEmailPage.tsx`, `AccountPage.tsx`, `UnsubscribePage.tsx`, `FollowingPage.tsx`, `HitsPage.tsx`, `frontend/src/components/ConfirmButton.tsx`, `AccountMenu.tsx`, `TaxonPicker.tsx`, `frontend/src/routes.tsx`, their tests

**Interfaces:**
- Produces: `EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode })` — large `Mark` (120 px), title at `--gr-type-head`, text, optional action.
- [ ] **Step 1: Write failing tests.** `EmptyState.test.tsx`: renders title and action. `routes` test (in `App.test.tsx`): an unknown path shows `EmptyState` "Page not found" with a link "Go to the search".
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.** Journals: ruled list, counts right-aligned. About: lede intro, body text, the large mark. Sign-in and email pages: one 480 px column, one filled primary pill. Account, Following, Hits: ruled lists; `ConfirmButton` and other destructive actions as red outline pills; Hits names through `SciName` (shape rule, since hit names have no rank) with `ActMarker`s. Empty lists use `EmptyState`. `TaxonPicker` dropdown square, options with `SciName`.
- [ ] **Step 4: Run** `npm test` — PASS.
- [ ] **Step 5: Commit** `feat(frontend): remaining pages, empty states and not-found page`.

### Task 12: Specimen page `/design`

**Files:**
- Create: `frontend/src/pages/DesignPage.tsx`, `frontend/src/pages/DesignPage.test.tsx`
- Modify: `frontend/src/routes.tsx`

**Interfaces:**
- Consumes: every `ui/` component, the tokens.
- [ ] **Step 1: Write the failing test**: `/design` renders headings "Colour", "Type", "Logo", "Components", "Patterns"; a swatch for each `--gr-*` colour with its hex read from the computed style or a constant list matching `tokens.css`.
- [ ] **Step 2: Run** — FAIL; implement; run — PASS.
- [ ] **Step 3: Implement** sections: colour swatches (light and dark side by side by wrapping one half in `data-mantine-color-scheme="dark"`), type scale lines with token names, the mark at 260/64/32/16 px and both lockups, every Mantine component in default, hover (static class), focus, active, disabled and error states, the Garrulus components with sample data (the three works from mockup B), the italics rule table. No API calls.
- [ ] **Step 4: Commit** `feat(frontend): design specimen page at /design`.

### Task 13: Visual and keyboard verification

**Files:** none planned; fixes go into the files they concern.

- [ ] **Step 1: Run this branch's web** on 28080 against the dev DB: `mvn -B -DskipTests install` then `SERVER_PORT=28080 NAMETRACKER_DIGEST_CRON=- ADMIN_TOKEN= mvn -pl web spring-boot:run` (background). Point a copy of `frontend/vite.devrun.config.ts` (port 5174) at `http://localhost:28080` and start Vite with it.
- [ ] **Step 2: Screenshot** with Playwright every page — `/`, `/?taxgroup=Animals`, a taxon, a name with acts, a retained work (e.g. 40580), a closed work (e.g. 42931), a journal, `/journals`, `/about`, `/signin`, `/hits` and `/following` signed out, a 404, `/design` — at 1440, 1024, 768 and 390 px, light and dark (`page.emulateMedia({ colorScheme })`). Check against the spec: no horizontal scroll at 390 px, no colour outside the tokens, names italic by rule, red only in its four uses, no Mantine blue anywhere.
- [ ] **Step 3: Keyboard pass**: tab through the search page (search field, nav, filters, results, pagination), open and close the account menu and the filters pill, use the taxon picker; the red focus ring is visible on each stop.
- [ ] **Step 4: Run** `npm test`, `npm run build`, and `mvn -B -pl web -am verify -Dit.test='WorkControllerIT,Digest*' -Dtest='TitleSpansTest,SciNamesTest,DigestComposerTest,OpenApiCoverageTest'` — all PASS. Stop the 28080 web and the 5174 Vite.
- [ ] **Step 5: Commit** fixes as `fix(frontend): …` with what the screenshots showed.
