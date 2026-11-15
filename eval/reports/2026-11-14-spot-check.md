# Spot-check of the finder service on five articles

Date: 2026-11-14

**Finder:** `finder-service` with the tuned defaults:
- pipeline 0.1.0-SNAPSHOT
- config `9e194ba0f1db4c4a2a1c71f674ee52e231f4367e22b8127a023397b5ed4fc420`
- COL dictionary `316691`
- gnfinder v1.4.0

**How it was run:**
- Each JATS file was converted locally with the publisher's taxon markup removed (italics kept), the same text as eval
  mode A. It was then sent to `POST /v1/find` through `RemoteNameFinder`.
- The same files also went through `POST /v1/find/jats`, which keeps the markup. All five answered 200 in under 0.2 s.
- Possible misses and false hits were listed by comparing the mentions with the publisher's `tp:taxon-name` tags
  (Pensoft) or with the italic spans (the other two). Then they were read in context.
- Reference lists are left out.

None of the five articles is in the eval corpora. All are CC BY 4.0. The texts are not committed; they were fetched into
`eval/work/spotcheck/`.

| Article | Source | Blocks | Mentions (non-ref) | Acts | Contexts | `/find/jats` (markup kept) |
|---|---|---:|---:|---:|---:|---|
| Zoology: Five new species of *Cranopygia* (Dermaptera, Pygidicranidae) from South China, ZooKeys 1262, [10.3897/zookeys.1262.175635](https://doi.org/10.3897/zookeys.1262.175635) | Pensoft XML | 241 | 118 | 22 | 7 | 168 mentions, 22 acts, 7 contexts |
| Botany: *Viola yuelingensis* (Violaceae), a new species from Guangxi, China, PhytoKeys 267, [10.3897/phytokeys.267.172198](https://doi.org/10.3897/phytokeys.267.172198) | Pensoft XML | 104 | 45 | 1 | 0 | 83, 4, 1 |
| Mycology: Four new species of *Microdochium* (Microdochiaceae, Xylariales) from Hainan, China, MycoKeys 126, [10.3897/mycokeys.126.170451](https://doi.org/10.3897/mycokeys.126.170451) | Pensoft XML | 826 | 216 | 10 | 5 | 299, 10, 5 |
| Spanish: *Scutellaria holguinensis* (Lamiaceae), nueva especie del noreste de Cuba, Rodriguésia 70, [10.1590/2175-7860201970049](https://doi.org/10.1590/2175-7860201970049) | SciELO JATS | 48 | 69 | 4 | 0 | 91, 4, 0 |
| Title chain: Draft genome of … *Carabus (Platycarabus) depressus* (Coleoptera: Carabidae) …, G3, [10.1093/g3journal/jkaf027](https://doi.org/10.1093/g3journal/jkaf027) | Europe PMC JATS (PMC12060234) | 81 | 32 | 0 | 1 | 40, 0, 1 |

## Zoology (ZooKeys, *Cranopygia*)

**Good:**
- The title chain "(Dermaptera, Pygidicranidae)" becomes a DOCUMENT context (ORDER, FAMILY).
- Each of the five new species gets SP_NOV acts and its own TREATMENT context.
- Of the 120 tagged names outside references, the finder misses 11.

**Misses:**
- Species-group names: the "*siamensis*-group", "*appendiculata*-group" and "*marmoricrura*-group" epithets. These are
  out of scope by `GUIDELINES.md`, but Pensoft tags them.
- One bare "*Cranopygia* evolution".

**False hits:**
- "Lisu", from "Nujiang Lisu Autonomous Prefecture" (a place, and a genus in COL).
- "Paratype", a material-section heading.
- The names in the "Citation" footnote (*Cranopygia*, Dermaptera, Pygidicranidae) are real names that Pensoft does
  not tag, so they are not errors.

**Compared with the untuned defaults:** the same article produced 68 untagged mentions, against 9 now. The ones that
disappeared include the treatment headings "Head", "Thorax", "Abdomen", "Genitalia" and "Pronotum", plus "General
appearance" and "Virga denticulate" (as binomials) and the place names "Yunnan", "Zhejiang", "Fugong" and "Indochina".

## Botany (PhytoKeys, *Viola yuelingensis*)

**Obvious miss:** the new species itself.
- *Viola yuelingensis* is rejected in the title, the headings, the captions and the tables (judge scores 0.05–0.57;
  threshold 0.8). The bare genus *Viola* is rejected too.
- Why: "viola" is also an ordinary word, so the dictionary marks it ambiguous. gnfinder gives it negative odds (−0.3).
  Italics alone no longer carry a name.
- The act "a new species" is still found once, on an accepted abbreviated mention. With the publisher markup
  (`/find/jats`) everything is found.

**Cause and suggestion:** this is the cost of the tuned judge on new species of genera that are also ordinary words
("Viola", "Major", "Ophelia"). An act marker right after the name ("sp. nov.", "a new species") is strong evidence, but
the judge has no feature for it. Add one (e.g. `actMarkerFollows`) and re-tune, ideally on the curated set.

**No false hits:** the only untagged mention is "Violaceae" in the citation footnote, which is a real name.

## Mycology (MycoKeys, *Microdochium*)

**Good:**
- The title chain "(Microdochiaceae, Xylariales)" becomes a DOCUMENT context.
- Four TREATMENT contexts, and SP_NOV acts for all four new species, including abbreviated ones ("M. baishamenense").

**Misses (7 of 216 tagged):**
- In the abstract, "M. baishamenense", "M. bambusina" and "M. bambusarum" are glued to their status. The XML has no
  space between `tp:taxon-name` and `tp:taxon-status`, so the text reads "M. baishamenensesp. nov.". The same glue
  shows up in the dev error samples ("caribbaeasp. nov."). The JATS converter should add a space there.
- Also missed: "Subploasporaceae", and the abbreviation key "M. = Microdochium".

**False hits:**
- "Canace", from the reagent "Hieff Canace® Plus PCR Master Mix".
- "Mycelia" and "Conidia", used as descriptive sentence starters. Both are also genus names in COL.

## Spanish (Rodriguésia, *Scutellaria holguinensis*)

**Good:**
- The title name and family are found.
- "nueva especie" and "sp. nov." both give SP_NOV acts: the Spanish act markers work.
- The Cuban synonymy list is found in full, including the trinomial "*Scutellaria havanensis* var. *portorricensis*".

**Misses:**
- "*Scutelleria*", a misspelling in the source abstract (italic uninomial, not in COL). Its binomial form "*Scutelleria
  holguinensis*" is found.
- No context: the title has one family in parentheses, not a chain.

**False hits:**
- "L. es" in "*Scutellaria* L. es un género …". The authority "L." plus the Spanish verb "es" is read as an abbreviated
  binomial and expanded to "Loma es".
- "Loma", from the place "Loma de La Mensura".
- "Diagnosis:Scutellaria holguinensis" in the English summary: a bold heading glued to the italic name. The mention name
  comes out as "Diagnosis�scutellaria holguinensis", with a U+FFFD replacement character taken from gnfinder's
  name. A name with U+FFFD should never be emitted, and the converter should keep a space between adjacent runs.

## Title chain (G3, *Carabus (Platycarabus) depressus* (Coleoptera: Carabidae))

**Good:**
- The colon chain "Coleoptera: Carabidae" becomes a DOCUMENT context with ORDER Coleoptera and FAMILY Carabidae.
- The verbatim keeps the subgenus: "Carabus (Platycarabus) depressus".
- The subfamilies and other higher taxa (Adephaga, Carabinae, Nebriinae, Dytiscidae) are found.

**False hits:**
- **Wrong abbreviation expansion.** "C. (Ohomopterus) uenoi", "C. depressus" and "C. (Mesocarabus) problematicus" are
  expanded to "*Camacho* …", although *Carabus* is in the title. The source is the citation "(Camacho et al. 2009)":
  the author name is a COL genus. Abbreviation sources are taken before judging, and citation authors are not filtered
  out.
- "Feron", from the citation "Feron and Waterhouse 2022".
- "Insecta data", from the BUSCO lineage dataset phrase.

**Misses:** "*C. (P.) depressus*" in a caption: an abbreviated genus with an abbreviated subgenus, a known gap of the
parser stage.

**Name form:** the mention name drops the subgenus ("Carabus depressus", gnfinder's name). The Pensoft gold uses
"Genus (Subgenus) species", so document-level scores count such names as a miss plus a false hit.

## Follow-ups worth doing

The order is by expected impact:

1. An act-marker feature for the judge, so a new species of an ambiguous genus survives (*Viola yuelingensis*).
2. Abbreviation expansion should prefer genera of accepted names and skip author citations (*Camacho* for *Carabus*).
3. The JATS converter should keep a space between `tp:taxon-name` and `tp:taxon-status`, and between a heading run and
   the next run.
4. Never emit a mention name containing U+FFFD.
5. Reject an authority initial followed by a common word ("L. es").
6. Treatment sub-headings and lab reagent names remain a false hit class: "Paratype", "Mycelia", "Conidia", "Canace".

## After the final-review fixes

Date: 2026-11-15. Same five articles, same procedure: each JATS file converted locally without the publisher's taxon
markup and sent to `POST /v1/find` (rejected candidates included, to see what the judge turned down), plus the raw XML
to `POST /v1/find/jats`. finder-service on 127.0.0.1:8095 with the re-tuned defaults (config
`799364566a46508c6817796ff29edb32d09b6343a8dc9a8a8e476f1f20d93153`, dictionary `316691`, gnfinder v1.4.0). Every
request answered 200 in under 0.5 s. The converter now separates words glued at a `tp:taxon-name` boundary, so some
block texts differ from the first run.

| Article | Mentions (non-ref) | Acts | Contexts | `/find/jats` (markup kept) |
|---|---:|---:|---:|---|
| ZooKeys *Cranopygia* | 118 (was 118) | 27 (22) | 7 (7) | 171, 27, 7 |
| PhytoKeys *Viola yuelingensis* | 47 (45) | 3 (1) | 0 (0) | 83, 4, 1 |
| MycoKeys *Microdochium* | 221 (216) | 14 (10) | 5 (5) | 299, 14, 5 |
| Rodriguésia *Scutellaria holguinensis* | 68 (69) | 4 (4) | 0 (0) | 90, 4, 0 |
| G3 *Carabus (Platycarabus) depressus* | 32 (32) | 0 (0) | 1 (1) | 41, 0, 1 |

**The five reported problems:**

- ***Viola yuelingensis*: fixed where an act marker follows the name with gnfinder or italics on the whole span.** The
  title, the abstract ("... Ju H.Huang, a new species") and the table cell "*Viola yuelingensis* sp. nov." are
  accepted as certain, each with an SP_NOV act; the five "*V. yuelingensis*" abbreviations are accepted too.
  - Still rejected (judge scores 0.01–0.26): the treatment heading "Viola yuelingensis Y.S.Chen & Ju H.Huang sp.
    nov.", where gnfinder sees only "Viola" (odds −0.3) and the heading has no italics, so the span lacks the full
    support the new-name rule needs; the citation paragraph; and the mentions without a marker in paragraphs and
    captions. The genus is still an ordinary word with negative gnfinder odds.
- ***Camacho*: fixed.** "C. depressus", "C. problematicus", "C. (Mesocarabus) problematicus" and "C. (Ohomopterus)
  uenoi" are now expanded to *Carabus* (the title's "*Carabus (Platycarabus) depressus*" is a source now that a
  subgenus is skipped). "Camacho" in "(Camacho et al. 2009)" is no source and is itself rejected.
- **U+FFFD: fixed.** No mention name contains U+FFFD. "Diagnosis:Scutellaria holguinensis" now gives the mention
  "*Scutellaria holguinensis*" from the italics; gnfinder's garbled candidate is dropped.
- **Glued "sp. nov.": fixed.** No "…sp. nov." is glued to a word in any of the five converted texts. In the MycoKeys
  abstract "*M. baishamenense*", "*M. bambusina*" and "*M. bambusarum*" are found, expanded to *Microdochium* and carry
  SP_NOV acts (10 → 14 acts for the article).
- **"L. es": fixed.** gnfinder does not see "L. es"; the dictionary alone proposed it, and an abbreviation outside
  italics whose epithet is a common word of the document's language ("es" in Spanish) is now dropped in that case
  (judged when another generator sees it). It is no longer a mention or a candidate.

**Unchanged:** the place, reagent and descriptive-word false hits ("Lisu", "Loma", "Canace", "Mycelia", "Conidia",
"Feron", "Insecta data") and the subgenus dropped from the mention name ("Carabus depressus", ADR-0003).
