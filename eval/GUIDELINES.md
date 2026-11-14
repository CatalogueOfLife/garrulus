# Annotation guidelines for the curated set

The 100 curated documents are the gold standard we tune and measure the name finder on. Whatever you tag here is what the
finder is judged against, so **consistency matters more than cleverness**. When a case is not covered below, take the
closest rule, and note the case in the comment field of the task so we can add it here.

You see a pre-tagged text. Machines (an italics rule and gnfinder) have already marked many names. Their output is a
starting point, often wrong: they miss names, add common words ("China", "Holotype"), and cut spans in the wrong place.
**Check every span and the text between the spans.** Delete wrong regions, fix the span boundaries, and add what is missing.

What counts is the document text exactly as shown. Do not correct spelling, OCR errors or old spellings, tag what is printed.

## 1. What you do in each document

Per document you work in this order:

1. Read the document. If the text is unusable (see section 8), choose `bad_text` in the task-level choice at the top and
   move on. Otherwise leave it on `ok`.
2. **Names:** every scientific name gets a `Name` region (section 2).
3. **Acts:** every nomenclatural act marker gets an `Act` region, an `act_type` choice and an `act_of` link to its name (section 4).
4. **Contexts:** every classification string or rank heading gets a `Context` region (section 5).
5. **Main subject:** on the `Name` regions the document is mainly about, tick `main_subject: yes` (section 6).
6. Submit.

A document without any scientific name is a valid, finished document: delete all pre-tags and submit it empty with `ok`.
Those documents matter, they measure false positives.

## 2. Names

**Tag every scientific name of a taxon at any rank**, wherever it occurs: title, abstract, headings, paragraphs, captions,
table cells, references.

- Ranks: species, genus, family, order, phylum, kingdom, subspecies, variety, form, and everything between.
  "Coleoptera", "Opheliidae", "Annelida" and "Carabus" are names as much as "Carabus auratus" is.
- Animals, plants, fungi, bacteria, archaea, protists. Also names that are only the genus part ("Rattus"), and
  names that are no longer valid (synonyms, homonyms, misspellings as printed).
- **Abbreviated names** count: "C. auratus", "E. coli", "O. limacina". The span is the whole abbreviation including the
  initial and its full stop. An abbreviated name is tagged even if the full name is never printed.
- **Hybrids:** a named hybrid is one name including the multiplication sign: "Mentha × piperita". A hybrid formula such as
  "Mentha aquatica × M. spicata" is two names, tag each parent separately and leave the sign outside.

### The span covers the name only

| Printed | Tag exactly |
|---|---|
| Carabus auratus Linnaeus, 1761 | `Carabus auratus` (no authorship, no year) |
| Ophelia limacina (Rathke, 1843) | `Ophelia limacina` |
| *Carabus* sp. | `Carabus` (leave "sp." out) |
| cf. Carabus auratus | `Carabus auratus` (leave "cf." out, same for "aff.", "nr.", "?") |
| Rosa canina var. glandulosa | `Rosa canina var. glandulosa` (the rank marker belongs to the name) |
| Carabus (Tachypus) auratus | `Carabus (Tachypus) auratus` (a subgenus in parentheses is part of the name) |
| Carabus (Tachypus) | `Carabus (Tachypus)` |
| "(Coleoptera: Carabidae)" | `Coleoptera` and `Carabidae` as two names, brackets, colon and comma outside |
| *Ophelia* spp. | `Ophelia` |
| Escherichia coli K-12 | `Escherichia coli` (strain left out) |

No surrounding brackets, quotes, commas, full stops or spaces in a span. The only punctuation inside a span is what belongs
to the name: the full stop of an abbreviation or rank marker, the parentheses of a subgenus, the hybrid sign.

A name that is cut across two blocks (the end of one paragraph and the start of the next) is tagged as two regions, one per block.
A region never runs across a blank line.

### Do not tag

- **Common (vernacular) names:** "golden jackal", "carabids", "opheliid worms", "Pensoft". Names derived from a genus used
  as an everyday word ("anopheline", "rhizobia") are also left alone.
- **Species-group and other informal group names:** "the *obscura* group", "the *Carabus auratus* species complex" (tag
  "Carabus auratus" if it reads as a species name, never the word "complex"). In "Drosophila *obscura* group" only the genus
  "Drosophila" is a name, the lone epithet "obscura" is not.
- **Gene and protein symbols:** "COI", "16S", "Hox", "cox1", "Rhodopsin".
- **Strain, voucher and culture codes:** "ATCC 25922", "CBS 123.45", "MNHN-IM-2000-1234", "K-12".
- **Virus names** in v1, including italic ICTV names ("SARS-CoV-2", "*Betacoronavirus*"). Do not tag them at all.
- **Names inside URLs, DOIs or e-mail addresses.**
- **Cultivar and breed names** ("Granny Smith"): tag only the botanical name ("Malus domestica").
- Person names, places and journals that happen to look like names ("Wolbachia Street").
- Words that are both a name and an ordinary word ("Acacia" in a street name, "Gemini"): tag only when the text uses it as the
  name of a taxon.

### Span boundaries are scored exactly

The score counts a name as right only if start and end match. That is why the pre-tags have to be fixed: gnfinder often
includes a bracket or a comma, italics tags often end one word too early or late. Use the character selection in the text,
check the region text in the region panel before you move on.

## 3. Tagging a name that occurs several times

Every occurrence is tagged separately. "Ophelia yangi" in the title, the abstract and a caption are three regions.

## 4. Acts

A nomenclatural act is a marker that creates or changes a name: "sp. nov.", "gen. nov.", "comb. nov.", "syn. nov." and
similar. Tag the **marker text only**, not the name, and link it to its name.

Procedure:

1. Select the `Act` label and mark the marker text ("sp. nov.").
2. In the region panel choose the `act_type` of that region.
3. Link it: select the Act region, use the relation button, click the `Name` region it applies to, and pick the relation
   `act_of`. The arrow goes **from the Act to the Name**.

An act without an `act_type` or without an `act_of` link stops the import with an error naming the document, so do not
leave half-finished acts in.

| `act_type` | Printed markers (all variants and spellings) |
|---|---|
| sp. nov. | sp. nov., sp. n., n. sp., sp. nov, new species |
| ssp. nov. | ssp. nov., subsp. nov., subsp. n. |
| var. nov. | var. nov., var. n. |
| gen. nov. | gen. nov., gen. n., n. gen. |
| subgen. nov. | subgen. nov., subgen. n. |
| fam. nov. | fam. nov., fam. n. |
| comb. nov. | comb. nov., comb. n. |
| stat. nov. | stat. nov., stat. n. (new rank) |
| stat. rev. | stat. rev. (revived status) |
| syn. nov. | syn. nov., syn. n. |
| nom. nov. | nom. nov., nom. n. (replacement name) |
| type designation | "holotype", "lectotype designated here", "neotype", "syntype designated" |

Rules:

- The name an act belongs to is the name it is printed with: "Ophelia yangi **sp. nov.**" links to "Ophelia yangi", even
  if another name stands closer. Gnfinder often attaches a marker to the wrong neighbour, that is exactly what we measure.
- If the marker is separated from the name (a treatment heading with the name and a later "Holotype" paragraph), link it to the
  name of that treatment.
- "gen. et sp. nov." and similar double markers: one Act region over the whole marker, the type of the lowest rank
  ("sp. nov."), linked to the species name. If the genus name is also introduced with its own marker, that one is a second act.
- A type designation counts only when the text designates or states a type of a named taxon. A mention of "the holotype of
  Carabus auratus" in passing is not an act.
- "nomen nudum", "nom. illeg.", "nom. cons." and the like are not acts in v1: tag the name, skip the marker.
- Plain mentions of an old name ("Ophelia limacina (Rathke, 1843)") are not acts.

## 5. Contexts

A context is the document's own **verbatim classification**: the higher taxa the authors give to place a name. Typical forms:

- in a title: "*Ophelia yangi* sp. nov. (Annelida: Opheliidae) from the South China Sea"
- in a heading: "Family Opheliidae", "Order Phyllodocida", "Phylum Annelida"
- in text: "a beetle of the family Carabidae, order Coleoptera"

Procedure:

1. Select the `Context` label and mark the **whole classification string** ("Annelida: Opheliidae" without the brackets; or the
   whole rank heading "Family Opheliidae").
2. Name the ranks in the `ranks` text area of that region, one entry per taxon, separated by semicolons, in the form
   `RANK: Name`, with the rank in capitals as in the COL rank list: `PHYLUM: Annelida; FAMILY: Opheliidae`.
   Allowed ranks include KINGDOM, PHYLUM, CLASS, ORDER, SUPERFAMILY, FAMILY, SUBFAMILY, TRIBE, GENUS and SUBGENUS.
3. A rank heading such as "Family Opheliidae" needs no `ranks` text, its first word is the rank.
4. The names inside a context are **also tagged as `Name`** regions. Regions may overlap, a Context region does not replace the
   Name regions.

Not a context: a bare mention of a higher taxon in running text with no classification given ("beetles such as Carabus"), a
reference to someone else's system ("Following Brown (2010), the order Coleoptera is…" is a context only if the document
itself places a name in it), and a list of related taxa. When in doubt whether a string classifies a name from this document,
tag it only when it appears in the title, a heading or immediately next to the name it classifies.

## 6. Main subject

Tick `main_subject: yes` on the `Name` regions of the names the document is **mainly about**:

- the taxa named in the title,
- taxa for which the document introduces a name or an act (every name linked to an Act region),
- taxa that the abstract or treatment clearly centres on, even if the title only gives a common name.

Ticking one occurrence of a name is enough. The importer collects the name texts, so ticking every occurrence does no harm.
Do not tick names that are only compared, listed as related, used as prey, hosts or outgroups, or that appear only in a
reference list, unless the document is about them.

A document can have several main subjects (a revision of three genera) or none (a checklist, a methods paper that only
mentions organisms in passing).

## 7. Using the Label Studio project

The project uses `eval/labelstudio/config.xml`. What you see:

- **Top:** the task-level choice `doc` with `ok` and `bad_text`.
- **Labels** (with hotkeys): `Name` (n), `Act` (a), `Context` (c). Select a label, then select the text in the document to
  create a region. The text is selectable by single characters.
- **Region panel (right):** select a region to see its extra fields. A `Name` region has the choice `main_subject` (`yes`).
  An `Act` region has `act_type`. A `Context` region has the text area `ranks`.
- **Relations:** select the `Act` region, click the relation button in the region panel, click the `Name` region, then set
  the relation label to `act_of`. The other relation, `abbrev_of`, is optional help for abbreviations and is not scored.
- **Pre-tags** are shown as regions of the first prediction. Use "Add as annotation" to copy them into your annotation, then
  edit. Nothing counts that is not in your submitted annotation.

Keep the text untouched. The document is the blocks title, abstract, headings and so on joined by one blank line, and a
region may not run across a blank line. If one name sits at the end of one block and one at the start of the next, they are two
regions.

## 8. When to use bad_text

Choose `bad_text` and submit without annotating if the text cannot be annotated fairly:

- the abstract is not text (a table of numbers, only a copyright line, "No abstract available"),
- the text is mostly garbled (broken encoding, stray HTML, OCR salad),
- the text is not in the language announced, so you cannot judge what is a name.

Do not use it for documents that merely contain no names, or whose names are hard. Those are annotated normally.

## 9. Worked examples

The examples show what to tag and why. Regions are written as `[Name: …]`, `[Act: …]`, `[Context: …]`. The pre-tags may
differ from them, always follow the rules.

### Example 1: a plain abstract with authorship, an abbreviation and a qualifier

> *Carabus auratus* Linnaeus, 1761 is common in arable fields. Records of *C. auratus* and of cf. *Carabus nemoralis* were
> compared with the Ground Beetle Atlas.

- `[Name: Carabus auratus]` Linnaeus, 1761 is common in arable fields. Records of `[Name: C. auratus]` and of cf.
  `[Name: Carabus nemoralis]` were compared with the Ground Beetle Atlas.

Why: authorship and year are outside the span. "C. auratus" is a name even though the full form was printed earlier. "cf." is
a qualifier and stays outside. "Ground Beetle Atlas" is a title in plain words, not a name. No `main_subject` is needed
unless the document is about these species, here the first one is the focus: tick it.

### Example 2: subgenus, infraspecific rank and hybrid

> Specimens of Carabus (Tachypus) auratus and Rosa canina var. glandulosa were found next to Mentha × piperita and the hybrid
> Mentha aquatica × M. spicata.

- `[Name: Carabus (Tachypus) auratus]`, `[Name: Rosa canina var. glandulosa]`, `[Name: Mentha × piperita]`
- `[Name: Mentha aquatica]` × `[Name: M. spicata]` (two names, the sign is outside)

Why: the subgenus in parentheses and the "var." marker are part of the name. A named hybrid keeps its sign, a formula is two
names.

### Example 3: what not to tag

> We sequenced COI and 16S from strain CBS 123.45 of *Escherichia coli* K-12 and from the *Drosophila* *obscura* group, as in
> https://github.com/example/Salmonella-tools. The golden jackal and carabids were used for comparison, as was SARS-CoV-2.

- `[Name: Escherichia coli]`, `[Name: Drosophila]`

Not tagged: "COI", "16S" (genes), "CBS 123.45" and "K-12" (strain codes), "*obscura* group" (species group, only the genus
"Drosophila" is a name), "Salmonella" inside the URL, "golden jackal" and "carabids" (common names), "SARS-CoV-2" (virus).

### Example 4: an act, linked to its name

> ***Ophelia yangi*** **sp. nov.** and *Ophelia limacina* (Rathke, 1843) **comb. nov.** are described from the Yellow Sea.

- `[Name: Ophelia yangi]` `[Act: sp. nov.]` with `act_type = sp. nov.`, relation `act_of` from the Act to "Ophelia yangi".
- `[Name: Ophelia limacina]` (Rathke, 1843) `[Act: comb. nov.]` with `act_type = comb. nov.`, `act_of` to "Ophelia limacina".

Why: the Act region is the marker text only. The authorship "(Rathke, 1843)" sits between the name and its marker and is in
neither span. Each marker is linked to the name printed right before it, not to the other name in the sentence.

### Example 5: a classification context in a title

> A new species of *Ophelia* (Annelida: Opheliidae) from the Yellow Sea

- `[Name: Ophelia]`, `[Name: Annelida]`, `[Name: Opheliidae]`
- `[Context: Annelida: Opheliidae]` with the `ranks` text `PHYLUM: Annelida; FAMILY: Opheliidae`

Why: the context region covers the whole classification string, brackets excluded, and records the rank of each taxon in
`ranks`. The three names are tagged as names too. "Yellow Sea" is a place. A heading "Family Opheliidae" would give
`[Name: Opheliidae]` and `[Context: Family Opheliidae]` with no `ranks` text.

### Example 6: main subject and a bad text

> Seasonal abundance of *Aedes aegypti* and *Aedes albopictus* in Miami, compared with *Culex quinquefasciens*. Vector
> competence was tested for *Aedes aegypti* only.

- `[Name: Aedes aegypti]`, `[Name: Aedes albopictus]`, `[Name: Culex quinquefasciens]`, and the second `[Name: Aedes aegypti]`
- `main_subject = yes` on the "Aedes aegypti" and "Aedes albopictus" regions of the title (the document is about both).
  "Culex quinquefasciens" is only a comparison, no `main_subject`. The misspelling is tagged as printed.

A document whose abstract reads "Abstract not available. © 2025 Publisher" or `Ã¢â‚¬â€œ Ã¢â€° ...` is `bad_text`: choose
`bad_text` at the top, no regions needed.

## 10. Quick checklist before you submit

- [ ] Every region text shows the name only: no authorship, no brackets, no trailing comma or full stop (apart from an abbreviation).
- [ ] No common names, genes, strains, viruses or URL names are tagged.
- [ ] Every `Act` has an `act_type` and an `act_of` link to a `Name`.
- [ ] Every `Context` has `ranks` text (unless it is a rank heading).
- [ ] `main_subject` is ticked for the document's focal names.
- [ ] `doc` is `ok`, or `bad_text` and nothing else is needed.

## 11. Maintainer notes (not for annotators)

**Offsets.** Our contract counts Unicode code points. Label Studio's Text tag runs in the browser, where string indices are
most likely UTF-16 units; we have not verified this against the frontend source. The config therefore sets
`saveTextResult="yes"` so every exported region carries `value.text`, and the importer checks it: offsets are read as code points
first, and only if the text then differs they are tried as UTF-16 units and converted. If neither fits, the import fails with
the docId and region id. One INFO line is logged per import when the UTF-16 reading was needed. Pre-tags are exported with
code point offsets, so if LS counts UTF-16, pre-tag highlights after an astral character (an emoji, some rare script
characters) may render shifted by one per such character. Annotators should fix shifted highlights; whatever they save is
imported correctly either way.

**Scripted Label Studio (1.23.x).** Legacy API tokens are disabled for new organizations (HTTP 401 "legacy token authentication has
been disabled"). To drive LS through its REST API with `LABEL_STUDIO_USER_TOKEN`:

1. Choose local-only values in your shell (or a git-ignored `.env`), never commit them:
   `export LABEL_STUDIO_USERNAME=<email> LABEL_STUDIO_PASSWORD=<random> LABEL_STUDIO_USER_TOKEN=<random 40 hex>`.
2. `docker compose --profile annotate up -d labelstudio` and wait for `http://127.0.0.1:8090/health`.
3. Once per fresh LS volume, enable legacy tokens for the organization:
   `docker compose --profile annotate exec -T labelstudio sh -c 'cd /label-studio/label_studio && python manage.py shell -c "from organizations.models import Organization; j=Organization.objects.first().jwt; j.legacy_api_tokens_enabled=True; j.save()"'`
4. Call the API with `Authorization: Token $LABEL_STUDIO_USER_TOKEN`: `POST /api/projects` (JSON `title`, `label_config` =
   contents of `eval/labelstudio/config.xml`), `POST /api/projects/<id>/import` (the tasks file),
   `POST /api/tasks/<id>/annotations` (`result`, `was_cancelled:false`), `GET /api/projects/<id>/export?exportType=JSON`.
5. `ls-import --export <file>` checks every document's text hash against `eval/corpus/curated/manifest.tsv`.
