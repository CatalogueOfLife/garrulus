# ADR-0002: GROBID TEI has no italics; the judge must not depend on them

Date: 2026-11-14. Status: accepted.

## Context

The design left open whether GROBID's TEI keeps italics. Italics are the strongest cheap signal for a name in JATS and
TaxPub, and the pipeline uses them twice: as the ITALIC candidate generator and as the judge feature `italic`.

Checked with `grobid/grobid:0.9.1-crf` on Pensoft PDFs and on the `GrobidIT` fixture:

- `processFulltextDocument` emits no `<hi rend="italic">`, nor any other font markup, in the body.
- Paragraphs come back as single lines. GROBID already joins words that were hyphenated at a line break; we saw no
  hyphen followed by a newline in its output.

OpenAlex's TEI comes from GROBID too, so the same holds for it.

## Decision

- `TeiConverter` produces blocks without style spans. In mode B, and for any TEI-based content route, the ITALIC
  generator finds nothing and the `italic` feature is always false.
- The judge must work without italics: italics may raise a score but must not be required for acceptance.
- The tuning objective therefore includes mode B. `eval tune --modes A,B` maximises the mean names F1 of mode A (JATS,
  with italics) and mode B (PDF via GROBID, without them) on the dev split. A weight setting that only works with italics
  loses on mode B.
- `TeiConverter` still de-hyphenates with the COL dictionary (Ruling M). GROBID does most of the joining, but other TEI
  sources may not.

## Consequences

- In mode B the italics baseline finds no names at all (F1 0), and the pipeline relies on gnfinder, the dictionary and the
  suffix rules for uninomials.
- Uninomials in PDF-only papers are where precision is weakest; the reports break this down by kind.
- If a later GROBID version or another PDF converter keeps font styles, map them to `Style.ITALIC` in the converter. The
  judge needs no change.
