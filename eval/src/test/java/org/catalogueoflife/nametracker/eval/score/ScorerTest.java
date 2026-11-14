package org.catalogueoflife.nametracker.eval.score;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.finder.api.Block;
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
    assertThat(md.getFileName().toString()).endsWith("-italics-pensoft-dev-A.md");
    assertThat(java.nio.file.Files.readString(md)).contains("| names | 1 | 0 | 1 |").contains("False negatives");
    assertThat(java.nio.file.Files.readString(tmp.resolve(md.getFileName().toString().replace(".md", ".json")))).contains("\"finder\" : \"italics\"");
  }
}
