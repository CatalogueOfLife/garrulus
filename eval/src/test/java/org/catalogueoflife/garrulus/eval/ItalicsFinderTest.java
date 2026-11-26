package org.catalogueoflife.garrulus.eval;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.api.Style;
import org.catalogueoflife.garrulus.finder.api.StyleSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItalicsFinderTest {
  private FinderResult find(String text, int start, int end) {
    Block b = new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(new StyleSpan(start, end, Style.ITALIC)), List.of());
    return new ItalicsFinder().find(new Document("d", "en", List.of(b), Map.of()), FinderOptions.defaults());
  }

  @Test
  void italicBinomialGivesOneMention() {
    FinderResult r = find("We found Carabus nigrus here.", 9, 23);
    assertThat(r.mentions()).hasSize(1);
    Mention m = r.mentions().get(0);
    assertThat(m.kind()).isEqualTo(NameKind.BINOMIAL);
    assertThat(m.verbatim()).isEqualTo("Carabus nigrus");
    assertThat(m.start()).isEqualTo(9);
    assertThat(m.end()).isEqualTo(23);
    assertThat(m.confidence()).isEqualTo(0.5);
    assertThat(r.info().name()).isEqualTo("italics");
  }

  @Test
  void etAlGivesNone() {
    assertThat(find("Smith et al. found it.", 6, 12).mentions()).isEmpty();
  }

  @Test
  void trimsPunctuationAndHandlesInitialsAndLongSpans() {
    FinderResult r = find("see O. limacina, and", 4, 17);
    assertThat(r.mentions()).hasSize(1);
    assertThat(r.mentions().get(0).verbatim()).isEqualTo("O. limacina");
    assertThat(r.mentions().get(0).kind()).isEqualTo(NameKind.ABBREVIATED);
    assertThat(find("Carabus nigrus alpinus Smith", 0, 28).mentions()).isEmpty();
    assertThat(find("Carabus", 0, 7).mentions().get(0).kind()).isEqualTo(NameKind.UNINOMIAL);
  }

  @Test
  void factoryListsAvailableOnUnknown() {
    assertThat(FinderFactory.create("italics")).isInstanceOf(ItalicsFinder.class);
    assertThatThrownBy(() -> FinderFactory.create("nope")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("italics");
  }

  @Test
  void badModeSplitOrTuneSplitFailClearly() {
    assertThat(runErr("run", "--finder", "italics", "--corpus", "x", "--split", "dev", "--mode", "C")).contains("--mode must be A or B");
    assertThat(runErr("run", "--finder", "italics", "--corpus", "x", "--split", "train", "--mode", "B")).contains("--split must be dev or test");
    // tuning on test would leak the held-out split into the defaults
    assertThat(runErr("tune", "--corpus", "x", "--split", "test")).contains("dev split only");
  }

  private static String runErr(String... args) {
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    java.io.PrintStream old = System.err;
    System.setErr(new java.io.PrintStream(err));
    try {
      assertThat(EvalCli.commandLine().execute(args)).isNotZero();
    } finally {
      System.setErr(old);
    }
    return err.toString();
  }
}
