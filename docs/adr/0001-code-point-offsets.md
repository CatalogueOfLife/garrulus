# ADR-0001: Offsets are Unicode code points

Date: 2026-11-14. Status: accepted.

## Context

Every mention, act marker, style span and gold annotation points into `Block.text` by offsets. Three systems have to agree
on what an offset counts:

- Java strings, which index UTF-16 units;
- gnfinder, which by default (without `bytesOffset`) counts Unicode code points;
- Label Studio, which counts code points in its exports (the importer also accepts UTF-16 units and checks the region text
  either way).

Texts are not plain ASCII. Umlauts and ligatures are single code points, but astral characters (mathematical letters,
emoji in abstracts) take two UTF-16 units. Pensoft titles start with a byte order mark (U+FEFF). gnfinder strips a leading
BOM and counts its offsets from the character after it.

## Decision

- Every offset in the `finder-api` contract is a **code-point index into `Block.text`**, with `end` exclusive.
- Java code converts with `String.offsetByCodePoints` (wrapped in `CodePoints`) and never mixes in UTF-16 indices.
- `TextNormalizer` drops U+FEFF and U+200B (and soft hyphens), so the converters never produce text with a BOM.
  `GnfinderGenerator` still shifts gnfinder's offsets by one when a chunk starts with a BOM, for documents that reach the
  finder from API clients without normalisation.

## Consequences

- gnfinder spans map onto blocks without conversion; `GnfinderClientIT` pins the code-point behaviour with an umlaut and an
  emoji.
- JavaScript and other UTF-16 clients must convert offsets. `finder-api.yaml` says so.
- A gold span that is off by one code point fails STRICT matching, so the corpus builders verify every span against its
  text.
