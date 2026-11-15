package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelSeparatorsTest {
  @Test
  void aLoneBackslashBetweenWhitespaceBecomesASpace() {
    assertThat(LabelSeparators.mask("[h]\"Themus \\ (Telephorops) \\ coelestis \\"))
        .isEqualTo("[h]\"Themus   (Telephorops)   coelestis  ");
    assertThat(LabelSeparators.mask("\\ China \\ Shanghai")).isEqualTo("  China   Shanghai");
  }

  @Test
  void otherBackslashesStay() {
    for (String s : List.of("a\\b", "ov.\\ V.", "a \\\\ b", "C:\\ x", "x \\n")) {
      assertThat(LabelSeparators.mask(s)).as(s).isSameAs(s);
    }
    String none = "Carabus nigrus";
    assertThat(LabelSeparators.mask(none)).isSameAs(none);
  }

  @Test
  void maskedDocumentKeepsLengthsAndEverythingElse() {
    Block label = new Block("b1", BlockType.CAPTION, 0, "Themus \\ coelestis", List.of(new StyleSpan(0, 6, Style.ITALIC)), List.of("s1"));
    Block plain = new Block("b2", BlockType.PARAGRAPH, 0, "Carabus nigrus", List.of(), List.of());
    Document d = new Document("d", "en", List.of(label, plain), Map.of());
    Document m = LabelSeparators.mask(d);
    assertThat(m.blocks()).hasSize(2);
    assertThat(m.blocks().get(0)).isEqualTo(new Block("b1", BlockType.CAPTION, 0, "Themus   coelestis", label.styles(), label.sectionPath()));
    assertThat(m.blocks().get(1)).isSameAs(plain);
    assertThat(m.id()).isEqualTo("d");
    assertThat(m.language()).isEqualTo("en");
    // a document without label separators is returned as it is
    Document clean = new Document("c", null, List.of(plain), Map.of());
    assertThat(LabelSeparators.mask(clean)).isSameAs(clean);
  }
}
