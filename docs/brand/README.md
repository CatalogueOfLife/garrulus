# Garrulus brand

**Garrulus** is the public name of the application: the genus of the European jay, *Garrulus glandarius*. Jays are
loud, curious birds that notice everything in the wood and spread what they find, which is what the application does
with names in the literature. The internal id stays `nametracker`: Java packages, Maven artifactIds, the database,
systemd units and the repository.

**Use "Garrulus" for everything people see:** UI and page titles, logo, favicon, email sender name, feed titles,
OpenAPI title, public URLs and the outgoing HTTP User-Agent (`Garrulus/<version> (+https://www.catalogueoflife.org)`).

## Logo

| File | Use |
|---|---|
| `garrulus-mark.svg` | Main mark and favicon: the jay's barred blue wing-covert feather, the part of the bird people find in the woods |
| `garrulus-mark-dark.svg` | The same mark for dark backgrounds (light shaft, lighter feather base) |
| `garrulus-jay.svg`, `-dark.svg` | Secondary illustration of a jay in profile, for empty states, the about page and error pages. Not for sizes below 48 px. |

**Wordmark:** "Garrulus" set in a serif italic, like a genus name in a paper (`Georgia, 'Times New Roman', serif`,
italic, weight 600). Use only system fonts, so the app has no web-font dependency. In the header, place the mark to the
left of the wordmark, with the mark at about 1.4× the cap height.

## Colours

All colours are taken from the bird:
- **jay:** the blue barred wing patch
- **fawn:** the vinaceous, pinkish-brown body
- **ink:** the black moustache stripe, tail and flight feathers
- **white:** the rump and the white wing patch

Each scale has 10 shades (Mantine's `0`–`9`).

| Shade | `jay` (primary) | `fawn` (secondary) | `ink` (Mantine `dark`) |
|---|---|---|---|
| 0 | `#EAF3FC` | `#FAF4F1` | `#C9C5C2` |
| 1 | `#D3E6F8` | `#F2E5DF` | `#ADA8A4` |
| 2 | `#A8CEF2` | `#E6CDC2` | `#8D8783` |
| 3 | `#7AB4EA` | `#D8B3A4` | `#6B6662` |
| 4 | `#5199E0` | `#CB9C8A` | `#4B4744` |
| 5 | `#3482D3` | `#BC8672` | `#383533` |
| 6 | `#2A6FBA` | `#A0705D` | `#2A2826` |
| 7 | `#235C9B` | `#82594A` | `#201E1D` |
| 8 | `#1C4A7C` | `#644337` | `#181716` |
| 9 | `#14365B` | `#462E25` | `#100F0F` |

### Roles

| Role | Light scheme | Dark scheme |
|---|---|---|
| Page background | white `#FFFFFF` | `ink.7` |
| Panels, sidebars, cards | `fawn.0` | `ink.6` |
| Body text | `ink.9` | `ink.0` |
| Muted text | `fawn.7` | `ink.2` |
| Primary buttons, active states | `jay.6` with white text | `jay.6` with white text |
| Links | `jay.6` | `jay.4` |
| Found-name highlight in article text | background `jay.1`, underline `jay.6` | background `jay.9`, underline `jay.4` |
| New-name (sp. nov., comb. nov.) badge | `fawn.7` with white text | `fawn.4` with `ink.9` text |
| Borders, dividers | `fawn.2` | `ink.5` |

**Contrast (WCAG 2.1):** the pairs above are at least 4.5:1 for normal text. For example:

| Pair | Contrast |
|---|---|
| white on `jay.6` | 5.1 |
| `jay.4` on `ink.8` | 6.0 |
| `fawn.7` on `fawn.0` | 5.5 |
| `jay.8` on `fawn.0` | 8.3 |

`jay.5` with white text is only 4.0, and `fawn.6` with white text only 4.2. Neither may be used for filled elements
with text.

## Mantine theme

`frontend/src/theme.ts`:

```ts
import { createTheme, type MantineColorsTuple } from '@mantine/core';

const jay: MantineColorsTuple = ['#EAF3FC', '#D3E6F8', '#A8CEF2', '#7AB4EA', '#5199E0', '#3482D3', '#2A6FBA', '#235C9B', '#1C4A7C', '#14365B'];
const fawn: MantineColorsTuple = ['#FAF4F1', '#F2E5DF', '#E6CDC2', '#D8B3A4', '#CB9C8A', '#BC8672', '#A0705D', '#82594A', '#644337', '#462E25'];
const ink: MantineColorsTuple = ['#C9C5C2', '#ADA8A4', '#8D8783', '#6B6662', '#4B4744', '#383533', '#2A2826', '#201E1D', '#181716', '#100F0F'];

export const theme = createTheme({
  primaryColor: 'jay',
  primaryShade: { light: 6, dark: 6 },
  colors: { jay, fawn, dark: ink },
  black: '#100F0F',
  defaultRadius: 'md',
  headings: { fontFamily: "Georgia, 'Times New Roman', serif" },
});
```
