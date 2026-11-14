package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.gbif.nameparser.rust.NameParserRust;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorshipAttacherTest {
  private static final AuthorshipAttacher ATT = new AuthorshipAttacher(new NameParserRust());

  /** A merged candidate for the first occurrence of name in text. */
  private static MergedCandidate cand(String text, String name) {
    int s = CodePoints.toCodePoint(text, text.indexOf(name));
    int e = s + CodePoints.length(name);
    return new MergedCandidate("b1", s, e, name, name, Set.of(Generator.GNFINDER), Map.of(), null, null);
  }

  private static Block block(String text) {
    return new Block("b1", BlockType.PARAGRAPH, 0, text, null, null);
  }

  private static MergedCandidate attach(String text, String name) {
    return ATT.attach(cand(text, name), block(text));
  }

  @Test
  void authorshipWithParentheses() {
    var r = attach("We found Panthera leo (Linnaeus, 1758) in the park.", "Panthera leo");
    assertThat(r.authorship()).isEqualTo("(Linnaeus, 1758)");
    assertThat(r.end()).isEqualTo(cand("We found Panthera leo (Linnaeus, 1758) in the park.", "Panthera leo").end());
    assertThat(r.verbatim()).isEqualTo("Panthera leo");
  }

  @Test
  void authorshipPlainZoological() {
    assertThat(attach("Ophelia Savigny, 1822 is a polychaete.", "Ophelia").authorship()).isEqualTo("Savigny, 1822");
  }

  @Test
  void twoAuthorsWithYear() {
    assertThat(attach("Carabus nigrus Smith & Jones, 1999 was seen.", "Carabus nigrus").authorship())
        .isEqualTo("Smith & Jones, 1999");
  }

  @Test
  void botanicalAuthorship() {
    assertThat(attach("The wild Rosa canina L. grows here.", "Rosa canina").authorship()).isEqualTo("L.");
    assertThat(attach("Rosa canina L.", "Rosa canina").authorship()).isEqualTo("L.");
    assertThat(attach("Lonicera x Benth. and more", "Lonicera").authorship()).isNull();
    assertThat(attach("Ranunculus acris Hook.f. is", "Ranunculus acris").authorship()).isEqualTo("Hook.f.");
  }

  @Test
  void initialsAndSurname() {
    assertThat(attach("Carabus nigrus A. Smith was", "Carabus nigrus").authorship()).isEqualTo("A. Smith");
  }

  @Test
  void ordinaryWordIsNotAuthorship() {
    assertThat(attach("Rosa canina grows here.", "Rosa canina").authorship()).isNull();
    assertThat(attach("Rosa canina. The plant is tall.", "Rosa canina").authorship()).isNull();
    assertThat(attach("Rosa canina", "Rosa canina").authorship()).isNull();
  }

  @Test
  void cfQualifierRemovedFromName() {
    var r = attach("Carabus cf. nigrus was found.", "Carabus cf. nigrus");
    assertThat(r.name()).isEqualTo("Carabus nigrus");
    assertThat(r.qualifier()).isEqualTo("cf.");
    assertThat(r.verbatim()).isEqualTo("Carabus cf. nigrus");
    assertThat(r.start()).isEqualTo(0);
    assertThat(r.end()).isEqualTo(18);
  }

  @Test
  void spQualifierOnGenus() {
    var inSpan = attach("Carabus sp. was found.", "Carabus sp.");
    assertThat(inSpan.name()).isEqualTo("Carabus");
    assertThat(inSpan.qualifier()).isEqualTo("sp.");
    var inTail = attach("Carabus sp. was found.", "Carabus");
    assertThat(inTail.name()).isEqualTo("Carabus");
    assertThat(inTail.qualifier()).isEqualTo("sp.");
    assertThat(inTail.end()).isEqualTo(7);
  }

  @Test
  void spNovIsNotAQualifier() {
    assertThat(attach("Carabus sp. nov. is described", "Carabus").qualifier()).isNull();
    assertThat(attach("Carabus sp. nov. is described", "Carabus sp. nov.").qualifier()).isNull();
    assertThat(attach("Carabus sp. n. is described", "Carabus").qualifier()).isNull();
  }

  @Test
  void figureReferenceIsNotAuthorship() {
    assertThat(attach("Rosa canina Fig. 2 shows the plant.", "Rosa canina").authorship()).isNull();
    assertThat(attach("Rosa canina Tab. 3 shows the plant.", "Rosa canina").authorship()).isNull();
  }

  @Test
  void otherQualifiers() {
    var aff = attach("Carabus aff. nigrus was found.", "Carabus aff. nigrus");
    assertThat(aff.name()).isEqualTo("Carabus nigrus");
    assertThat(aff.qualifier()).isEqualTo("aff.");
    var nr = attach("Carabus nr. nigrus was found.", "Carabus nr. nigrus");
    assertThat(nr.name()).isEqualTo("Carabus nigrus");
    assertThat(nr.qualifier()).isEqualTo("nr.");
    var spp = attach("Carabus spp. were found.", "Carabus");
    assertThat(spp.name()).isEqualTo("Carabus");
    assertThat(spp.qualifier()).isEqualTo("spp.");
    var sl = attach("Carabus nigrus s.l. was found.", "Carabus nigrus s.l.");
    assertThat(sl.name()).isEqualTo("Carabus nigrus");
    assertThat(sl.qualifier()).isEqualTo("s.l.");
    var sstr = attach("Carabus nigrus s.str. was found.", "Carabus nigrus");
    assertThat(sstr.name()).isEqualTo("Carabus nigrus");
    assertThat(sstr.qualifier()).isEqualTo("s.str.");
  }
}
