package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtuIdentifierGeneratorTest {
  private static List<Candidate> find(String text) {
    Document d = new Document("d", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(), List.of())), Map.of());
    return new OtuIdentifierGenerator().generate(d, FlatText.of(d), new GenerationContext(null));
  }

  private static String span(String text, Candidate c) {
    return CodePoints.slice(text, c.start(), c.end());
  }

  @Test
  void findsBoldBin() {
    String t = "specimens of BIN BOLD:AAB2345 were";
    List<Candidate> cs = find(t);
    assertThat(cs).hasSize(1);
    Candidate c = cs.get(0);
    assertThat(span(t, c)).isEqualTo("BOLD:AAB2345");
    assertThat(c.verbatim()).isEqualTo("BOLD:AAB2345");
    assertThat(c.name()).isEqualTo("BOLD:AAB2345");
    assertThat(c.generator()).isEqualTo(Generator.OTU);
    assertThat(c.features()).containsEntry("scheme", "BOLD_BIN");
  }

  @Test
  void normalisesSpacedBin() {
    String t = "see BOLD: ACF1234 here";
    Candidate c = find(t).get(0);
    assertThat(span(t, c)).isEqualTo("BOLD: ACF1234");
    assertThat(c.name()).isEqualTo("BOLD:ACF1234");
  }

  @Test
  void findsUniteSh() {
    String t = "species hypothesis SH1556109.08FU was";
    List<Candidate> cs = find(t);
    assertThat(cs).hasSize(1);
    assertThat(cs.get(0).name()).isEqualTo("SH1556109.08FU");
    assertThat(cs.get(0).features()).containsEntry("scheme", "UNITE_SH");
  }

  @Test
  void identifiersInsidePunctuation() {
    for (String[] c : new String[][] {{"(BOLD:AAB2345)", "BOLD:AAB2345"}, {"BOLD:AAB2345;", "BOLD:AAB2345"},
        {"SH1556109.08FU.", "SH1556109.08FU"}}) {
      List<Candidate> cs = find(c[0]);
      assertThat(cs).as(c[0]).hasSize(1);
      assertThat(span(c[0], cs.get(0))).isEqualTo(c[1]);
    }
  }

  @Test
  void lookAlikesDoNotMatch() {
    for (String t : List.of("BOLD:AA12345", "bold:AAB2345", "SH1556109.08", "SH1556109.08FUN", "XSH1556109.08FU",
        "BOLD:AAB23456", "xBOLD:AAB2345")) {
      assertThat(find(t)).as(t).isEmpty();
    }
  }

  @Test
  void uniteDoiYieldsTheShCode() {
    String t = "https://doi.org/10.15156/BIO/SH1556109.08FU";
    List<Candidate> cs = find(t);
    assertThat(cs).hasSize(1);
    assertThat(span(t, cs.get(0))).isEqualTo("SH1556109.08FU");
  }

  @Test
  void codePointOffsets() {
    String t = "𝔄 BOLD:AAB2345 and SH1556109.08FU";
    List<Candidate> cs = find(t);
    assertThat(cs).hasSize(2);
    assertThat(cs.get(0).start()).isEqualTo(2);
    assertThat(cs.get(0).end()).isEqualTo(14);
    assertThat(span(t, cs.get(1))).isEqualTo("SH1556109.08FU");
  }
}
