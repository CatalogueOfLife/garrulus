package org.catalogueoflife.garrulus.eval.score;

import org.catalogueoflife.garrulus.eval.corpus.GoldAct;
import org.catalogueoflife.garrulus.eval.corpus.GoldContext;
import org.catalogueoflife.garrulus.eval.corpus.GoldDocument;
import org.catalogueoflife.garrulus.eval.corpus.GoldName;
import org.catalogueoflife.garrulus.finder.api.Act;
import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.ClassificationContext;
import org.catalogueoflife.garrulus.finder.api.ContextScope;
import org.catalogueoflife.garrulus.finder.api.RankedName;
import org.gbif.nameparser.api.Rank;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Evidence;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {
  private static final FinderInfo INFO = new FinderInfo("t", "1", "h", null);
  private static final String TEXT = "Ophelia limacina and O. limacina live here";

  private static GoldDocument gold(List<Block> blocks, List<GoldName> names) {
    Document d = new Document("d1", "en", blocks, Map.of());
    return new GoldDocument("d1", "pensoft", "dev", "CC-BY", true, GoldDocument.textHash(d), d, names, List.of(), List.of(), Set.of());
  }

  private static Mention m(String id, String block, int s, int e, String verbatim, String name) {
    return new Mention(id, block, s, e, verbatim, name, NameKind.BINOMIAL, null, null, null, 1,
        new Evidence(Set.of(Generator.ITALIC), null, null), null, null, Map.of());
  }

  private static FinderResult result(Mention... ms) {
    return new FinderResult(INFO, List.of(ms), List.of(), List.of(), List.of(), Map.of());
  }

  private static GoldDocument doc() {
    return gold(List.of(new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of())),
        List.of(new GoldName("b1", 0, 16, "Ophelia limacina", null, null), new GoldName("b1", 21, 32, "O. limacina", "Ophelia limacina", null)));
  }

  private static ScoreOptions opts(MatchMode mode, boolean docLevel) {
    return new ScoreOptions(mode, Set.of(), docLevel);
  }

  @Test
  void otuMentionsAreNotScoredAsNames() {
    Mention otu = new Mention("3", "b1", 34, 46, "BOLD:AAB2345", "BOLD:AAB2345", NameKind.OTU, null, null, null, 1,
        new Evidence(Set.of(Generator.OTU), null, null), null, null, Map.of("scheme", "BOLD_BIN"));
    FinderResult base = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"));
    FinderResult withOtu = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), otu);
    for (boolean docLevel : new boolean[] {false, true}) {
      EvalReport a = Scorer.score(List.of(doc()), Map.of("d1", base), opts(MatchMode.STRICT, docLevel));
      EvalReport b = Scorer.score(List.of(doc()), Map.of("d1", withOtu), opts(MatchMode.STRICT, docLevel));
      assertThat(b.names()).isEqualTo(a.names());
      assertThat(b.mainSubject()).isEqualTo(a.mainSubject());
      assertThat(b.falsePositives()).isEqualTo(a.falsePositives());
      assertThat(a.otuMentions()).isZero();
      assertThat(b.otuMentions()).isEqualTo(1);
      assertThat(b.otuByScheme()).containsExactly(Map.entry("BOLD_BIN", 1));
    }
  }

  @Test
  void strictMatchCountsExactSpansOnly() {
    // exact for the first, shifted by one for the second, plus one stray
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "b1", 21, 33, "O. limacina ", "O. limacina"),
        m("3", "b1", 34, 38, "live", "live"));
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, false));
    assertThat(rep.names()).isEqualTo(new Metrics(1, 2, 1));
    assertThat(rep.falsePositives()).hasSize(2);
    assertThat(rep.falseNegatives()).hasSize(1);
    assertThat(rep.falseNegatives().get(0).context()).contains("«O. limacina»");
  }

  @Test
  void overlapMatchAcceptsBoundaryDifferences() {
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "b1", 21, 33, "O. limacina ", "O. limacina"),
        m("3", "b1", 34, 38, "live", "live"));
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.OVERLAP, false));
    assertThat(rep.names()).isEqualTo(new Metrics(2, 1, 0));
    // the same span in another block never matches
    Mention other = m("4", "b2", 0, 16, "Ophelia limacina", "Ophelia limacina");
    assertThat(Scorer.score(List.of(doc()), Map.of("d1", result(other)), opts(MatchMode.OVERLAP, false)).names().tp()).isZero();
  }

  @Test
  void documentLevelUsesFullNames() {
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"));
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, true));
    // gold: "Ophelia limacina" (text) and "Ophelia limacina" (fullName of "O. limacina") collapse into one name
    assertThat(rep.names()).isEqualTo(new Metrics(1, 0, 0));
  }

  @Test
  void referencesExcludedForPensoft() {
    Document d = new Document("d1", "en", List.of(
        new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of()),
        new Block("r1", BlockType.REFERENCE, 0, "Carabus nigrus Smith 1900", List.of(), List.of())), Map.of());
    GoldDocument g = new GoldDocument("d1", "pensoft", "dev", "CC-BY", true, GoldDocument.textHash(d), d,
        List.of(new GoldName("b1", 0, 16, "Ophelia limacina", null, null), new GoldName("r1", 0, 13, "Carabus nigrus", null, null)),
        List.of(), List.of(), Set.of());
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "r1", 0, 7, "Carabus", "Carabus"));
    EvalReport with = Scorer.score(List.of(g), Map.of("d1", r), ScoreOptions.pensoft(MatchMode.STRICT));
    assertThat(with.names()).isEqualTo(new Metrics(1, 0, 0));
    EvalReport without = Scorer.score(List.of(g), Map.of("d1", r), ScoreOptions.curated(MatchMode.STRICT));
    assertThat(without.names()).isEqualTo(new Metrics(1, 1, 1));
  }

  @Test
  void metricsHandleZeroDivision() {
    Metrics z = new Metrics(0, 0, 0);
    assertThat(z.precision()).isZero();
    assertThat(z.recall()).isZero();
    assertThat(z.f1()).isZero();
    assertThat(Double.isNaN(new Metrics(0, 3, 0).recall())).isFalse();
    assertThat(new Metrics(2, 2, 2).f1()).isEqualTo(0.5);
  }

  @Test
  void missingResultCountsAllGoldAsMissedAndCategoriesAreFilled() {
    EvalReport rep = Scorer.score(List.of(doc()), Map.of(), opts(MatchMode.STRICT, false));
    assertThat(rep.names()).isEqualTo(new Metrics(0, 0, 2));
    assertThat(rep.byCategory()).containsKeys("kind:BINOMIAL", "kind:ABBREVIATED", "block:PARAGRAPH", "lang:en");
  }

  @Test
  void reportWriterWritesMarkdownAndJson(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"))),
        opts(MatchMode.STRICT, false), "italics", "pensoft", "A");
    java.nio.file.Path md = ReportWriter.write(rep, tmp);
    assertThat(md.getFileName().toString()).endsWith("-italics-pensoft-dev-A-STRICT.md");
    assertThat(java.nio.file.Files.readString(md)).contains("| names | 1 | 0 | 1 |").contains("False negatives");
    assertThat(java.nio.file.Files.readString(tmp.resolve(md.getFileName().toString().replace(".md", ".json")))).contains("\"finder\" : \"italics\"");
  }

  private static GoldDocument rich() {
    Document d = new Document("d1", "en", List.of(
        new Block("t", BlockType.TITLE, 0, "Ophelia limacina sp. nov.", List.of(), List.of()),
        new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of())), Map.of());
    return new GoldDocument("d1", "pensoft", "dev", "CC-BY", true, GoldDocument.textHash(d), d,
        List.of(new GoldName("t", 0, 16, "Ophelia limacina", null, null)),
        List.of(new GoldAct("t", 0, 16, ActType.SP_NOV, "Ophelia limacina"), new GoldAct("b1", 21, 32, ActType.COMB_NOV, "O. limacina")),
        List.of(new GoldContext("b1", List.of(new RankedName(Rank.FAMILY, "Opheliidae"), new RankedName(Rank.ORDER, "Opheliida")))),
        Set.of("Ophelia limacina"));
  }

  @Test
  void actsAreScoredAsTypeAndName() {
    Mention a = m("1", "t", 0, 16, "Ophelia limacina", "Ophelia limacina");
    Mention b = m("2", "b1", 21, 32, "O. limacina", "Ophelia limacina");
    // right type and name; wrong type for the second; one act with no gold counterpart
    FinderResult r = new FinderResult(INFO, List.of(a, b), List.of(new Act("a1", ActType.SP_NOV, "sp. nov.", "1", 1),
        new Act("a2", ActType.SYN_NOV, "syn. nov.", "2", 1)), List.of(), List.of(), Map.of());
    EvalReport rep = Scorer.score(List.of(rich()), Map.of("d1", r), opts(MatchMode.STRICT, false));
    assertThat(rep.acts()).isEqualTo(new Metrics(1, 1, 1));
    // the expanded name of an abbreviated mention matches the gold printed name too
    Mention c = m("3", "b1", 21, 32, "O. limacina", "Ophelia limacina");
    FinderResult r2 = new FinderResult(INFO, List.of(c), List.of(new Act("a3", ActType.COMB_NOV, "comb. nov.", "3", 1)), List.of(), List.of(), Map.of());
    assertThat(Scorer.score(List.of(rich()), Map.of("d1", r2), opts(MatchMode.STRICT, false)).acts()).isEqualTo(new Metrics(1, 0, 1));
  }

  @Test
  void contextsAreScoredAsRankAndName() {
    ClassificationContext c = new ClassificationContext("c1", "Opheliidae", List.of(new RankedName(Rank.FAMILY, "Opheliidae"),
        new RankedName(Rank.GENUS, "Ophelia")), ContextScope.SECTION, "b1", null);
    FinderResult r = new FinderResult(INFO, List.of(), List.of(), List.of(c), List.of(), Map.of());
    // the family matches, the order is missed, the genus is wrong; the same name at another rank would not count
    assertThat(Scorer.score(List.of(rich()), Map.of("d1", r), opts(MatchMode.STRICT, false)).contexts()).isEqualTo(new Metrics(1, 1, 1));
    ClassificationContext wrongRank = new ClassificationContext("c2", "Opheliidae", List.of(new RankedName(Rank.ORDER, "Opheliidae")),
        ContextScope.SECTION, "b1", null);
    FinderResult r2 = new FinderResult(INFO, List.of(), List.of(), List.of(wrongRank), List.of(), Map.of());
    assertThat(Scorer.score(List.of(rich()), Map.of("d1", r2), opts(MatchMode.STRICT, false)).contexts().tp()).isZero();
  }

  @Test
  void mainSubjectIsTitleMentionsPlusActMentions() {
    Mention title = m("1", "t", 0, 16, "Ophelia limacina", "Ophelia limacina");
    Mention other = m("2", "b1", 34, 38, "live", "Carabus nigrus");
    FinderResult r = new FinderResult(INFO, List.of(title, other), List.of(), List.of(), List.of(), Map.of());
    assertThat(Scorer.score(List.of(rich()), Map.of("d1", r), opts(MatchMode.STRICT, false)).mainSubject()).isEqualTo(new Metrics(1, 0, 0));
    // a body mention only counts when it carries an act
    FinderResult r2 = new FinderResult(INFO, List.of(other), List.of(new Act("a1", ActType.SP_NOV, "sp. nov.", "2", 1)), List.of(), List.of(), Map.of());
    assertThat(Scorer.score(List.of(rich()), Map.of("d1", r2), opts(MatchMode.STRICT, false)).mainSubject()).isEqualTo(new Metrics(0, 1, 1));
    assertThat(Scorer.score(List.of(rich()), Map.of("d1", result(other)), opts(MatchMode.STRICT, false)).mainSubject()).isEqualTo(new Metrics(0, 0, 1));
  }

  @Test
  void errorSamplesAreSpreadAcrossDocuments() {
    List<ErrorSample> in = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) in.add(new ErrorSample("d1", "b", i, i + 1, "x", null, null));
    in.add(new ErrorSample("d2", "b", 0, 1, "y", null, null));
    in.add(new ErrorSample("d3", "b", 0, 1, "z", null, null));
    List<ErrorSample> out = Scorer.spread(in);
    assertThat(out).hasSize(6);
    assertThat(out.subList(0, 3)).extracting(ErrorSample::docId).containsExactly("d1", "d2", "d3");
  }

  @Test
  void theJsonKeepsACappedNumberOfErrorSamples(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
    List<ErrorSample> many = new java.util.ArrayList<>();
    for (int i = 0; i < ReportWriter.JSON_SAMPLES + 100; i++) many.add(new ErrorSample("d1", "b1", i, i + 1, "x" + i, "BINOMIAL", "ctx"));
    EvalReport base = Scorer.score(List.of(doc()), Map.of(), opts(MatchMode.STRICT, false), "pipeline", "pensoft", "A");
    EvalReport rep = new EvalReport(base.finder(), base.info(), base.corpus(), base.split(), base.mode(), base.documents(), base.names(),
        base.byCategory(), base.nameCorrect(), base.nameCorrectByKind(), base.acts(), base.contexts(), base.mainSubject(), many,
        many.subList(0, 3), base.matchMode(), base.documentLevel(), List.of("a note"), 0, Map.of(), null);
    java.nio.file.Path md = ReportWriter.write(rep, tmp, java.time.LocalDate.of(2026, 11, 15));
    String json = java.nio.file.Files.readString(tmp.resolve(md.getFileName().toString().replace(".md", ".json")));
    var tree = org.catalogueoflife.garrulus.finder.api.FinderJson.mapper().readTree(json);
    assertThat(tree.get("falsePositives").size()).isEqualTo(ReportWriter.JSON_SAMPLES);
    assertThat(tree.get("falseNegatives").size()).isEqualTo(3);
    assertThat(tree.get("falsePositives").get(0).get("text").asString()).isEqualTo("x0");
    // the markdown still counts every recorded sample, and the notes say what the JSON keeps
    String text = java.nio.file.Files.readString(md);
    assertThat(text).contains("## False positives (30 of " + (ReportWriter.JSON_SAMPLES + 100) + " recorded)").contains("- a note")
        .contains("The JSON keeps the first " + ReportWriter.JSON_SAMPLES);
    assertThat(tree.get("notes").toString()).contains("The JSON keeps the first");
  }

  @Test
  void reportFileNameCarriesMatchModeAndDocLevel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
    FinderResult r = result();
    EvalReport overlap = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.OVERLAP, false), "x", "pensoft", "A");
    EvalReport docLevel = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, true), "x", "pensoft", "A");
    assertThat(ReportWriter.write(overlap, tmp).getFileName().toString()).endsWith("-x-pensoft-dev-A-OVERLAP.md");
    assertThat(ReportWriter.write(docLevel, tmp).getFileName().toString()).endsWith("-x-pensoft-dev-A-STRICT-doc.md");
  }

  @Test
  void stratumFromDocumentMetaBecomesACategory() {
    Document d = new Document("d1", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of())),
        Map.of("stratum", "BROAD_EN"));
    GoldDocument g = new GoldDocument("d1", "curated", "dev", "CC-BY", true, GoldDocument.textHash(d), d,
        List.of(new GoldName("b1", 0, 16, "Ophelia limacina", null, null)), List.of(), List.of(), Set.of());
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "b1", 21, 32, "O. limacina", "O. limacina"));
    EvalReport rep = Scorer.score(List.of(g), Map.of("d1", r), opts(MatchMode.STRICT, false));
    assertThat(rep.byCategory().get("stratum:BROAD_EN")).isEqualTo(new Metrics(1, 1, 0));
    // an untagged document adds no stratum category
    EvalReport plain = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, false));
    assertThat(plain.byCategory().keySet()).noneMatch(k -> k.startsWith("stratum:"));
    EvalReport docLevel = Scorer.score(List.of(g), Map.of("d1", r), opts(MatchMode.STRICT, true));
    assertThat(docLevel.byCategory().get("stratum:BROAD_EN")).isEqualTo(new Metrics(1, 1, 0));
  }

  @Test
  void predictedDocumentsDecideWhichPredictionsAreExcluded() {
    // mode B: the finder ran on a converted document with its own blocks; its REFERENCE block is left out like the gold's
    Document gold = new Document("d1", "en", List.of(
        new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of()),
        new Block("r1", BlockType.REFERENCE, 0, "Carabus nigrus Smith 1900", List.of(), List.of())), Map.of());
    GoldDocument g = new GoldDocument("d1", "pensoft", "dev", "CC-BY", true, GoldDocument.textHash(gold), gold,
        List.of(new GoldName("b1", 0, 16, "Ophelia limacina", null, null), new GoldName("r1", 0, 14, "Carabus nigrus", null, null)),
        List.of(), List.of(), Set.of("Ophelia limacina"));
    Document converted = new Document("d1", "en", List.of(
        new Block("b1", BlockType.TITLE, 0, "Ophelia limacina", List.of(), List.of()),
        new Block("b2", BlockType.PARAGRAPH, 0, "Carabus nigrus and Pterostichus niger", List.of(), List.of()),
        new Block("b3", BlockType.REFERENCE, 0, "Abax ater Smith", List.of(), List.of())), Map.of());
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"),
        m("2", "b3", 0, 9, "Abax ater", "Abax ater"), m("3", "b2", 0, 14, "Carabus nigrus", "Carabus nigrus"));
    ScoreOptions o = new ScoreOptions(MatchMode.STRICT, Set.of(BlockType.REFERENCE), true);

    EvalReport rep = Scorer.score(List.of(g), Map.of("d1", r), o, "x", "pensoft", "B", Map.of("d1", converted));
    // "Carabus nigrus" is gold only in the excluded reference, so it is a false positive; "Abax ater" is not counted
    assertThat(rep.names()).isEqualTo(new Metrics(1, 1, 0));
    // TITLE is the converted document's block type
    assertThat(rep.mainSubject()).isEqualTo(new Metrics(1, 0, 0));
    assertThat(rep.documents()).isEqualTo(1);

    // without the converted documents, b3 is unknown to the gold document and its mention would count
    assertThat(Scorer.score(List.of(g), Map.of("d1", r), o, "x", "pensoft", "B").names()).isEqualTo(new Metrics(1, 2, 0));
  }

  @Test
  void matchedSpanWithAWrongExpansionIsMatchedButNotNameCorrect() {
    // both spans match; the abbreviation was expanded to the wrong genus
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "b1", 21, 32, "O. limacina", "Ophion limacina"));
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, false));
    assertThat(rep.names()).isEqualTo(new Metrics(2, 0, 0));
    // the first gold name has no fullName, so only the abbreviation is in the denominator
    assertThat(rep.nameCorrect()).isEqualTo(new NameAccuracy(0, 1));
    assertThat(rep.nameCorrectByKind()).containsOnlyKeys("kind:ABBREVIATED").containsEntry("kind:ABBREVIATED", new NameAccuracy(0, 1));

    FinderResult right = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "b1", 21, 32, "O. limacina", "Ophelia  limacina"));
    EvalReport ok = Scorer.score(List.of(doc()), Map.of("d1", right), opts(MatchMode.STRICT, false));
    assertThat(ok.nameCorrect()).as("whitespace is normalised as at document level").isEqualTo(new NameAccuracy(1, 1));
    assertThat(ok.nameCorrect().rate()).isEqualTo(1.0);
    assertThat(new NameAccuracy(0, 0).rate()).isZero();
  }

  @Test
  void goldWithoutFullNameIsLeftOutOfTheNameCorrectDenominator() {
    Document d = new Document("d1", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of())), Map.of());
    GoldDocument g = gold(d.blocks(), List.of(new GoldName("b1", 0, 16, "Ophelia limacina", null, null)));
    EvalReport rep = Scorer.score(List.of(g), Map.of("d1", result(m("1", "b1", 0, 16, "Ophelia limacina", "Something else"))),
        opts(MatchMode.OVERLAP, false));
    assertThat(rep.names().tp()).isEqualTo(1);
    assertThat(rep.nameCorrect()).isEqualTo(new NameAccuracy(0, 0));
    assertThat(rep.nameCorrectByKind()).isEmpty();
    // document level compares names anyway, so there is no such metric there
    assertThat(Scorer.score(List.of(doc()), Map.of(), opts(MatchMode.STRICT, true)).nameCorrect()).isNull();
  }

  @Test
  void nameCorrectIsInTheMarkdownAndTheJson(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
    FinderResult r = result(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), m("2", "b1", 21, 32, "O. limacina", "Ophion limacina"));
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, false), "pipeline", "pensoft", "A");
    String md = ReportWriter.markdown(rep, java.time.LocalDate.of(2026, 11, 14));
    assertThat(md).contains("| Name correct | Correct | Matched | Rate |").contains("| names | 0 | 1 | 0.000 |")
        .contains("| &nbsp;&nbsp;kind:ABBREVIATED | 0 | 1 | 0.000 |");
    java.nio.file.Path file = ReportWriter.write(rep, tmp);
    String json = java.nio.file.Files.readString(tmp.resolve(file.getFileName().toString().replace(".md", ".json")));
    var tree = org.catalogueoflife.garrulus.finder.api.FinderJson.mapper().readTree(json);
    assertThat(tree.get("nameCorrect").get("correct").asInt()).isZero();
    assertThat(tree.get("nameCorrect").get("matched").asInt()).isEqualTo(1);
    assertThat(tree.get("nameCorrect").get("rate").asDouble()).isZero();
    assertThat(tree.get("nameCorrectByKind").get("kind:ABBREVIATED").get("matched").asInt()).isEqualTo(1);
  }

  @Test
  void notesAreWrittenIntoTheReport() {
    EvalReport rep = Scorer.score(List.of(doc()), Map.of(), opts(MatchMode.STRICT, true)).withNotes(List.of("3 PDFs failed"));
    assertThat(rep.notes()).containsExactly("3 PDFs failed");
    assertThat(ReportWriter.markdown(rep, java.time.LocalDate.of(2026, 11, 14))).contains("Documents: 1").contains("- 3 PDFs failed");
  }

  private static Mention withAuthorship(Mention m, String authorship) {
    return new Mention(m.id(), m.blockId(), m.start(), m.end(), m.verbatim(), m.name(), m.kind(), m.rank(), authorship, m.qualifier(),
        m.confidence(), m.evidence(), m.actId(), m.contextId(), m.hints());
  }

  @Test
  void authorshipIsScoredOnMatchedNamesOfCuratedDocuments() {
    String text = "Ophelia limacina (Rathke, 1843), Carabus auratus L. and Rosa canina L. and Aus bus Smith";
    Document d = new Document("d1", "en", List.of(new Block("b1", BlockType.ABSTRACT, 0, text, List.of(), List.of())), Map.of());
    List<GoldName> names = List.of(
        new GoldName("b1", 0, 16, "Ophelia limacina", null, null, "(Rathke, 1843)"),
        new GoldName("b1", 33, 48, "Carabus auratus", null, null, "L."),
        new GoldName("b1", 56, 67, "Rosa canina", null, null, null),
        new GoldName("b1", 75, 82, "Aus bus", null, null, "Smith"));
    GoldDocument g = new GoldDocument("d1", "curated", "test", "CC-BY", true, GoldDocument.textHash(d), d, names, List.of(), List.of(),
        Set.of());
    FinderResult r = result(
        // right, whitespace aside
        withAuthorship(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), "(Rathke,  1843)"),
        // wrong
        withAuthorship(m("2", "b1", 33, 48, "Carabus auratus", "Carabus auratus"), "Linnaeus"),
        // not in the gold
        withAuthorship(m("3", "b1", 56, 67, "Rosa canina", "Rosa canina"), "L."),
        // missed; the name span does not match, so it is not counted at all
        m("4", "b1", 75, 81, "Aus bu", "Aus bu"));
    EvalReport rep = Scorer.score(List.of(g), Map.of("d1", r), opts(MatchMode.STRICT, false));
    // tp: Ophelia; fp: Carabus (wrong), Rosa (extra); fn: Carabus (wrong)
    assertThat(rep.authorship()).isEqualTo(new Metrics(1, 2, 1));
    assertThat(rep.names()).isEqualTo(new Metrics(3, 1, 1));
    assertThat(ReportWriter.markdown(rep, java.time.LocalDate.of(2026, 11, 15))).contains("| authorship | 1 | 2 | 1 |");
  }

  @Test
  void authorshipIsNotScoredForCorporaWithoutIt() {
    FinderResult r = result(withAuthorship(m("1", "b1", 0, 16, "Ophelia limacina", "Ophelia limacina"), "(Rathke, 1843)"));
    EvalReport rep = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, false));
    assertThat(rep.authorship()).isNull();
    assertThat(ReportWriter.markdown(rep, java.time.LocalDate.of(2026, 11, 15))).doesNotContain("| authorship |");
    assertThat(Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, true)).authorship()).isNull();
  }

  @Test
  void normReadsLoneBackslashesAsSpaces() {
    assertThat(Scorer.norm("Themus \\ (Telephorops) \\ coelestis")).isEqualTo("Themus (Telephorops) coelestis");
    assertThat(Scorer.norm("\\ Aus bus \\")).isEqualTo("Aus bus");
    assertThat(Scorer.norm("a\\b")).isEqualTo("a\\b");
  }
}
