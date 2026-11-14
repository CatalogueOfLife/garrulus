package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.eval.corpus.GoldAct;
import org.catalogueoflife.nametracker.eval.corpus.GoldContext;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.ContextScope;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.gbif.nameparser.api.Rank;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameKind;
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
  void reportFileNameCarriesMatchModeAndDocLevel(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) {
    FinderResult r = result();
    EvalReport overlap = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.OVERLAP, false), "x", "pensoft", "A");
    EvalReport docLevel = Scorer.score(List.of(doc()), Map.of("d1", r), opts(MatchMode.STRICT, true), "x", "pensoft", "A");
    assertThat(ReportWriter.write(overlap, tmp).getFileName().toString()).endsWith("-x-pensoft-dev-A-OVERLAP.md");
    assertThat(ReportWriter.write(docLevel, tmp).getFileName().toString()).endsWith("-x-pensoft-dev-A-STRICT-doc.md");
  }
}
