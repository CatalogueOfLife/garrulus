package org.catalogueoflife.nametracker.eval.tune;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.eval.score.Metrics;
import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.salience.SalienceProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SalienceTunerTest {
  private static Block block(String id, BlockType t) {
    return new Block(id, t, 0, "x", List.of(), List.of());
  }

  private static Mention m(String block, String name, String actId) {
    return new Mention("m" + Math.random(), block, 0, 1, name, name, NameKind.BINOMIAL, null, null, null, 1.0,
        new Evidence(Set.of(), null, null), actId, null, Map.of());
  }

  private static FinderResult result(List<Mention> ms, List<Act> acts) {
    return new FinderResult(new FinderInfo("t", "1", "h", null), ms, acts, List.of(), List.of(), Map.of());
  }

  private static GoldDocument gold(String id, List<Block> blocks, Set<String> main) {
    return new GoldDocument(id, "curated", "dev", "CC-BY", true, "", new Document(id, "en", blocks, Map.of()),
        List.of(), List.of(), List.of(), main);
  }

  /**
   * Two kinds of documents. In a title document the main name is in the title and body; a distractor is in the body once.
   * In an act document the main name has an act in the body only; the distractor is mentioned twice (more often, but no
   * act). Separating them takes a title or act weight above the threshold and a low share/repeat weight.
   */
  private static List<SalienceTuner.Doc> corpus() {
    List<SalienceTuner.Doc> docs = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      List<Block> bs = List.of(block("t", BlockType.TITLE), block("p", BlockType.PARAGRAPH));
      docs.add(SalienceTuner.prepare(gold("t" + i, bs, Set.of("Aus  bus")),
          result(List.of(m("t", "Aus bus", null), m("p", "Aus bus", null), m("p", "Cus dus", null)), List.of())));
      List<Block> bs2 = List.of(block("p", BlockType.PARAGRAPH));
      docs.add(SalienceTuner.prepare(gold("a" + i, bs2, Set.of("Eus fus")),
          result(List.of(m("p", "Eus fus", "act1"), m("p", "Gus hus", null), m("p", "Gus hus", null)),
              List.of(new Act("act1", ActType.SP_NOV, "sp. nov.", "m", 1.0)))));
    }
    return docs;
  }

  @Test
  void documentsWithoutLabelsAreDropped() {
    GoldDocument g = gold("u", List.of(block("p", BlockType.PARAGRAPH)), Set.of());
    assertThat(SalienceTuner.prepare(g, result(List.of(m("p", "Aus bus", null)), List.of()))).isNull();
  }

  @Test
  void evaluateCountsNormalisedNamesAndSkipsReferences() {
    List<Block> bs = List.of(block("t", BlockType.TITLE), block("r", BlockType.REFERENCE));
    var d = SalienceTuner.prepare(gold("d", bs, Set.of("Aus  bus")),
        result(List.of(m("t", "Aus bus", null), m("r", "Ref name", null), m("r", "Ref name", null)), List.of()));
    // title weight 0.4 >= threshold: Aus bus main (gold, tp); Ref name has no body mention so never main
    Metrics x = SalienceTuner.evaluate(List.of(d), SalienceProperties.DEFAULTS);
    assertThat(x).isEqualTo(new Metrics(1, 0, 0));
    SalienceProperties high = SalienceTuner.with(SalienceProperties.DEFAULTS, "threshold", 0.99);
    assertThat(SalienceTuner.evaluate(List.of(d), high)).isEqualTo(new Metrics(0, 0, 1));
  }

  @Test
  void anAbstractOnlyDocumentCountsBodyMentionsAsInTheAbstract() {
    var only = SalienceTuner.prepare(gold("a", List.of(block("t", BlockType.TITLE), block("b", BlockType.ABSTRACT)), Set.of("X y")),
        result(List.of(m("b", "X y", null)), List.of()));
    assertThat(only.inputs().get("X y").inAbstract()).isTrue();
    var full = SalienceTuner.prepare(gold("f", List.of(block("p", BlockType.PARAGRAPH)), Set.of("X y")),
        result(List.of(m("p", "X y", null)), List.of()));
    assertThat(full.inputs().get("X y").inAbstract()).isFalse();
    assertThat(full.inputs().get("X y").bodyCount()).isEqualTo(1);
  }

  @Test
  void actMentionsSetTheActFlag() {
    var d = corpus().get(1);
    assertThat(d.inputs().get("Eus fus").hasAct()).isTrue();
    assertThat(d.inputs().get("Gus hus").hasAct()).isFalse();
  }

  @Test
  void searchFindsAPerfectSettingFromABadStart() {
    var docs = corpus();
    SalienceProperties bad = new SalienceProperties(0, 0, 0, 0, 0, 0, 0.5, 0.02, 0.3);
    assertThat(SalienceTuner.evaluate(docs, bad).f1()).isZero();
    var r = SalienceTuner.search(docs, List.of(bad), SalienceTuner.Grids.defaults());
    assertThat(r.metrics()).isEqualTo(new Metrics(6, 0, 0));
    assertThat(SalienceTuner.evaluate(docs, r.props())).isEqualTo(r.metrics());
    // ubiquity settings are never touched
    assertThat(r.props().ubiquityWeight()).isEqualTo(0.5);
    assertThat(r.props().ubiquityFullShare()).isEqualTo(0.02);
  }

  @Test
  void aStartThatIsAlreadyBestIsKept() {
    var docs = corpus();
    var first = SalienceTuner.search(docs, List.of(SalienceProperties.DEFAULTS), SalienceTuner.Grids.defaults());
    var again = SalienceTuner.search(docs, List.of(first.props()), SalienceTuner.Grids.defaults());
    assertThat(again.props()).isEqualTo(first.props());
    assertThat(again.passes()).isEqualTo(1);
  }

  @Test
  void gridStepsAreExact() {
    assertThat(SalienceTuner.Grids.defaults().weights()).containsExactly(0, 0.1, 0.2, 0.3, 0.4, 0.5);
    assertThat(SalienceTuner.Grids.defaults().thresholds()).hasSize(9).startsWith(0.2).endsWith(0.6);
  }
}
