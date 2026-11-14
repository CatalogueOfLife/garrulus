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
- The `GnfinderGenerator` trims punctuation from verbatims and keeps a matched closing parenthesis (subgenus). The
  mention's verbatim and offsets therefore cover all of "Carabus (Megodontus) violaceus". When it checks gnfinder's
  verbatim against the text, it maps ␤ back to a line break. The `EpithetGuard` cuts over-long gnfinder spans.
- The mention **name** is gnfinder's name whenever gnfinder saw exactly that span, so the subgenus is dropped. The path:
  1. `GnfinderGenerator` stores `GnName.name()` ("Carabus violaceus") as the candidate name.
  2. `EpithetGuard` leaves that name alone unless it cuts the span. When it cuts, it shortens gnfinder's name, or falls
     back to the collapsed verbatim if the tokens differ.
  3. `CandidateMerger` takes the name of the exact-span candidate whose generator comes first in `Generator`, and
     GNFINDER comes first. This holds even over a publisher `TAXON_MARKUP` tag on the same span.
  4. `AbbreviationExpander` only replaces an abbreviated genus.
  5. `PipelineNameFinder` uses the result as `Mention.name`.

  A span gnfinder did not see exactly keeps its subgenus in the name, because the dictionary, italic and markup
  generators name a span by its collapsed verbatim.

## Consequences

- No call to `verifier.globalnames.org`; the finder works offline apart from the local gnfinder container.
- Abbreviation, act and hyphenation behaviour is ours to test and tune. The eval reports measure acts separately.
- A gnfinder upgrade has to be re-checked against these observations. The ITs pin the hyphenation and offset behaviour.
- **Known gap: subgenus in names.** A name gnfinder saw comes out without its subgenus ("Carabus depressus" for
  "Carabus (Platycarabus) depressus", see the 2026-11-14 spot-check). The Pensoft gold names keep it
  ("Genus (Subgenus) species").
  - Span scoring (STRICT and OVERLAP) compares offsets and is not affected.
  - Name-based scoring counts every such name as one miss plus one false hit: document-level runs (all of mode B) and
    the main-subject metric.
  - Fixing it means building the name from the verbatim, or putting the subgenus back into gnfinder's name. That is a
    behaviour change and needs the reports re-run.
