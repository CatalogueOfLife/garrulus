# M0/M1 Finder and Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Naming:** the internal id is `nametracker` and the public name is **Garrulus** (see the platform plan and
> `docs/brand/README.md`).
>
> Requires Task 1 (parent POM and modules) and Task 2 (docker-compose) of
> `2026-11-14-m0-platform-skeleton.md`. After those, this plan runs independently of the platform plan's remaining
> tasks.

**Goal:** The name-finding contract (`finder-api`), document converters, the default `PipelineNameFinder` (gnfinder + own stages), an HTTP finder service, and an evaluation harness (Pensoft silver set, ~100-document hand-checked gold set via Label Studio, PDF path via GROBID) with committed baseline reports.

**Architecture:** `finder-api` holds the document model, result model, the `NameFinder` SPI and the JSON contract. Offsets are always Unicode code points. `finder-core` converts JATS/TaxPub, TEI and abstracts into `Document`s and runs the pipeline:

```
candidate generators (gnfinder, COL dictionary, rank-suffix rules, italics/markup)
 → merge → authorship + qualifiers → abbreviation expansion → name-parser-rust → judge
 → act tagger → classification-context extractor
```

`finder-service` exposes it over HTTP; `RemoteNameFinder` consumes that contract, so another implementation can be swapped in. `eval` builds the corpora, runs any `NameFinder` and scores it.

**Tech Stack:** Java 25, Jackson 3 (`tools.jackson.databind`, managed by Spring Boot 4.1), `org.gbif:name-parser-api` + `org.gbif.nameparser:name-parser-rust` (+ `linux-x86_64`, `osx-aarch_64` classifiers, as in Blixa), StAX for XML, `java.net.http.HttpClient`, fastutil (primitive hash sets), picocli (eval CLI), Testcontainers `GenericContainer` for gnfinder/GROBID, WireMock for HTTP stubs, JUnit 5 + AssertJ.

**Spec:** `docs/design.md` §3, §4, *Evaluation*, *Roadmap* M0/M1, *Verification*, *Open items*.

## Global Constraints

- **Naming:**
  - Packages go under `org.catalogueoflife.garrulus.finder.*` and `org.catalogueoflife.garrulus.eval`.
  - The public name **Garrulus** is used in the finder-service OpenAPI title and in every outgoing HTTP User-Agent:
    - `Garrulus/<version> (+https://www.catalogueoflife.org)`
    - `Garrulus-eval/0.1 (...)` for the eval fetchers
- **Offsets:**
  - Every offset in the contract is a **Unicode code-point index into `Block.text`**, `end` exclusive.
  - Same convention as gnfinder (default, no `bytesOffset`) and Label Studio.
  - Java code converts with `String.offsetByCodePoints`; never mix in UTF-16 indices.
- **JSON:** camelCase, nulls omitted, enums by name, via `FinderJson.mapper()` (Task 1). The golden file `finder-api/src/test/resources/contract/find-response.json` is the contract example.
- **`Rank` and `NomCode`:** from `org.gbif.nameparser.api`.
- **gnfinder:**
  - Image `gnames/gnfinder:v1.4.0`, API `POST {GNFINDER_URL}/api/v1/find`.
  - Always `verification:false`: no dependency on `verifier.globalnames.org`; we expand abbreviations ourselves.
  - Other settings: `ambiguousNames:true`, `oddsDetails:true`, `language:"detect"`, `wordsAround:0`.
  - Response fields: `verbatim, name, abbrName, cardinality, oddsLog10, start, end, annotationNomen, annotationNomenType`.
  - `verbatim` spans include surrounding punctuation ("(Coleoptera:"). Requests time out after 1 minute.
- **GROBID:**
  - Image `grobid/grobid:0.9.1-crf`.
  - `POST {GROBID_URL}/api/processFulltextDocument`, multipart field `input`, `consolidateHeader=0`, `consolidateCitations=0`.
  - HTTP 503 means busy: retry after 10 s, up to 5 times.
  - Its TEI has **no italics**.
- **Pensoft XML:**
  - URL `https://{journal}.pensoft.net/article/{id}/download/xml/`, where `{id}` is the last DOI segment (`10.3897/zookeys.1297.218447` → `218447`; BDJ drops the `e`: `10.3897/BDJ.14.e202849` → `202849`).
  - Namespace `tp` = `http://www.plazi.org/taxpub`.
  - OpenAlex source ids: ZooKeys `S199213172`, PhytoKeys `S138605562`, MycoKeys `S4210227917`, BDJ `S2764367193`.
- **COL dictionary input:** `NameUsage.tsv` from `https://download.checklistbank.org/col/xr_latest_coldp.zip` (1.39 GB zip, 3.5 GB TSV). Relevant columns, verified: `col:ID, col:status, col:scientificName, col:rank, col:uninomial, col:genericName, col:infragenericEpithet, col:specificEpithet, col:infraspecificEpithet, col:code, col:genus, col:family, col:order, col:class, col:kingdom, clb:taxGroup`.
- **Free by default:**
  - OpenAlex calls in `eval` use the optional `OPENALEX_API_KEY` (free allowance) and only list/single-record endpoints, never `content.openalex.org`.
  - Pensoft, Europe PMC, GROBID and gnfinder are free.
  - No LLM anywhere in this plan; the `LlmJudge` stays a documented, unimplemented option.
- **Licences in `eval/`:**
  - Only CC-BY/CC0 texts are committed as text.
  - Curated documents from other sources are committed as ids + annotations + a SHA-256 of the text, and re-fetched on load.
- **Tuning discipline:** every corpus has a fixed `dev`/`test` split recorded in its manifest. Weights and thresholds are tuned on `dev` only; reports quote `test`.
- **Commits:** conventional style, no tool attribution lines.

## Review Focus

- **Pensoft XML starts with a DOCTYPE pointing to a remote DTD.** The converter must neither fetch it nor resolve external entities (no network, no XXE) → test `doesNotResolveExternalDtd` (Task 2).
- **Characters outside the Basic Multilingual Plane, and combining accents** (e.g. "𝐴", "Ü" as U+0055 U+0308) before a name: offsets must still point at the name → tests `offsetsSurviveSupplementaryCharacters` (Task 1) and `umlautTextUsesCodePointOffsets` (Task 7).
- **A genus that is also a common word, at sentence start in an ecology abstract** ("Major changes in…"): it must not be accepted without supporting evidence → test `commonWordGenusAtSentenceStartRejected` (Task 16).
- **"sp. nov." right after a parenthesised higher-taxon chain**: the act belongs to the species, not the family (gnfinder gets this wrong) → test `spNovAttachesToSpeciesNotFamilyChain` (Task 14).
- **An empty abstract and a 300-page monograph:**
  - empty input gives an empty result, not an exception → test `emptyDocumentYieldsEmptyResult` (Task 16)
  - long input is chunked at block boundaries below gnfinder's limits → test `largeDocumentIsChunkedAtBlockBoundaries` (Task 7)

---

### Task 1: `finder-api` — document model, result model, SPI, JSON contract

**Files:**
- Create: `finder-api/src/main/java/org/catalogueoflife/garrulus/finder/api/` — `Document.java, Block.java, BlockType.java, StyleSpan.java, Style.java, NameFinder.java, FinderInfo.java, FinderOptions.java, FinderResult.java, Mention.java, NameKind.java, Evidence.java, Generator.java, JudgeVerdict.java, Act.java, ActType.java, ClassificationContext.java, RankedName.java, ContextScope.java, CodePoints.java, FinderJson.java`
- Create: `finder-api/src/main/resources/finder-api.yaml` (OpenAPI 3.1: `POST /v1/find`, `GET /v1/info`, schemas = the records below)
- Test: `finder-api/src/test/java/.../finder/api/{ContractJsonTest.java, CodePointsTest.java}`, `finder-api/src/test/resources/contract/find-response.json`

**Interfaces:**
- Produces (all records immutable; lists never null):
  - **Document model:**
    - `record Document(String id, @Nullable String language, List<Block> blocks, Map<String,String> meta)`
    - `record Block(String id, BlockType type, int level, String text, List<StyleSpan> styles, List<String> sectionPath)`
    - `enum BlockType { TITLE, ABSTRACT, HEADING, PARAGRAPH, CAPTION, TABLE_CELL, FOOTNOTE, REFERENCE }`
    - `record StyleSpan(int start, int end, Style style)`
    - `enum Style { ITALIC, BOLD, SMALLCAPS, TAXON_NAME }`. `TAXON_NAME` marks a name tagged by the publisher (TaxPub).
  - **SPI:**
    - `interface NameFinder { FinderInfo info(); FinderResult find(Document doc, FinderOptions opts); }`
    - `record FinderInfo(String name, String version, String configHash, @Nullable String dictionaryKey)`
    - `record FinderOptions(boolean includeRejected, @Nullable NomCode codeHint)` with `static FinderOptions defaults()`
    - `record FinderResult(FinderInfo info, List<Mention> mentions, List<Act> acts, List<ClassificationContext> contexts, List<Mention> rejected, Map<String,String> diagnostics)`
  - **Mentions:**
    - `record Mention(String id, String blockId, int start, int end, String verbatim, String name, NameKind kind, @Nullable Rank rank, @Nullable String authorship, @Nullable String qualifier, double confidence, Evidence evidence, @Nullable String actId, @Nullable String contextId, Map<String,String> hints)`
    - `enum NameKind { UNINOMIAL, BINOMIAL, TRINOMIAL, INFRAGENERIC, ABBREVIATED }`
    - `record Evidence(Set<Generator> generators, @Nullable Double gnfinderOddsLog10, @Nullable JudgeVerdict judge)`
    - `enum Generator { GNFINDER, DICTIONARY, SUFFIX, ITALIC, TAXON_MARKUP }`
    - `record JudgeVerdict(String judge, double score, boolean accepted, @Nullable String correctedName)`
  - **Acts:**
    - `record Act(String id, ActType type, String marker, String mentionId, double confidence)`
    - `enum ActType { SP_NOV, SSP_NOV, VAR_NOV, GEN_NOV, SUBGEN_NOV, FAM_NOV, COMB_NOV, STAT_NOV, STAT_REV, SYN_NOV, NOM_NOV, TYPE_DESIGNATION }`
  - **Classification contexts:**
    - `record ClassificationContext(String id, String verbatim, List<RankedName> ranks, ContextScope scope, String blockId, @Nullable String untilBlockId)`
    - `record RankedName(Rank rank, String name)`
    - `enum ContextScope { DOCUMENT, SECTION, TREATMENT }`
  - **Offset helpers:** `final class CodePoints { static int length(String s); static String slice(String s, int start, int end); static int toCharIndex(String s, int cp); static int toCodePoint(String s, int charIndex); }`
  - **JSON:** `final class FinderJson { static JsonMapper mapper(); }` (Jackson 3; camelCase, `NON_NULL`, no failure on unknown properties).
  - Dependencies: `name-parser-api`, `jackson-databind` (3.x). Nothing else.

- [ ] **Step 1: Write failing tests**

```java
class ContractJsonTest {
  @Test void goldenResponseRoundTrips() {   // read contract/find-response.json → FinderResult → JSON → tree-equal to file
  }
  @Test void unknownPropertiesAreIgnored() { /* golden JSON plus {"future":1} still parses */ }
  @Test void nullsAreOmitted() { /* Mention with authorship=null serialises without "authorship" */ }
}
class CodePointsTest {
  @Test void offsetsSurviveSupplementaryCharacters() {
    String s = "𝐴 Carabus nigrus";             // U+1D434 is two UTF-16 units, one code point
    assertThat(CodePoints.slice(s, 2, 16)).isEqualTo("Carabus nigrus");
    assertThat(CodePoints.toCharIndex(s, 2)).isEqualTo(3);
  }
}
```
  `find-response.json` shows one document with:
  - Mention `m1` "Carabus nigrus" (BINOMIAL, SPECIES, generators `[GNFINDER, DICTIONARY]`, `actId:"a1"`, `contextId:"c1"`)
  - Mention `m2` "C. nigrus" (ABBREVIATED, name "Carabus nigrus")
  - Act `a1` SP_NOV with marker "sp. nov."
  - Context `c1` DOCUMENT `[ORDER Coleoptera, FAMILY Carabidae]`
- [ ] **Step 2: Run** `mvn -B -pl finder-api test` → FAIL (classes missing).
- [ ] **Step 3: Implement** the records, enums, `CodePoints`, `FinderJson`, and `finder-api.yaml` (schemas mirror the records; describe the offset convention in `info.description`).
- [ ] **Step 4: Run** same command → PASS.
- [ ] **Step 5: Commit** `feat(finder-api): document/result model, NameFinder SPI and JSON contract`

### Task 2: Text normalisation and the JATS/TaxPub converter

**Files:**
- Create: `finder-core/src/main/java/org/catalogueoflife/garrulus/finder/doc/{TextNormalizer.java, JatsConverter.java, JatsOptions.java, Conversion.java, TaxonMarkup.java, NamePart.java, StatusMarkup.java}`
- Test: `finder-core/src/test/java/.../finder/doc/{TextNormalizerTest.java, JatsConverterTest.java}`, fixture `finder-core/src/test/resources/jats/zookeys-218447-excerpt.xml`. This is a trimmed copy of the real article (CC-BY) containing:
  - the DOCTYPE
  - the title and abstract
  - one paragraph with "*Ophelia* Savigny, 1822 (Opheliidae Malmgren, 1867)"
  - an abbreviated "*O. limacina*"
  - one `tp:taxon-treatment` with `tp:nomenclature` "Ophelia yangi" + `tp:taxon-status` "sp. nov."
  - a figure caption, a table cell, and two references

**Interfaces:**
- Consumes: `Document`, `Block`, `StyleSpan` (Task 1).
- Produces:
  - `final class TextNormalizer { static String normalize(String s); }`: NFC, ligatures (ﬁ ﬂ ﬀ ﬃ ﬄ → letters), soft hyphen U+00AD removed, all whitespace runs → one space, no-break space → space. Callers apply it per text node while appending, so style spans stay aligned.
  - `record JatsOptions(boolean keepTaxonMarkup, boolean includeReferences)` with `defaults()` = `(true, true)`
  - `record Conversion(Document document, List<TaxonMarkup> taxonMarkup, List<StatusMarkup> statusMarkup)`
  - `record TaxonMarkup(String blockId, int start, int end, List<NamePart> parts)`, `record NamePart(String type, @Nullable String reg, String verbatim)`
  - `record StatusMarkup(String blockId, int start, int end, String status, int taxonMarkupIndex)`: links `tp:taxon-status` to the preceding `tp:taxon-name` of the same `tp:nomenclature`.
  - `final class JatsConverter { Conversion convert(InputStream xml, String docId, JatsOptions opts); }`
    - Element mapping:
      - `article-title` → TITLE
      - `abstract//p` → ABSTRACT
      - `sec/title` and `tp:treatment-sec/title` → HEADING (level = nesting depth)
      - `tp:nomenclature` → HEADING (level = depth of its treatment + 1)
      - `p` → PARAGRAPH
      - `fig/caption`, `table-wrap/caption` → CAPTION
      - `td`/`th` → TABLE_CELL
      - `fn` → FOOTNOTE
      - `ref` → REFERENCE
    - Markup mapping: `italic`→ITALIC, `bold`→BOLD, `sc`→SMALLCAPS, `tp:taxon-name`→TAXON_NAME (only if `keepTaxonMarkup`).
    - Block ids `b1…bn` in document order; `sectionPath` = enclosing section titles.
    - StAX with `SUPPORT_DTD=false`, `IS_SUPPORTING_EXTERNAL_ENTITIES=false`, `XMLResolver` that throws; unknown entity references become a space.

- [ ] **Step 1: Write failing tests**

```java
@Test void mapsBlockTypes() { /* fixture → first block TITLE, abstract blocks ABSTRACT, caption CAPTION, table cell TABLE_CELL, refs REFERENCE */ }
@Test void italicAndTaxonSpansPointAtNames() {
  // paragraph containing "Ophelia Savigny, 1822 (Opheliidae Malmgren, 1867)": ITALIC span slices to "Ophelia";
  // TAXON_NAME spans slice to "Ophelia" and "Opheliidae"; TaxonMarkup parts = [genus reg=Ophelia], [family reg=Opheliidae]
}
@Test void abbreviatedTaxonMarkupKeepsReg() { /* "O. limacina" → parts [genus reg=Ophelia verbatim "O."], [species reg=limacina] */ }
@Test void treatmentHeadingAndStatus() { /* HEADING block text starts "Ophelia yangi"; StatusMarkup status "sp. nov." linked to that heading's TaxonMarkup */ }
@Test void withoutTaxonMarkupNoTaxonSpans() { /* JatsOptions(false,true) → no TAXON_NAME styles, taxonMarkup still extracted */ }
@Test void doesNotResolveExternalDtd() { /* DOCTYPE pointing at http://unresolvable.invalid/x.dtd converts fine, offline */ }
// TextNormalizerTest: ligatures, soft hyphen, NBSP, NFC ("Über" → "Über")
```
- [ ] **Step 2: Run** `mvn -B -pl finder-core -am test -Dtest='JatsConverterTest,TextNormalizerTest'` → FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): JATS/TaxPub converter and text normalisation`

### Task 3: Abstract converter and `FlatText`

**Files:**
- Create: `finder-core/src/main/java/.../finder/doc/{AbstractConverter.java, FlatText.java}`
- Test: `.../finder/doc/{AbstractConverterTest.java, FlatTextTest.java}`

**Interfaces:**
- Produces:
  - **`AbstractConverter`:**
    - `static String fromInvertedIndex(Map<String, List<Integer>> index)`: OpenAlex `abstract_inverted_index`, words joined by single spaces in position order.
    - `static Document fromParts(String docId, @Nullable String title, @Nullable String abstractText, @Nullable String jatsAbstract)`: a TITLE block plus ABSTRACT blocks. If `jatsAbstract` (Crossref `<jats:p>` with `<jats:italic>`) is given, it wins and keeps italics.
  - **`FlatText`:** blocks joined by `"\n\n"`. It is used for gnfinder requests and Label Studio tasks.
    - `static FlatText of(Document doc)`
    - `String text()`
    - `record Position(String blockId, int offset)`, `Position toBlock(int flatCp)`
    - `int toFlat(String blockId, int blockCp)`
    - `List<FlatText> chunks(int maxCodePoints)`: splits only at block boundaries. Each chunk keeps its own mapping.

- [ ] **Step 1: Failing tests:**
  - `invertedIndexRestoresWordOrder`
  - `crossrefJatsAbstractKeepsItalics`: "<jats:p>The genus <jats:italic>Carabus</jats:italic> …" gives an ITALIC span on "Carabus"
  - `flatOffsetsMapBackToBlocks`: round trip for every code point of a 3-block document containing "𝐴"
  - `chunksSplitOnlyAtBlockBoundaries`
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): abstract converter and flat-text offset mapping`

### Task 4: TEI converter and de-hyphenation

**Files:**
- Create: `finder-core/src/main/java/.../finder/doc/{TeiConverter.java, Dehyphenator.java}`
- Test: `.../finder/doc/{TeiConverterTest.java, DehyphenatorTest.java}`, fixture `tei/grobid-sample.tei.xml`. Write the fixture by hand in GROBID 0.9 structure (`teiHeader/fileDesc/titleStmt/title`, `profileDesc/abstract/div/p`, `text/body/div/head|p|figure/figDesc`, `back/div/listBibl/biblStruct`), with one paragraph containing "Pterosti-\nchus niger".

**Interfaces:**
- Produces:
  - `final class TeiConverter { Document convert(InputStream tei, String docId); }`
    - Mapping: title→TITLE, abstract p→ABSTRACT, `head`→HEADING (level 1), body `p`→PARAGRAPH, `figDesc`→CAPTION, `table//cell`→TABLE_CELL, `note`→FOOTNOTE, `biblStruct` (text content)→REFERENCE.
    - No style spans (GROBID emits no italics).
    - Same XML hardening as Task 2.
  - `final class Dehyphenator { static String join(String text, Predicate<String> isKnownWord); }`
    - Pattern `(\p{L}+)-\s*\n\s*(\p{Ll}+)`: joined without the hyphen if `isKnownWord(joined.toLowerCase())`, else the hyphen is kept and the line break removed.
    - It is applied by `TeiConverter` callers, with the dictionary predicate from Task 9.

- [ ] **Step 1: Failing tests:**
  - `mapsTeiBlocks`
  - `referencesAreReferenceBlocks`
  - `joinsHyphenatedKnownWord`: "Pterosti-\nchus" becomes "Pterostichus" when the predicate knows "pterostichus"
  - `keepsRealHyphen`: "north-\neast" becomes "north-east" when unknown
- [ ] **Step 2–4:** run → FAIL, implement, run → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): TEI converter and dictionary-guided de-hyphenation`

### Task 5: Eval corpus model, TaxPub gold extractor and Pensoft fetcher

**Files:**
- Create: `eval/src/main/java/org/catalogueoflife/garrulus/eval/corpus/{GoldDocument.java, GoldName.java, GoldAct.java, GoldContext.java, CorpusManifest.java, CorpusStore.java, TaxPubGoldExtractor.java, PensoftFetcher.java, OpenAlexClient.java}`
- Create: `eval/corpus/pensoft/manifest.tsv`, `eval/corpus/pensoft/gold.jsonl.gz` (generated in Step 6)
- Test: `eval/src/test/java/.../eval/corpus/{TaxPubGoldExtractorTest.java, PensoftFetcherTest.java, CorpusStoreTest.java}`

**Interfaces:**
- Consumes: `JatsConverter`, `Conversion`, `TaxonMarkup`, `StatusMarkup` (Task 2), `FinderJson` (Task 1).
- Produces:
  - **Gold records:**
    - `record GoldDocument(String docId, String corpus, String split, String license, boolean textRetained, String textSha256, Document document, List<GoldName> names, List<GoldAct> acts, List<GoldContext> contexts, Set<String> mainSubjectNames)`
    - `record GoldName(String blockId, int start, int end, String text, @Nullable String fullName, @Nullable Rank rank)`: `fullName` is joined from `reg` attributes, so "O. limacina" gives "Ophelia limacina".
    - `record GoldAct(String blockId, int start, int end, ActType type, String nameText)`
    - `record GoldContext(String blockId, List<RankedName> ranks)`
  - `TaxPubGoldExtractor`: `GoldDocument extract(InputStream xml, String docId, String split)`
    - Runs `JatsConverter` with `keepTaxonMarkup=true`, turns `TaxonMarkup` into `GoldName`s and `StatusMarkup` into `GoldAct`s.
    - Status text is mapped to `ActType` by a private `statusToAct(String)` covering "sp. nov.", "sp. n.", "ssp. nov.", "subsp. nov.", "gen. nov.", "gen. n.", "comb. nov.", "comb. n.", "syn. nov.", "syn. n.", "stat. nov.", "stat. rev.", "nom. nov.". Task 14 replaces it with `ActPatterns.classify`, so the gold extractor and the finder share one vocabulary.
    - Then **removes all TAXON_NAME spans** from the stored document, so mode A never sees gold markup.
    - Skips name parts with empty text.
  - `CorpusManifest` TSV columns: `docId, doi, journal, license, split, xmlUrl, pdfUrl`.
  - `CorpusStore`:
    - `static void write(Path jsonlGz, List<GoldDocument>)`
    - `static List<GoldDocument> read(Path jsonlGz, @Nullable TextRefetcher refetcher)`: documents with `textRetained=false` are re-fetched and their SHA-256 verified, otherwise loading fails.
  - `OpenAlexClient(HttpClient, @Nullable String apiKey)`:
    - `List<JsonNode> listWorks(String filter, int max)`: cursor paging, `per-page=100`, adds `api_key` if set
    - `JsonNode getWork(String openAlexId)`
  - `PensoftFetcher`:
    - `List<CorpusManifest.Row> sample(int n, int devCount, long seed)`
      - OpenAlex filter `primary_location.source.id:S199213172|S138605562|S4210227917|S2764367193,open_access.is_oa:true,type:article,from_publication_date:2024-01-01`
      - Deterministic shuffle with `seed`; the first `devCount` rows are `dev`, the rest `test`.
    - `static String xmlUrl(String doi)` and `static String pdfUrl(String doi)` (`…/download/pdf/`; verify in Step 6)
    - `GoldDocument fetch(Row)`: 1 request/s, `User-Agent: Garrulus-eval/0.1 (+https://www.catalogueoflife.org)`

- [ ] **Step 1: Failing tests:**
  - `extractsGoldNamesFromFixture`: the Task 2 fixture gives gold names including "Ophelia", "Opheliidae", "O. limacina" (fullName "Ophelia limacina").
  - `goldActFromTaxonStatus`: an SP_NOV act on "Ophelia yangi".
  - `storedDocumentHasNoTaxonSpans`
  - `xmlUrlFromDoi`:

    ```java
    assertThat(PensoftFetcher.xmlUrl("10.3897/zookeys.1297.218447")).isEqualTo("https://zookeys.pensoft.net/article/218447/download/xml/");
    assertThat(PensoftFetcher.xmlUrl("10.3897/BDJ.14.e202849")).isEqualTo("https://bdj.pensoft.net/article/202849/download/xml/");
    ```
  - `refetchMismatchFails`: CorpusStore fails loudly when a re-fetched text's hash differs.
  - `sampleIsDeterministic`: WireMock-stubbed OpenAlex, same seed gives the same rows.
- [ ] **Step 2–4:** run `mvn -B -pl eval -am test` → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(eval): gold corpus model, TaxPub gold extraction, Pensoft fetcher`
- [ ] **Step 6: Build the silver corpus** (CLI arrives in Task 6; for now a test-scoped main or wait for Task 6)
  - Run: `fetch-pensoft --n 300 --dev 100 --seed 42 --out eval/corpus/pensoft`
  - Check one row's `pdfUrl` with `curl -sI` (expect 200, `application/pdf`).
  - Expected: `manifest.tsv` has 300 rows (100 dev / 200 test) and `gold.jsonl.gz` exists, all licences CC-BY.
  - Commit `chore(eval): pensoft silver corpus (300 docs, CC-BY)`.

### Task 6: Scorer, reports, trivial finder and the eval CLI

**Files:**
- Create: `eval/src/main/java/.../eval/score/{Scorer.java, ScoreOptions.java, Metrics.java, EvalReport.java, ErrorSample.java, ReportWriter.java}`
- Create: `eval/src/main/java/.../eval/{EvalCli.java, ItalicsFinder.java, FinderFactory.java}`
- Modify: `eval/pom.xml` (picocli, `exec-maven-plugin` with `mainClass` `EvalCli`)
- Test: `eval/src/test/java/.../eval/score/ScorerTest.java`, `.../eval/ItalicsFinderTest.java`

**Interfaces:**
- Consumes: `GoldDocument`, `CorpusStore` (Task 5), `NameFinder`, `FinderResult` (Task 1).
- Produces:
  - **Scoring inputs:**
    - `record ScoreOptions(MatchMode mode, Set<BlockType> excludedBlocks, boolean documentLevel)`, `enum MatchMode { STRICT, OVERLAP }`
    - Defaults: Pensoft excludes `REFERENCE` (its reference tagging is incomplete); curated excludes nothing.
  - **Metrics:**
    - `record Metrics(int tp, int fp, int fn)` with `precision()`, `recall()`, `f1()`
    - `record EvalReport(String finder, FinderInfo info, String corpus, String split, String mode, Metrics names, Map<String,Metrics> byCategory, Metrics acts, Metrics contexts, Metrics mainSubject, List<ErrorSample> falsePositives, List<ErrorSample> falseNegatives)`
    - Categories: `kind:UNINOMIAL|BINOMIAL|TRINOMIAL|ABBREVIATED`, `block:<BlockType>`, `lang:<language>`.
  - `Scorer.score(List<GoldDocument>, Map<String, FinderResult>, ScoreOptions) -> EvalReport`. Matching rules:
    - **Strict:** same block, same `start`/`end`.
    - **Overlap:** same block and the spans intersect.
    - **Document level:** the set of `fullName ?: text` versus `Mention.name`, compared after whitespace normalisation.
    - **Acts:** the pair `(type, normalised name)` per document.
    - **Contexts:** the pair `(rank, name)` per document.
  - `ReportWriter.write(EvalReport, Path dir)` writes `{date}-{finder}-{corpus}-{split}-{mode}.md` and `.json`. The Markdown holds a metrics table plus up to 30 false-positive and 30 false-negative samples with ±40 code points of context.
  - `ItalicsFinder implements NameFinder`: name `"italics"`, the trivial baseline. Each ITALIC span of 1–3 words whose first word is capitalised (or an initial with a period) becomes a Mention, with kind from the word count and confidence 0.5.
  - **`EvalCli` (picocli) subcommands:**
    - `fetch-pensoft --n --dev --seed --out`
    - `run --finder italics|gnfinder|pipeline|remote:<url> --corpus <dir> --split dev|test --mode A|B [--out eval/reports]`
    - `curated-select`, `ls-export`, `ls-import` (Task 8)
    - `build-dictionary` (Task 9)
    - `FinderFactory` builds a finder from its name. It registers finders as they arrive: `italics` now, `gnfinder` in Task 7, `pipeline` in Task 16, `remote:<url>` in Task 17. An unknown name fails with the list of available ones.

- [ ] **Step 1: Failing tests:**
  - `strictMatchCountsExactSpansOnly`
  - `overlapMatchAcceptsBoundaryDifferences`
  - `documentLevelUsesFullNames`: "O. limacina" with fullName "Ophelia limacina" matches the mention name "Ophelia limacina".
  - `referencesExcludedForPensoft`
  - `metricsHandleZeroDivision`: precision of 0/0 is 0, not NaN.
  - `ItalicsFinderTest`: an italic "Carabus nigrus" gives one BINOMIAL mention; an italic "et al." gives none.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: M0 check** (design: "eval CLI scores a trivial finder on 50 Pensoft articles")
  - Run: `mvn -B -q -pl eval -am package && mvn -pl eval exec:java -Dexec.args="run --finder italics --corpus eval/corpus/pensoft --split dev --mode A"`
  - Expected: a report under `eval/reports/` with non-zero P/R over the 100 dev docs.
- [ ] **Step 6: Commit** `feat(eval): scorer, reports, italics baseline and CLI`

### Task 7: gnfinder client, candidate SPI, gnfinder generator and gnfinder-only baseline

**Files:**
- Create: `finder-core/src/main/java/.../finder/gnfinder/{GnfinderClient.java, GnfinderRequest.java, GnfinderResponse.java, GnName.java, GnfinderGenerator.java, GnfinderOnlyFinder.java}`
- Create: `finder-core/src/main/java/.../finder/pipeline/{Candidate.java, CandidateGenerator.java, GenerationContext.java}`
- Test: `finder-core/src/test/java/.../finder/gnfinder/{GnfinderClientIT.java, GnfinderGeneratorTest.java, SpanTrimTest.java}`

**Interfaces:**
- Consumes: `FlatText` (Task 3), `Document`, `Mention`, `Act`, `NameFinder` (Task 1).
- Produces:
  - **Candidate SPI:**
    - `record Candidate(String blockId, int start, int end, String verbatim, String name, Generator generator, Map<String,Object> features)`. Feature keys used later: `oddsLog10`, `cardinality`, `annotationNomenType`, `ambiguousWord`, `suffixRank`, `dictHit`.
    - `interface CandidateGenerator { Generator id(); List<Candidate> generate(Document doc, FlatText flat, GenerationContext ctx); }`
    - `record GenerationContext(@Nullable NomCode codeHint)`. Generators get other dependencies (gnfinder client, dictionary) through their constructors.
  - **Client:**
    - `GnfinderClient(URI baseUrl, HttpClient http)`, `GnfinderResponse find(String text)`
    - Sends `{"text":…, "ambiguousNames":true, "oddsDetails":true, "language":"detect", "verification":false, "wordsAround":0}`.
    - `record GnName(int cardinality, String verbatim, String name, @Nullable String abbrName, double oddsLog10, int start, int end, @Nullable String annotationNomen, String annotationNomenType)`
  - **Generator:**
    - `GnfinderGenerator(GnfinderClient client, int maxChunkCodePoints /* 100_000 */) implements CandidateGenerator`
    - Calls once per `FlatText.chunks(maxChunkCodePoints)`.
    - Trims `verbatim` to the tight name span: strip leading/trailing characters that are not letters, keeping the trailing `.` of an abbreviated genus.
    - Maps offsets back to blocks; drops spans that cross a block boundary.
  - **Baseline:**
    - `GnfinderOnlyFinder(GnfinderClient) implements NameFinder`, name `"gnfinder"`.
    - Candidates become mentions directly: kind from `cardinality` and an initial-period pattern, confidence `min(1, max(0, oddsLog10/10))`.
    - Acts come from `annotationNomenType != NO_ANNOT`, attached to that same name, deliberately reproducing gnfinder's behaviour for the baseline.

- [ ] **Step 1: Failing tests**

```java
class GnfinderClientIT {   // GenericContainer("gnames/gnfinder:v1.4.0").withExposedPorts(8999), wait for /api/v1/ping
  @Test void findsNamesInSampleText() {
    // text: "A new species of Carabus (Coleoptera: Carabidae), Carabus nigrus sp. nov., is described. C. nigrus differs from Pterostichus niger."
    // names verbatim (after trimming): Carabus, Coleoptera, Carabidae, Carabus nigrus, C. nigrus, Pterostichus niger
    // "C. nigrus" has name "C. nigrus" (no verification, so no expansion)
  }
  @Test void umlautTextUsesCodePointOffsets() { /* "Über die Käfer: Carabus nigrus" → mention start 16 */ }
  @Test void hyphenatedLineBreakBehaviour() { /* "Pterosti-\nchus niger": record what gnfinder returns; assertion documents the observed behaviour (input for ADR-0003) */ }
}
class SpanTrimTest { @Test void trimsPunctuationButKeepsAbbreviationDot() { /* "(Coleoptera:"→"Coleoptera"; "C. nigrus"→unchanged; "niger."→"niger" */ } }
class GnfinderGeneratorTest {   // WireMock stub with the recorded JSON
  @Test void largeDocumentIsChunkedAtBlockBoundaries() { /* 3 blocks, maxChunk smaller than 2 blocks → 2 requests, offsets map to the right blocks */ }
}
```
- [ ] **Step 2: Run** `mvn -B -pl finder-core -am verify -Dit.test=GnfinderClientIT -Dtest='SpanTrimTest,GnfinderGeneratorTest'` → FAIL.
- [ ] **Step 3: Implement.** **Step 4:** run → PASS.
- [ ] **Step 5: Baseline report:** `eval run --finder gnfinder --corpus eval/corpus/pensoft --split dev --mode A` gives a report in `eval/reports/`.
- [ ] **Step 6: Commit** `feat(finder-core): gnfinder client, candidate SPI and gnfinder-only baseline`

### Task 8: Hand-checked curated set — selection, Label Studio round trip, guidelines

**Files:**
- Create: `eval/src/main/java/.../eval/curated/{CuratedSelector.java, LabelStudioExporter.java, LabelStudioImporter.java, PreTagger.java}`
- Create: `eval/labelstudio/config.xml`, `eval/GUIDELINES.md`, `eval/corpus/curated/manifest.tsv`
- Test: `eval/src/test/java/.../eval/curated/{LabelStudioRoundTripTest.java, CuratedSelectorTest.java}`

**Interfaces:**
- Consumes: `OpenAlexClient`, `GoldDocument`, `CorpusStore` (Task 5); `FlatText` (Task 3); `AbstractConverter` (Task 3); any `NameFinder`s for pre-tagging.
- Produces:
  - **`CuratedSelector.select(long seed) -> List<CuratedSelector.Pick>`**, 100 documents:
    - **50 English abstracts:** 2025 articles from subfields 2303, 2404, 2405, 1104, `has_abstract:true`, `language:en`.
    - **20 non-English abstracts:** `language:es|pt|fr|de`, 5 each.
    - **30 Pensoft excerpts:** documents not in the silver corpus, cut to 10 blocks each. Each excerpt includes one treatment heading, two captions, two table cells, and the rest paragraphs.
    - **Split:** 30 dev / 70 test, stratified per stratum.
    - Abstracts from non-CC sources get `textRetained=false`.
  - **`PreTagger.pretag(Document, List<NameFinder>) -> List<Mention>`:** the union of all finders' mentions, de-duplicated by span. In M0: `ItalicsFinder` + `GnfinderOnlyFinder` (Task 7). Re-run with `PipelineNameFinder` before the annotators start, if M1 is done by then.
  - **`LabelStudioExporter.write(List<Pick>, Map<String,List<Mention>> pretags, Path tasksJson)`:**
    - One task per document.
    - `data.text` = `FlatText.of(doc).text()`, `data.docId`, `data.document` = the Document JSON (makes import self-contained).
    - `predictions[0].result` items: `{"id","from_name":"label","to_name":"text","type":"labels","value":{"start","end","text","labels":["Name"]}}` with code-point offsets.
  - **`LabelStudioImporter.read(Path exportJson) -> List<GoldDocument>`:**
    - Uses the latest annotation per task with `was_cancelled=false`.
    - Region labels map as follows: `Name` → `GoldName`, `Act` + relation `act_of` → `GoldAct` (type from the region's `act_type` choice), `Context` → `GoldContext`.
    - The per-region choice `main_subject=yes` adds to `mainSubjectNames`.
    - Task choice `doc=bad_text` drops the document.
  - **`config.xml`:**
    - `<Labels name="label" toName="text">` with `Name`, `Act`, `Context`
    - `<Relations>` with `act_of`, `abbrev_of`
    - per-region `<Choices name="act_type" perRegion="true">` with the `ActType` values
    - per-region `<Choices name="main_subject" perRegion="true">` with `yes`
    - task-level `<Choices name="doc">` with `ok`, `bad_text`
    - `<Text name="text" value="$text"/>`
  - **`GUIDELINES.md`:**
    - **Tag:** every scientific name at any rank, including abbreviated names and hybrids, with the span covering the name only (no authorship, no punctuation). Qualifiers (cf./aff.) stay outside the span. Subgenus in parentheses is part of the span.
    - **Do not tag:** common names, species-group names ("*obscura* group"), gene or protein symbols, strain or culture codes, virus names (v1), names inside URLs.
    - **Acts:** tag the marker text and link it to its name with `act_of`.
    - **Contexts:** tag each classification string or rank heading.
    - **Main subject:** mark the names the document is mainly about.
    - Includes 6 worked examples.

- [ ] **Step 1: Failing tests:**
  - `exportThenImportOfPredictionsReproducesPretags`: simulate a reviewer by copying predictions into `annotations`, then import gives the same spans/blocks.
  - `importMapsActRelationAndMainSubject`: a hand-written export JSON with one Name, one Act linked via `act_of` (act_type `sp. nov.` → SP_NOV), and main_subject=yes.
  - `badTextDocumentsAreDropped`
  - `selectionHasStrataAndSplits`: stubbed OpenAlex gives 50/20/30 and 30/70.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Manual round trip in a real Label Studio**
  - `docker compose --profile annotate up -d labelstudio`, create a project with `eval/labelstudio/config.xml`, import `eval/work/curated-tasks.json` (from `curated-select` + `ls-export`), annotate one document, export JSON, `ls-import`.
  - Expected: the CLI prints 1 gold document with the annotated counts.
- [ ] **Step 6: Commit** `feat(eval): curated set selection, Label Studio round trip and guidelines`
- [ ] **Step 7: Hand-off to annotators.** Tell Markus the 100 tasks are ready (about 1–2 days of someone's time). The annotated export is imported later into `eval/corpus/curated/gold.jsonl.gz`, which is needed by Task 18.

### Task 9: COL XR word dictionary

**Files:**
- Create: `finder-core/src/main/java/.../finder/dict/{ColDictionary.java, ColDictionaryBuilder.java, ColDictionaryIO.java, CommonWords.java, DictHit.java}`
- Create: `finder-core/src/main/resources/org/catalogueoflife/garrulus/finder/dict/common-words-{en,de,fr,es,pt}.txt`, plus `NOTICE` (source and licence of the word lists)
- Test: `.../finder/dict/{ColDictionaryBuilderTest.java, ColDictionaryIOTest.java}`, fixture `dict/NameUsage-sample.tsv` (the verified header + ~15 rows: genus Carabus, family Carabidae, order Coleoptera, species *Carabus nigrus*, a genus "Major", a subspecies, a synonym, a botanical family "Rosaceae")

**Interfaces:**
- Consumes: CLI wiring in `EvalCli` (Task 6).
- Produces:
  - **`ColDictionary`:**
    - `boolean isGenus(String w)`, `boolean isEpithet(String w)`, `boolean isUninomial(String w)`, `Optional<Rank> uninomialRank(String w)`, `boolean isAmbiguousWord(String w)`, `boolean isKnownWord(String lower)` (genus, epithet or uninomial, used by `Dehyphenator`), `String releaseKey()`
    - Lookups are case-sensitive for genus/uninomial (capitalised form) and lower-case for epithets.
  - **Storage:**
    - Per category a fastutil `LongOpenHashSet` of 64-bit FNV-1a hashes.
    - `Long2ByteOpenHashMap` for the uninomial rank, keeping the most frequent rank per uninomial.
    - Measured on the full XR in Step 6: file < 64 MB, load < 5 s.
  - **`ColDictionaryBuilder`:**
    - `static ColDictionary fromNameUsageTsv(Reader tsv, String releaseKey, CommonWords common)` reads all statuses, synonyms included:
      - genus set = `col:genericName` (rank ≤ genus) ∪ `col:uninomial` of rank genus
      - epithets = `col:specificEpithet` ∪ `col:infraspecificEpithet`
      - uninomials = `col:uninomial` with ranks above genus
      - ambiguous = genus or uninomial whose lower case is in `common`
    - `static ColDictionary fromXrZip(Path zip)` streams the `NameUsage.tsv` entry. The release key is `metadata.yaml`'s `key:`; fall back to `version:`. Check which exists in Step 6.
  - **`ColDictionaryIO`:** `write(ColDictionary, Path)` / `read(Path)`, a versioned binary format.
  - **`CommonWords`:** `static CommonWords load(Set<String> langs)`, top 20,000 lower-case words per language. The source must be licence-compatible; credit it in `NOTICE`.
  - `enum DictHit { NONE, GENUS, UNINOMIAL, EPITHET, BINOMIAL, AMBIGUOUS_WORD }`

- [ ] **Step 1: Failing tests:**
  - `buildsCategoriesFromFixture`: Carabus is a genus; nigrus an epithet; Carabidae a uninomial with rank FAMILY; Rosaceae FAMILY.
  - `commonWordGenusIsAmbiguous`: "Major".
  - `writeReadRoundTrip`
  - `unknownWordIsNotKnown`
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): COL XR word dictionary`
- [ ] **Step 6: Build from the real XR** (manual; ~1.4 GB download into `eval/work/`, not committed)
  - Run: `eval build-dictionary --xr https://download.checklistbank.org/col/xr_latest_coldp.zip --out eval/work/`
  - Expected: `eval/work/dictionary-<releaseKey>.bin` under 64 MB; the CLI prints category sizes and load time under 5 s.
  - Record the sizes in the commit message of the next task.

### Task 10: Dictionary, rank-suffix and markup generators

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/{Tokenizer.java, DictionaryGenerator.java, SuffixRules.java, SuffixRuleGenerator.java, MarkupGenerator.java}`
- Test: `.../finder/pipeline/{DictionaryGeneratorTest.java, SuffixRuleGeneratorTest.java, MarkupGeneratorTest.java}` (use the fixture dictionary from Task 9)

**Interfaces:**
- Consumes: `CandidateGenerator`, `Candidate`, `GenerationContext` (Task 7), `ColDictionary` (Task 9). Generators that need the dictionary take it as a constructor argument: `DictionaryGenerator(ColDictionary dict)`, `SuffixRuleGenerator(CommonWords common)`.
- Produces:
  - `Tokenizer.tokens(String blockText) -> List<Token>`, `record Token(int start, int end, String text)` (code points). Tokens are runs of letters, apostrophes and inner hyphens; a trailing `.` is attached to 1–3-letter capitalised tokens (abbreviations).
  - **`DictionaryGenerator`** emits:
    - capitalised token that `isGenus || isUninomial` → uninomial
    - + following lower-case token that `isEpithet` → binomial
    - optional `(Subgenus)` between them → infrageneric
    - + rank marker (`subsp.`, `ssp.`, `var.`, `f.`, `forma`) or a bare third epithet → trinomial
    - `X.` / `Xy.` + epithet → abbreviated

    Features: `dictHit`, `ambiguousWord`.
  - **`SuffixRules`:** `static Optional<Rank> rankFor(String word, @Nullable NomCode code)`, pure suffix logic (minimum length 6, Capitalised, not all-caps). `SuffixRuleGenerator` also skips common words. The suffix table, the most specific suffix winning:

    | Code | Suffix → rank |
    |---|---|
    | Zoological | -oidea → SUPERFAMILY, -idae → FAMILY, -inae → SUBFAMILY, -ini → TRIBE, -ina → SUBTRIBE |
    | Botanical / fungi | -phyta → PHYLUM, -mycota → PHYLUM, -mycotina → SUBPHYLUM, -opsida → CLASS, -mycetes → CLASS, -ales → ORDER, -ineae → SUBORDER, -aceae → FAMILY, -oideae → SUBFAMILY, -eae → TRIBE, -inae → SUBTRIBE |
    | Bacterial | -ales → ORDER, -aceae → FAMILY, -ineae → SUBORDER |

    With an unknown code, a suffix shared by codes gives the rank both codes agree on, otherwise `UNRANKED`.
  - `SuffixRuleGenerator` emits uninomial candidates with feature `suffixRank`.
  - `MarkupGenerator` emits:
    - for each ITALIC span, the maximal name-shaped token runs inside it → generator ITALIC
    - each TAXON_NAME span → generator TAXON_MARKUP

- [ ] **Step 1: Failing tests:**
  - `binomialAndAbbreviation`: "Carabus nigrus … C. nigrus".
  - `subgenusInParentheses`: "Carabus (Megodontus) nigrus".
  - `trinomialWithMarker`: "Carabus nigrus ssp. alpinus".
  - `ambiguousWordFlagged`: "Major" gets `ambiguousWord=true`.
  - Suffix cases: "Carabidae" → FAMILY; "Harpalinae" → SUBFAMILY under zoological; "Rosaceae" → FAMILY; "Database" → none.
  - `italicSpanSplitIntoNameRuns`: "*Carabus nigrus* and *C. alpinus*" gives two candidates.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): dictionary, suffix and markup candidate generators`

### Task 11: Merge, authorship and qualifiers

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/{MergedCandidate.java, CandidateMerger.java, AuthorshipAttacher.java}`
- Test: `.../finder/pipeline/{CandidateMergerTest.java, AuthorshipAttacherTest.java}`

**Interfaces:**
- Consumes: `Candidate` (Task 7), `NameParser` (`org.gbif.nameparser.rust.NameParserRust`, single shared instance, thread-safe).
- Produces:
  - `record MergedCandidate(String blockId, int start, int end, String verbatim, String name, Set<Generator> generators, Map<String,Object> features, @Nullable String authorship, @Nullable String qualifier)`
  - `CandidateMerger.merge(List<Candidate>) -> List<MergedCandidate>`:
    - Groups overlapping spans per block.
    - Keeps the longest span backed by at least one generator, as long as every generator's span lies inside it.
    - Unions generators and features (`oddsLog10` takes the max).
    - Output is sorted by block order, then start.
  - **`AuthorshipAttacher(NameParser parser)`:** `MergedCandidate attach(MergedCandidate c, Block block)`.
    - Reads up to 80 code points after the name and tries authorship patterns, longest first: `(Author, 1758)`, `Author, 1758`, `Author & Author, 1999`, `A. Author`, botanical `L.` / `Benth.` / `Hook.f.`. A pattern is accepted only if `parser.parse(name + " " + authorship, …)` yields that authorship.
    - Qualifiers `cf.`, `aff.`, `nr.`, `sp.`, `spp.`, `s.l.`, `s.str.`:
      - between genus and epithet, they are removed from the name ("Carabus cf. nigrus" becomes name "Carabus nigrus" with qualifier "cf.")
      - directly after a genus ("Carabus sp."), the qualifier is set and the kind stays UNINOMIAL

- [ ] **Step 1: Failing tests:**
  - `longestSupportedSpanWins`: DICTIONARY "Carabus" and GNFINDER "Carabus nigrus" give one candidate "Carabus nigrus" with both generators.
  - `authorshipWithParentheses`: "Panthera leo (Linnaeus, 1758)".
  - `authorshipPlainZoological`: "Ophelia Savigny, 1822".
  - `botanicalAuthorship`: "Rosa canina L.".
  - `cfQualifierRemovedFromName`
  - `spQualifierOnGenus`
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): candidate merging, authorship and qualifiers`

### Task 12: Document-wide abbreviation expansion

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/AbbreviationExpander.java`
- Test: `.../finder/pipeline/AbbreviationExpanderTest.java`

**Interfaces:**
- Consumes: `MergedCandidate` (Task 11).
- Produces: `AbbreviationExpander.expand(List<MergedCandidate> docOrder) -> List<MergedCandidate>`. Rules:
  - For a name starting with an abbreviated genus (`X.` or `Xy.`), and for trinomials with abbreviated genus and species ("L. g. confertiflora"), take the **most recent preceding** full genus (and full species) whose spelling starts with the abbreviation letters.
  - If none precedes, take the first one that follows.
  - If two different matching genera occur in the same block before it, the abbreviation is **ambiguous**: leave it, with feature `abbrevAmbiguous=true`.
  - The expanded `name` replaces the abbreviation; `verbatim` is unchanged.

- [ ] **Step 1: Failing tests:**
  - `expandsFromPrecedingGenus`: "C. nigrus" becomes "Carabus nigrus".
  - `usesFollowingGenusIfNonePrecedes`
  - `multiLetterAbbreviation`: "Pt. niger" becomes "Pterostichus niger".
  - `trinomialAbbreviation`: "L. g. confertiflora" after "Lupinus grandiflorus" becomes "Lupinus grandiflorus confertiflora".
  - `ambiguousWithinBlockLeftUnexpanded`: "Carabus … Calosoma … C. nigrus" in one block.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): document-wide abbreviation expansion`

### Task 13: Parser stage

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/{ParsedCandidate.java, ParserStage.java}`
- Test: `.../finder/pipeline/ParserStageTest.java`

**Interfaces:**
- Consumes: `MergedCandidate` (Tasks 11–12), `ColDictionary` (Task 9), `SuffixRules` (Task 10), `NameParser`.
- Produces:
  - `record ParsedCandidate(MergedCandidate c, String canonical, NameKind kind, @Nullable Rank rank, @Nullable NomCode code)`
  - `ParserStage(NameParser parser, ColDictionary dict)`, `Optional<ParsedCandidate> parse(MergedCandidate c, @Nullable NomCode codeHint)`:
    - Calls `parser.parse(c.name(), c.authorship(), null, codeHint)` and drops the candidate if `result.parsed().isEmpty()`. TAXON_MARKUP candidates are kept as UNINOMIAL/UNRANKED instead.
    - Rank:
      - parsed rank for binomials and below
      - for uninomials: `dict.uninomialRank`, else the suffix rank, else GENUS if `dict.isGenus`, else UNRANKED
    - Kind: from the parsed name (uninomial / binomial / trinomial / infrageneric); ABBREVIATED if the name still starts with an abbreviation after Task 12.

- [ ] **Step 1: Failing tests:**
  - `binomialParsedAsSpecies`
  - `familyRankFromDictionary`: "Carabidae".
  - `suffixRankWhenNotInDictionary`: "Fooidae" → FAMILY.
  - `unexpandedAbbreviationIsAbbreviated`
  - `unparsableDropped`: e.g. "BOLD:AAA1234".
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): name-parser stage`

### Task 14: Nomenclatural act tagger

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/{ActPatterns.java, ActTagger.java}`
- Test: `.../finder/pipeline/{ActPatternsTest.java, ActTaggerTest.java}`

**Interfaces:**
- Consumes: `ParsedCandidate` (Task 13), `Block`, `Act`, `ActType` (Task 1).
- Produces:
  - **`ActPatterns`:** `static List<ActMatch> find(String blockText)`, `record ActMatch(int start, int end, ActType type, String marker)`, `static Optional<ActType> classify(String statusText)`.
    - All patterns are case-insensitive, with optional dots and spaces.

    | Type | Patterns |
    |---|---|
    | SP_NOV | `sp. nov.`, `sp. n.`, `spec. nov.`, `n. sp.`, `species nova`, `new species`, `nueva especie`, `nova espécie`, `nouvelle espèce`, `neue Art` |
    | SSP_NOV | `ssp. nov.`, `subsp. nov.`, `ssp. n.`, `new subspecies` |
    | VAR_NOV | `var. nov.` |
    | GEN_NOV | `gen. nov.`, `gen. n.`, `n. gen.`, `new genus`, `nuevo género`, `novo gênero`, `nouveau genre`, `neue Gattung` |
    | SUBGEN_NOV | `subgen. nov.`, `subgen. n.` |
    | FAM_NOV | `fam. nov.`, `subfam. nov.`, `trib. nov.`, `new family`, `new subfamily`, `new tribe` |
    | COMB_NOV | `comb. nov.`, `comb. n.`, `new combination`, `combinatio nova`, `nueva combinación` |
    | STAT_NOV | `stat. nov.`, `stat. n.`, `new status` |
    | STAT_REV | `stat. rev.`, `status revived`, `revived status` |
    | SYN_NOV | `syn. nov.`, `syn. n.`, `new synonym`, `new synonymy`, `nueva sinonimia` |
    | NOM_NOV | `nom. nov.`, `nom. n.`, `nomen novum`, `replacement name` |
    | TYPE_DESIGNATION | `lectotype designated here`, `lectotype here designated`, `designated here as lectotype`, `neotype designated here`, `neotype here designated` |
  - **`ActTagger.tag(List<ParsedCandidate> doc, Document document) -> Map<ParsedCandidate, ActMatch>`:**
    - A marker attaches to the **nearest preceding** candidate in the same block that ends at most 40 code points before the marker start and whose kind fits:
      - SP_NOV: binomial or abbreviated
      - SSP_NOV, VAR_NOV: trinomial
      - GEN_NOV, SUBGEN_NOV: uninomial or infrageneric with rank ≤ GENUS
      - FAM_NOV: uninomial with a family-group rank
      - all other acts: any kind
    - A marker that is a whole HEADING block attaches to the last name of the preceding HEADING block.
    - Unattached markers are counted in diagnostics `actsUnattached`.

- [ ] **Step 1: Failing tests:**
  - `spNovAttachesToSpeciesNotFamilyChain`: "… Carabus (Coleoptera: Carabidae), Carabus nigrus sp. nov., is described" gives SP_NOV on "Carabus nigrus" only.
  - `combNovOnBinomial`
  - `spanishNuevaEspecie`
  - `genNovNeedsGenusRank`
  - `lectotypeDesignation`
  - `markerTooFarIsUnattached`
  - `classifyStatusTexts`: "sp. n." → SP_NOV, "comb. nov." → COMB_NOV, "stat. rev." → STAT_REV.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Replace** `TaxPubGoldExtractor.statusToAct` (Task 5) with `ActPatterns.classify`, and re-run `mvn -B -pl eval -am test`. Expected: PASS.
- [ ] **Step 6: Commit** `feat(finder-core): nomenclatural act tagger`

### Task 15: Classification-context extractor

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/{RankWords.java, ContextExtractor.java}`
- Test: `.../finder/pipeline/ContextExtractorTest.java`

**Interfaces:**
- Consumes: `Document`, `ParsedCandidate`, `ColDictionary`, `SuffixRules`, `ClassificationContext`, `RankedName`, `ContextScope`.
- Produces:
  - **`RankWords`:** `static Optional<Rank> rank(String word)`. Multilingual vocabulary:

    | Rank | Words |
    |---|---|
    | KINGDOM | Kingdom, Regnum, Reino, Règne, Reich |
    | PHYLUM | Phylum, Division, Divisio, Filo, Embranchement, Stamm |
    | CLASS | Class, Classis, Clase, Classe, Klasse |
    | ORDER | Order, Ordo, Orden, Ordre, Ordem, Ordnung |
    | SUPERFAMILY | Superfamily, Superfamilia, Superfamille, Überfamilie |
    | FAMILY | Family, Familia, Famille, Família, Familie |
    | SUBFAMILY | Subfamily, Subfamilia, Sous-famille, Subfamília, Unterfamilie |
    | TRIBE | Tribe, Tribus, Tribu |
    | GENUS | Genus, Género, Gênero, Genre, Gattung |
  - **`ContextExtractor(ColDictionary dict)`:** `Result extract(Document doc, List<ParsedCandidate> names)`, `record Result(List<ClassificationContext> contexts, Map<ParsedCandidate,String> contextOf)`. Patterns:
    1. In TITLE, ABSTRACT and HEADING blocks, parenthesised chains `\(([A-Z][a-z]+(?:\s*[:,;]\s*[A-Z][a-z]+)+)\)` and block-initial chains `^([A-Z][a-z]+(?:\s*:\s*[A-Z][a-z]+)+)`. A chain is accepted only if every element `isUninomial || isGenus || SuffixRules.rankFor` is present. The rank of each element comes from the dictionary, then the suffix, else UNRANKED.
    2. HEADING blocks starting with a rank word followed by a capitalised name.
    3. TaxPub treatment HEADINGs (from `tp:nomenclature`): a name with rank ≥ GENUS opens a TREATMENT context.
  - **Scope:**
    - a TITLE chain → DOCUMENT
    - a HEADING (pattern 2) → SECTION until the next HEADING of level ≤ its level (`untilBlockId`)
    - a treatment → TREATMENT likewise
  - **Assignment:** each name gets the innermost context covering its block; otherwise the DOCUMENT context, if any.

- [ ] **Step 1: Failing tests:**
  - `titleChainIsDocumentContext`: "A new species of Carabus (Coleoptera: Carabidae) from China" gives DOCUMENT `[ORDER Coleoptera, FAMILY Carabidae]`.
  - `rankHeadingOpensSection`: "Family Opheliidae Malmgren, 1867", then a paragraph mention gets that SECTION context, until the next level-1 heading.
  - `invalidChainRejected`: "(Results: Discussion)".
  - `germanRankWord`: "Familie Carabidae".
  - `innermostContextWins`
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): classification-context extractor`

### Task 16: Judge SPI, `ThresholdJudge` and `PipelineNameFinder`

**Files:**
- Create: `finder-core/src/main/java/.../finder/pipeline/{CandidateFeatures.java, FeatureExtractor.java, CandidateJudge.java, ThresholdJudge.java, PipelineConfig.java, PipelineNameFinder.java}`
- Create: `finder-core/src/main/resources/org/catalogueoflife/garrulus/finder/pipeline/pipeline-defaults.json`
- Test: `.../finder/pipeline/{FeatureExtractorTest.java, ThresholdJudgeTest.java, PipelineNameFinderTest.java}`; recorded gnfinder JSON fixtures under `finder-core/src/test/resources/gnfinder/`

**Interfaces:**
- Consumes: everything from Tasks 1–15.
- Produces:
  - **Features:**
    - `record CandidateFeatures(boolean italic, boolean taxonMarkup, boolean rankWordBefore, @Nullable Double gnfinderOdds, DictHit dictHit, boolean suffixRule, boolean ambiguousWord, double docNameDensity, BlockType blockType, boolean sentenceInitial, boolean authorshipFollows, int generatorCount)`
    - `FeatureExtractor.features(ParsedCandidate c, Document doc, int namesInDoc) -> CandidateFeatures`. `docNameDensity` = names per 100 tokens.
  - **Judge SPI:** `interface CandidateJudge { String name(); JudgeVerdict judge(ParsedCandidate c, CandidateFeatures f); }`
  - **`ThresholdJudge(Map<String,Double> weights, double bias, double threshold)`:**
    - score = logistic(bias + Σ weight·feature). Booleans count 1/0, `gnfinderOdds` is clamped to [0, 20]/20, and `dictHit` is one-hot.
    - Initial weights live in `pipeline-defaults.json` and are tuned in Task 18 on dev only.
  - **Who gets judged:**
    - **Certain**, bypassing the judge: binomials, trinomials and infrageneric names with `dictHit` EPITHET/BINOMIAL, or `gnfinderOdds ≥ 8`, or `taxonMarkup`. Their confidence is the max of the available signals.
    - **Uncertain**, judged: all uninomials, ambiguous words, candidates with a single generator and no italics.
  - **`PipelineConfig`** (Jackson-bound from `pipeline-defaults.json` + overrides):
    - fields: `judgeWeights`, `judgeBias`, `judgeThreshold`, `certainOdds`, `maxChunkCodePoints`, `actMaxDistance` (40), `enabledGenerators`, `llmJudgeEnabled` (always false in M1; a non-false value fails fast with "LLM judge not implemented")
    - `String configHash()` = SHA-256 of the canonical JSON
  - **`PipelineNameFinder(PipelineConfig cfg, GnfinderClient gn, ColDictionary dict, NameParser parser) implements NameFinder`**, name `"pipeline"`:
    - Version from `finder-core`'s `META-INF/MANIFEST.MF` `Implementation-Version`.
    - `info()` = `FinderInfo("pipeline", version, cfg.configHash(), dict.releaseKey())`.
    - Order: generators → merge → authorship → abbreviations → parse → judge → acts → contexts.
    - IDs `m1…`, `a1…`, `c1…` in document order.
    - Rejected candidates are returned only if `opts.includeRejected()`.
    - Diagnostics: `candidates`, `accepted`, `rejected`, `actsUnattached`, `gnfinderMs`.

- [ ] **Step 1: Failing tests**

```java
class PipelineNameFinderTest {   // fake GnfinderClient returning recorded JSON; fixture dictionary
  @Test void zookeysExcerptEndToEnd() { /* fixture from Task 2 (taxon markup stripped): finds Ophelia, Opheliidae, "O. limacina"→"Ophelia limacina", Ophelia yangi + SP_NOV, contexts present */ }
  @Test void commonWordGenusAtSentenceStartRejected() { /* abstract "Major changes in soil fauna were observed." → no mention "Major" */ }
  @Test void italicAmbiguousGenusAccepted() { /* "*Major* sp. was collected" (italic) → accepted */ }
  @Test void emptyDocumentYieldsEmptyResult() { /* Document with zero blocks and with one empty block → empty lists, no exception, no gnfinder call */ }
  @Test void configHashIsStableAndSensitive() { /* same config → same hash; threshold 0.5→0.6 → different hash */ }
  @Test void llmJudgeEnabledFailsFast() { }
}
```
- [ ] **Step 2–4:** run `mvn -B -pl finder-core -am test` → FAIL, implement, → PASS.
- [ ] **Step 5: Commit** `feat(finder-core): judge SPI, threshold judge and PipelineNameFinder`

### Task 17: `finder-service` and `RemoteNameFinder`

**Files:**
- Create: `finder-service/src/main/java/org/catalogueoflife/garrulus/finder/service/{FinderServiceApplication.java, FinderConfig.java, FindController.java, FindRequest.java}`, `finder-service/src/main/resources/application.yml`
- Create: `finder-core/src/main/java/.../finder/remote/RemoteNameFinder.java`
- Test: `finder-service/src/test/java/.../finder/service/{FindControllerTest.java, RemoteNameFinderIT.java}`

**Interfaces:**
- Consumes: `NameFinder`, `FinderJson`, `JatsConverter`, `PipelineNameFinder`, `ColDictionaryIO`, `GnfinderClient`.
- Produces:
  - **Endpoints** (port `8095`):
    - `POST /v1/find`: body `record FindRequest(Document document, @Nullable FinderOptions options)`, response `FinderResult`.
    - `POST /v1/find/text`: `text/plain`, query `language`; wrapped as one PARAGRAPH block `b1`.
    - `POST /v1/find/jats`: `application/xml`; `JatsConverter`, keeping the taxon markup.
    - `GET /v1/info`: returns `FinderInfo`.
  - **Config** (env):
    - `FINDER_DICTIONARY`: path to the `.bin`. Required; startup fails with "FINDER_DICTIONARY must point to a dictionary built by `eval build-dictionary`".
    - `GNFINDER_URL`: default `http://localhost:8778`.
    - `FINDER_CONFIG`: optional JSON overrides.
  - `RemoteNameFinder(URI baseUrl, HttpClient http) implements NameFinder`: `info()` caches `GET /v1/info`, and `find` posts to `/v1/find`. It uses `FinderJson.mapper()`.
  - The JSON produced must match `finder-api.yaml`.

- [ ] **Step 1: Failing tests:**
  - `FindControllerTest` (WebMvc, stub `NameFinder` returning the golden `find-response.json`):
    - `/v1/find` returns exactly the golden JSON
    - `/v1/find/text` wraps the text
    - a missing dictionary fails startup (context test)
  - `RemoteNameFinderIT`: start the service on a random port with the stub; `RemoteNameFinder.find` equals the stub's result.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Manual check** (design verification line)
  - Run: `docker compose up -d gnfinder`; `FINDER_DICTIONARY=eval/work/dictionary-<key>.bin mvn -pl finder-service spring-boot:run`; then `curl -s -XPOST -H 'Content-Type: application/xml' --data-binary @<(curl -s https://zookeys.pensoft.net/article/218447/download/xml/) localhost:8095/v1/find/jats | jq '.mentions|length, .acts, .contexts'`
  - Expected: many mentions; an SP_NOV act on "Ophelia yangi"; contexts present. Spot-check 3 offsets with `CodePoints.slice`.
- [ ] **Step 6: Commit** `feat(finder-service): HTTP finder service and RemoteNameFinder`

### Task 18: PDF path (mode B), tuning, baselines and the M1 finish line

**Files:**
- Create: `eval/src/main/java/.../eval/modeb/{GrobidClient.java, ModeBRunner.java}`
- Create: `docs/adr/0001-code-point-offsets.md`, `docs/adr/0002-grobid-has-no-italics.md`, `docs/adr/0003-gnfinder-without-verification.md`
- Create: `eval/reports/*` (generated), `eval/corpus/curated/gold.jsonl.gz` (from the annotators' export)
- Modify: `finder-core/src/main/resources/.../pipeline-defaults.json` (tuned weights)
- Test: `eval/src/test/java/.../eval/modeb/{GrobidClientTest.java, GrobidIT.java}`

**Interfaces:**
- Consumes: `TeiConverter`, `Dehyphenator` (Task 4), `PensoftFetcher.pdfUrl` (Task 5), `Scorer` (Task 6), all finders.
- Produces:
  - `GrobidClient(URI baseUrl, HttpClient http)`, `String processFulltext(byte[] pdf)`:
    - multipart field `input`, `consolidateHeader=0`, `consolidateCitations=0`
    - on 503, retry up to 5 times with 10 s waits
  - **`ModeBRunner.run(List<GoldDocument>, NameFinder, Path pdfCache) -> Map<String,FinderResult>`:**
    - Downloads PDFs to `eval/work/pdf/` (not committed).
    - TEI → `TeiConverter`, then `Dehyphenator.join` with `dict::isKnownWord` per block.
    - Runs the finder.
    - Scoring is always `documentLevel=true`.
  - `eval run … --mode B` and `eval tune --corpus <dir> --split dev`. The latter is a grid search over `judgeThreshold` ∈ {0.3…0.8} and the three largest weights, maximising F1 on `dev`, and printing the best config JSON.

- [ ] **Step 1: Failing tests:**
  - `GrobidClientTest`: WireMock returns 503 twice, then TEI; the client returns the TEI after 3 calls.
  - `GrobidIT`, profile `grobid`, container `grobid/grobid:0.9.1-crf`: one small CC-BY PDF fixture gives TEI with ≥1 paragraph.
- [ ] **Step 2–4:** run → FAIL, implement, → PASS.
- [ ] **Step 5: Tune on dev** (Pensoft dev + curated dev, once annotated):
  - Run `eval tune` on both, and write the chosen weights into `pipeline-defaults.json`.
  - Expected: the dev F1 of `pipeline` is ≥ the dev F1 of `gnfinder`.
- [ ] **Step 6: Final baselines on test.** Run `eval run` with:
  - `--finder italics|gnfinder|pipeline` × `--corpus pensoft --split test --mode A`
  - the same for `--mode B`
  - `--corpus curated --split test --mode A`

  Commit all reports.
- [ ] **Step 7: M1 finish line check** (design roadmap). In the curated **test** report:
  - `pipeline` F1 > `gnfinder` F1
  - `pipeline` precision on stratum `BROAD` abstracts ≥ `gnfinder` precision
  - If not met, stop and report the numbers to Markus; don't lower the bar.
- [ ] **Step 8: ADRs.**
  - 0001: why offsets are code points.
  - 0002: GROBID TEI lacks italics, so the italic feature is absent in mode B and for OpenAlex TEI; the judge must not depend on it.
  - 0003: gnfinder runs without verification; abbreviations are expanded by us; its act attachment is replaced by our tagger; add the hyphenation behaviour observed in Task 7.
- [ ] **Step 9: Manual spot-check** (design verification line). Run `finder-service` on 5 articles: a zoology paper, a botany paper (PhytoKeys), a mycology paper (MycoKeys), a Spanish-language paper, and a paper whose title contains a "(Coleoptera: Carabidae)"-style chain. Note obvious misses and false hits in `eval/reports/{date}-spot-check.md`.
- [ ] **Step 10: Update `CLAUDE.md`** with the eval commands and where reports and dictionaries live.
- [ ] **Step 11: Commit** `feat(eval): GROBID mode B, tuning, baselines and ADRs`
