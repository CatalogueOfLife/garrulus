package org.catalogueoflife.nametracker.eval.tune;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.corpus.GoldName;
import org.catalogueoflife.nametracker.eval.score.MatchMode;
import org.catalogueoflife.nametracker.eval.score.Metrics;
import org.catalogueoflife.nametracker.eval.score.ScoreOptions;
import org.catalogueoflife.nametracker.eval.score.Scorer;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineNameFinder;
import org.catalogueoflife.nametracker.finder.pipeline.ThresholdJudge;
import org.gbif.nameparser.rust.NameParserRust;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeTunerTest {
  private static final String TSV = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
      + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n3\tgenus\tAbax\t\t\t\n4\tspecies\t\tAbax\tater\t\n"
      + "5\tgenus\tYunnan\t\t\t\n6\tfamily\tCarabidae\t\t\t\n7\tgenus\tMajor\t\t\t\n";
  private static final String TEXT = "We collected Carabus nigrus and Abax ater in Yunnan. Carabus is common in Yunnan; "
      + "the family Carabidae is large. Major finds were made by Abax.";
  private static final String REF = "Smith J (2000) Abax in Yunnan.";

  /** The pipeline without gnfinder, on a tiny dictionary. */
  private static PipelineNameFinder finder() throws Exception {
    var dict = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(TSV), "t1", CommonWords.load(CommonWords.DEFAULT_LANGS));
    PipelineConfig cfg = PipelineConfig.load("{\"enabledGenerators\": [\"DICTIONARY\", \"SUFFIX\", \"ITALIC\"]}");
    return new PipelineNameFinder(cfg, null, dict, new NameParserRust());
  }

  private static int at(String s) {
    return TEXT.indexOf(s);
  }

  private static GoldDocument gold() {
    Document d = new Document("d1", "en", List.of(
        new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(new StyleSpan(at("Carabus nigrus"), at("Carabus nigrus") + 14, Style.ITALIC),
            new StyleSpan(at("Abax ater"), at("Abax ater") + 9, Style.ITALIC)), List.of()),
        new Block("r1", BlockType.REFERENCE, 0, REF, List.of(), List.of())), Map.of());
    int carabus2 = TEXT.indexOf("Carabus is");
    List<GoldName> names = List.of(
        new GoldName("b1", at("Carabus nigrus"), at("Carabus nigrus") + 14, "Carabus nigrus", null, null),
        new GoldName("b1", at("Abax ater"), at("Abax ater") + 9, "Abax ater", null, null),
        new GoldName("b1", carabus2, carabus2 + 7, "Carabus", null, null),
        new GoldName("b1", at("Carabidae"), at("Carabidae") + 9, "Carabidae", null, null),
        new GoldName("b1", TEXT.lastIndexOf("Abax"), TEXT.lastIndexOf("Abax") + 4, "Abax", null, null),
        new GoldName("r1", 15, 19, "Abax", null, null));
    return new GoldDocument("d1", "pensoft", "dev", "CC-BY", true, GoldDocument.textHash(d), d, names, List.of(), List.of(), Set.of());
  }

  private static LinkedHashMap<String, double[]> values() {
    LinkedHashMap<String, double[]> v = new LinkedHashMap<>();
    v.put("dictHit.GENUS", new double[]{-2, 0, 1.5, 4});
    v.put("ambiguousWord", new double[]{-6, -2.5, 0});
    v.put("suffixRule", new double[]{0, 2});
    return v;
  }

  @Test
  void fastCountsEqualTheScorerAtEveryGridPoint() throws Exception {
    PipelineNameFinder f = finder();
    GoldDocument g = gold();
    PipelineNameFinder.Prepared p = f.prepare(g.document(), FinderOptions.defaults());
    // the fixture must leave the judge something to decide
    assertThat(p.candidates()).anyMatch(c -> !c.certain());

    PipelineConfig base = f.config();
    List<JudgeTuner.Setting> grid = JudgeTuner.grid(base, new double[]{0.3, 0.5, 0.8}, values());
    assertThat(grid).hasSize(3 * 4 * 3 * 2);
    for (boolean docLevel : new boolean[]{false, true}) {
      ScoreOptions opts = new ScoreOptions(MatchMode.STRICT, ScoreOptions.pensoft(MatchMode.STRICT).excludedBlocks(), docLevel);
      JudgeTuner tuner = new JudgeTuner(base, List.copyOf(values().keySet()),
          List.of(new JudgeTuner.Target("t", List.of(g), Map.of("d1", p), opts)));
      Set<Metrics> distinct = new HashSet<>();
      for (JudgeTuner.Setting s : grid) {
        FinderResult r = f.finish(p, new ThresholdJudge(s.weights(), base.judgeBias(), s.threshold()));
        Metrics real = Scorer.score(List.of(g), Map.of("d1", r), opts).names();
        Metrics fast = tuner.evaluate(s).names().get(0);
        assertThat(fast).as("%s, document level %s", s, docLevel).isEqualTo(real);
        distinct.add(fast);
      }
      assertThat(distinct).as("the grid must change the outcome").hasSizeGreaterThan(1);
    }
  }

  @Test
  void searchPutsTheBestFirstAndBreaksTiesTowardsTheBase() throws Exception {
    PipelineNameFinder f = finder();
    GoldDocument g = gold();
    PipelineNameFinder.Prepared p = f.prepare(g.document(), FinderOptions.defaults());
    PipelineConfig base = f.config();
    JudgeTuner tuner = new JudgeTuner(base, List.copyOf(values().keySet()),
        List.of(new JudgeTuner.Target("A", List.of(g), Map.of("d1", p), ScoreOptions.pensoft(MatchMode.STRICT))));
    List<JudgeTuner.Setting> grid = JudgeTuner.grid(base, new double[]{0.3, 0.5, 0.8}, values());
    List<JudgeTuner.Outcome> ranked = tuner.search(grid);
    JudgeTuner.Outcome best = ranked.get(0);
    assertThat(ranked).allMatch(o -> o.objective() <= best.objective());
    for (JudgeTuner.Outcome o : ranked) {
      if (o.objective() == best.objective()) assertThat(o.distance()).isGreaterThanOrEqualTo(best.distance());
    }
    // the same search gives the same order
    assertThat(tuner.search(grid)).extracting(JudgeTuner.Outcome::setting).containsExactlyElementsOf(
        ranked.stream().map(JudgeTuner.Outcome::setting).toList());
    // and the base setting is evaluated like any other
    assertThat(tuner.evaluate(tuner.baseSetting()).distance()).isZero();
  }

  @Test
  void overridesJsonHoldsOnlyChangedWeights() throws Exception {
    PipelineConfig base = finder().config();
    Map<String, Double> w = new java.util.HashMap<>(base.judgeWeights());
    double italic = base.judgeWeights().get("italic") + 1.5; // a value the defaults do not have, whatever tuning gave
    w.put("italic", italic);
    String json = new JudgeTuner.Setting(0.6, w).overridesJson(base);
    assertThat(json).isEqualTo("{\"judgeThreshold\":0.6,\"judgeWeights\":{\"italic\":" + italic + "}}");
    PipelineConfig tuned = base.withOverrides(json);
    assertThat(tuned.judgeWeights().get("italic")).isEqualTo(italic);
    assertThat(tuned.judgeThreshold()).isEqualTo(0.6);
  }

  @Test
  void untunedWeightsMustStayAtTheBase() throws Exception {
    PipelineConfig base = finder().config();
    JudgeTuner tuner = new JudgeTuner(base, List.of("italic"), List.of());
    Map<String, Double> w = new java.util.HashMap<>(base.judgeWeights());
    w.put("suffixRule", 9.0);
    assertThatThrownBy(() -> tuner.evaluate(new JudgeTuner.Setting(0.5, w))).hasMessageContaining("suffixRule");
    assertThatThrownBy(() -> new JudgeTuner(base, List.of("itallic"), List.of())).hasMessageContaining("itallic");
  }
}
