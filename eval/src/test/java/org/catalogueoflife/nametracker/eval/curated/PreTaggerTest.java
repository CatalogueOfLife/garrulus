package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.ItalicsFinder;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.JudgeVerdict;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PreTaggerTest {
  private static final String TEXT = "We saw Carabus auratus and Major beetles here.";
  private static final Document DOC = new Document("d", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, TEXT,
      List.of(new StyleSpan(7, 22, Style.ITALIC)), List.of())), Map.of());

  private static Mention m(String id, int s, int e, @Nullable Double judgeScore) {
    JudgeVerdict v = judgeScore == null ? null : new JudgeVerdict("threshold", judgeScore, judgeScore >= 0.5, null);
    return new Mention(id, "b1", s, e, TEXT.substring(s, e), TEXT.substring(s, e), NameKind.BINOMIAL, null, null, null,
        judgeScore == null ? 0.95 : judgeScore, new Evidence(Set.of(Generator.DICTIONARY), null, v), null, null, Map.of());
  }

  /** A pipeline-like finder: accepted mentions plus, when asked, the rejected candidates. Records the options it got. */
  private static final class Stub implements NameFinder {
    final List<Mention> accepted;
    final List<Mention> rejected;
    final List<FinderOptions> asked = new ArrayList<>();

    Stub(List<Mention> accepted, List<Mention> rejected) {
      this.accepted = accepted;
      this.rejected = rejected;
    }

    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", "h", null);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      asked.add(opts);
      return new FinderResult(info(), accepted, List.of(), List.of(), opts.includeRejected() ? rejected : List.of(), Map.of());
    }
  }

  @Test
  void unionDeduplicatesBySpan() {
    List<PreTagger.PreTag> one = PreTagger.pretag(DOC, List.of(new ItalicsFinder()));
    assertThat(one).hasSize(1);
    assertThat(one.get(0).score()).isEqualTo(1.0);
    List<PreTagger.PreTag> two = PreTagger.pretag(DOC, List.of(new ItalicsFinder(), new ItalicsFinder()));
    assertThat(two).hasSize(1);
    assertThat(PreTagger.pretag(DOC, List.of())).isEmpty();
  }

  @Test
  void rejectedPipelineCandidatesArePreTaggedWithTheirJudgeScore() {
    // the pipeline accepted "Carabus auratus" as certain (no judge) and rejected "Major" with score 0.2
    Stub pipeline = new Stub(List.of(m("m1", 7, 22, null)), List.of(m("r1", 27, 32, 0.2)));
    List<PreTagger.PreTag> tags = PreTagger.pretag(DOC, List.of(pipeline));
    assertThat(pipeline.asked).containsExactly(new FinderOptions(true, null));
    assertThat(tags).extracting(t -> t.mention().verbatim()).containsExactly("Carabus auratus", "Major");
    assertThat(tags).extracting(PreTagger.PreTag::score).containsExactly(1.0, 0.2);
  }

  @Test
  void aSpanThePipelineJudgedKeepsTheJudgeScoreWhoeverProposedItFirst() {
    // italics proposes "Carabus auratus" first; the pipeline judged the same span 0.7: one region, scored 0.7
    Stub pipeline = new Stub(List.of(m("m1", 7, 22, 0.7)), List.of(m("r1", 27, 32, 0.1)));
    List<PreTagger.PreTag> tags = PreTagger.pretag(DOC, List.of(new ItalicsFinder(), pipeline));
    assertThat(tags).hasSize(2);
    assertThat(tags.get(0).mention().verbatim()).isEqualTo("Carabus auratus");
    assertThat(tags.get(0).score()).isEqualTo(0.7);
    assertThat(tags.get(1).score()).isEqualTo(0.1);
  }
}
