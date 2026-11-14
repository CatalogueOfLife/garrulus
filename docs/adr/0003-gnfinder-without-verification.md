# ADR-0003: gnfinder runs without verification; we do abbreviations, acts and hyphenation ourselves

Date: 2026-11-14. Status: accepted.

## Context

gnfinder (`gnames/gnfinder:v1.4.0`) can verify every name against `verifier.globalnames.org`, expand abbreviated genera,
and annotate "sp. nov."-style markers (`annotationNomen`). We match names against COL ourselves and must not depend on an
external service for every document. gnfinder's own expansion and annotation are local to a sentence and English-centred.

Observed while building the pipeline (Task 7 onwards):

- **Hyphenated line breaks.** gnfinder handles "Pterosti-\nchus niger" inconsistently. At the start of a text, as in
  "Pterosti-\nchus niger was found.", it returns nothing (`GnfinderClientIT` pins this). Inside a sentence ("We studied
  Pterosti-\nchus niger") it joins the parts and returns a verbatim with the symbol ␤ in place of the line break.
- **Subgenera.** gnfinder splits "Carabus (Megodontus) violaceus" into two names: the full span with name
  "Carabus violaceus" (subgenus dropped), and "(Megodontus)" as a uninomial whose verbatim includes the parentheses.
  Verbatims in general include surrounding punctuation, as in "(Coleoptera:".
- **BOM.** A leading byte order mark is stripped and offsets count from the next character (see ADR-0001).

## Decision

- Always call gnfinder with `verification:false`, `ambiguousNames:true`, `oddsDetails:true`, `language:"detect"` and
  `wordsAround:0`. Its Bayes odds (`oddsLog10`) are one judge feature among others.
- Abbreviations are expanded document-wide by our `AbbreviationExpander`. An ambiguous expansion stays unexpanded.
- Nomenclatural acts come from our `ActTagger`: a multilingual marker catalogue with compound markers ("gen. et sp.
  nov."), attached to the nearest preceding accepted name. It is consistent with how the Pensoft gold maps
  `tp:taxon-status`. gnfinder's `annotationNomen` is not used.
- De-hyphenation runs **before** gnfinder. `TeiConverter` joins with the COL dictionary on raw block text (Ruling M).
  Converters map a `<break/>` to a space.
- The `GnfinderGenerator` trims punctuation from verbatims, keeps a matched closing parenthesis (subgenus), and maps ␤
  back to a line break. The `EpithetGuard` cuts over-long gnfinder spans. Our name is built from the verbatim span, so
  "Genus (Subgenus) species" keeps its subgenus.

## Consequences

- No call to `verifier.globalnames.org`; the finder works offline apart from the local gnfinder container.
- Abbreviation, act and hyphenation behaviour is ours to test and tune. The eval reports measure acts separately.
- A gnfinder upgrade has to be re-checked against these observations. The ITs pin the hyphenation and offset behaviour.
