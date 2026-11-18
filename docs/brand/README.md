# Garrulus brand

**Garrulus** is the public name of the application: the genus of the European jay, *Garrulus glandarius*. Jays are
loud, curious birds that notice everything in the wood and spread what they find, which is what the application does
with names in the literature. The internal id stays `nametracker`: Java packages, Maven artifactIds, the database,
systemd units and the repository.

**Use "Garrulus" for everything people see:** UI and page titles, logo, favicon, email sender name, feed titles,
OpenAPI title, public URLs and the outgoing HTTP User-Agent (`Garrulus/<version> (+https://www.catalogueoflife.org)`).

The identity is black and white with one red: one grotesk for everything, black rules, outline pills, and red only
where something needs attention. The values below come from `frontend/src/styles/tokens.css`; that file is the source
when the two disagree.

## The mark

A jay's head in one colour: a solid head with a swept-back crest, a pale eye cut out, two cuts that start in the crest
notches and run the crest feathers on into the head, and a third cut that draws the black moustache stripe running on
from the bill. Black on white, white on black; never in red or grey, never outlined, never on a colour.

| File | Use |
|---|---|
| `garrulus-mark.svg` | The mark in `currentColor`, for inline use. As an image it renders black. |
| `garrulus-mark-black.svg` | Black, for white or light backgrounds. |
| `garrulus-mark-white.svg` | White, for black or dark backgrounds. |
| `garrulus-lockup.svg` | Mark and wordmark, both as outlines (no font needed), in `currentColor`, for use outside the app: other sites, slides, print. |
| `frontend/public/favicon.svg` | Black mark that turns white when the browser is in dark mode (`prefers-color-scheme` inside the SVG); cropped closer to the mark. |
| `frontend/src/ui/Mark.tsx` | The mark in the app: `<Mark size={38} title="Garrulus" />` in `currentColor`; without a `title` it is hidden from screen readers. |

**Geometry:** a 100-unit square (`viewBox="0 0 100 100"`). The head is one outline from the bill tip at (97, 52); the
crest notches are at (28, 30) and (30, 45), and each crest cut starts exactly in its notch; the eye is a circle at
(61, 41), radius 4.6; all three cuts taper to points inside the head. The files and `Mark.tsx` are one path filled
even-odd (head, eye, cuts), with no masks and no ids. A change to the geometry goes into all of them at once.

## Lockups and sizes

The wordmark is "Garrulus" in Schibsted Grotesk 500, upright (here it is the application's name, not the genus), with
the mark to its left.

- **Compact** (the header of every page but search): mark 38 px beside a 30 px wordmark, so the mark is 1.27 times
  the type size. `garrulus-lockup.svg` is this lockup as approved in the mockup: a third of the type size between
  them, centred on each other, letter-spacing −0.03em.
- **Big** (the search page): mark 96 px beside the display wordmark (`--gr-type-display`: 132 px, 88 px below 768 px,
  56 px below 480 px; letter-spacing −0.045em), bottoms aligned. Above it, the tagline on the left and the site menu
  and account on the right, at the same place as in the compact header but with no rule under them. Below it, a rule,
  then the intro text ("Garrulus reads … journals …") left of the search field, with no rule under that row.
- **Large mark alone:** empty states and error pages use the mark at 120 px instead of an illustration.
- **Small sizes:** the crest cuts, the eye and the moustache read from 32 px up. At 16 px the cuts blur and the
  moustache disappears; what remains is the silhouette with the eye, which is what the favicon shows.

## Colour

| Token | Light | Dark | Role |
|---|---|---|---|
| `--gr-paper` | `#FFFFFF` | `#000000` | Page background |
| `--gr-ink` | `#000000` | `#FFFFFF` | Text, rules, the logo, outlines; the fill of the one primary button |
| `--gr-grey` | `#6E6E6E` | `#9A9A9A` | Secondary text: meta lines, counts, hints |
| `--gr-rule-soft` | `#D6D6D6` | `#3A3A3A` | The rare minor separator inside dense blocks (tables); never between list items, never text |
| `--gr-red` | `#D2381C` | `#E8553A` | Attention, see below |

The dark scheme is the light one inverted: black paper, white ink.

**Contrast on paper (WCAG 2.1, computed as in `frontend/src/styles/contrast.test.ts`, which requires 4.5:1 for every
text colour):**

| Pair | Light | Dark |
|---|---|---|
| `--gr-ink` on `--gr-paper` | 21.00 | 21.00 |
| `--gr-grey` on `--gr-paper` | 5.10 | 7.46 |
| `--gr-red` on `--gr-paper` | 4.86 | 5.79 |
| `--gr-rule-soft` on `--gr-paper` (not for text) | 1.45 | 1.85 |

**Red means "look here", and only that:** new names (the act markers "sp. nov.", "comb. nov." and the others), the
current place (the active nav item, a chosen filter, the current page in pagination), errors, and the keyboard focus
ring (2 px, offset 2 px). Red is never a fill behind text and never decoration. Success messages are plain ink.

**No blue, no fawn.** The first identity's jay plumage palette (jay blue, fawn) and the feather mark are gone, and so
is any other colour: no green for success, no amber for warnings, no tints. Links are ink, underlined on hover and in
running text; the current place is underlined in red.

## Type

**Schibsted Grotesk**, weights 400 and 500, upright and italic, Latin and Latin Extended (for authors such as Pražák and
diacritics in names), self-hosted through `@fontsource/schibsted-grotesk`: no request to a third party. The fallback
stack is `'Helvetica Neue', Arial, sans-serif` (also the stack of the digest mail, since mail clients load no web
fonts). Figures are proportional; counts are right-aligned instead of tabular. Sentence case throughout: no all-caps
labels and no letter-spaced capitals.

| Token | Size (px) | Line height | Weight | Letter-spacing | Use |
|---|---|---|---|---|---|
| `--gr-type-display` | 132 (88 below 768 px, 56 below 480 px) | 0.9 | 500 | −0.045em | The big wordmark on the search page |
| `--gr-type-title` | 40 | 1.1 | 500 | −0.02em | Page title (taxon, name, work, journal) |
| `--gr-type-lede` | 22 | 1.3 | 400 | −0.01em | Intro text in the side column, the abstract's first lines |
| `--gr-type-head` | 22 | 1.25 | 500 | −0.01em | Section headings |
| `--gr-type-item` | 18 | 1.3 | 500 | −0.005em | Work titles in lists (search results, a taxon's papers) |
| `--gr-type-body` | 16 | 1.5 | 400 | | Running text, article full text |
| `--gr-type-ui` | 15 | 1.4 | 400 | | Nav, filters, names lists, buttons |
| `--gr-type-meta` | 14 | 1.4 | 400 | | Authors, journal, date, text level (grey); the main names under a listed work (ink) |
| `--gr-type-small` | 13 | 1.4 | 400 or 500 | | Facet group headings, footnotes, the header's top line |

Each token is a `-size` and a `-line` variable in `tokens.css`.

## Scientific names: the italics rule

Genus and below are set in italic; family and above in roman; authorship and qualifiers ("sp. nov.", "cf.",
"Bleeker, 1860") in roman. When a name's rank is unknown, the style is decided in this order:

1. The name's own rank.
2. The Catalogue of Life usage rank where the name is placed.
3. The name's shape: binomials and trinomials are italic; any uninomial is roman, because roman makes no claim.
4. OTU codes (BOLD BINs, UNITE SHs) are always roman.

Act markers follow the name in red, roman, at the same size. A family name in italic or a genus in roman is a bug.
The digest mail and the Hits page have no ranks and go by the shape (step 3).

## Page layout

- Under the header, the first row holds the page's own head: its title and sub-line, or on the search page the
  result count.
- Below that row, the two columns both start with a rule at the same height. Nothing stands above the first rule of
  either column.
- The page actions (the Atom feed as a round outline button with the feed glyph, then the Follow pill) sit in that first row, flush right over the side column, under
  the header's account menu. Every page that can be followed puts them there (`ui/HeadRow.tsx`,
  `components/FollowActions.tsx`). Below 1024 px they follow the title.

## In code

- `frontend/src/styles/tokens.css`: every colour, type, space and layout token; dark values under
  `[data-mantine-color-scheme='dark']`.
- `frontend/src/styles/global.css`: the font imports, body text, links and the red focus ring.
- `frontend/src/theme.ts`: the Mantine theme on the tokens (pills for buttons and inputs, square corners elsewhere,
  no shadows).
- `frontend/src/ui/Mark.tsx`: the mark.
