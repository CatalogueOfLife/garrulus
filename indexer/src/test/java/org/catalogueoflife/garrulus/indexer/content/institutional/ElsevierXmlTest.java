package org.catalogueoflife.garrulus.indexer.content.institutional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.CodePoints;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Style;
import org.catalogueoflife.garrulus.finder.api.StyleSpan;
import org.catalogueoflife.garrulus.indexer.content.route.RouteTestSupport;
import org.junit.jupiter.api.Test;

class ElsevierXmlTest {

  private static Document sample() {
    return ElsevierXml.toDocument(RouteTestSupport.fixture("institutional/elsevier-article.xml")
        .getBytes(StandardCharsets.UTF_8), "work:7");
  }

  @Test
  void blocksInDocumentOrder() {
    Document d = sample();
    assertThat(d.id()).isEqualTo("work:7");
    assertThat(d.blocks()).extracting(Block::type).containsExactly(BlockType.TITLE, BlockType.ABSTRACT,
        BlockType.HEADING, BlockType.PARAGRAPH, BlockType.HEADING, BlockType.PARAGRAPH, BlockType.CAPTION,
        BlockType.HEADING, BlockType.REFERENCE);
    assertThat(d.blocks()).extracting(Block::id).containsExactly("b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9");
    assertThat(d.blocks()).extracting(Block::text).containsExactly(
        "A new species of Aus from Borneo",
        "We describe Aus bus sp. nov. from Sabah.",
        "Introduction",
        "The genus Aus Smith, 1900 is known from Asia (Fig. 1).",
        "Material",
        "Specimens of Aus bus were measured as mm.",
        "Habitus of Aus bus.",
        "References",
        "Smith, 1900 Smith On Aus");
  }

  @Test
  void headOnlyGivesTitleAndAbstractOnce() {
    // the ce:abstract and ce:keywords of the article head repeat coredata or are not text: not read
    assertThat(sample().blocks()).filteredOn(b -> b.text().equals("Abstract") || b.text().equals("Keywords"))
        .isEmpty();
  }

  @Test
  void italicSpansInCodePoints() {
    List<Block> b = sample().blocks();
    assertSpan(b.get(3), "Aus");
    assertSpan(b.get(5), "Aus bus");
    assertSpan(b.get(6), "Aus bus");
    assertThat(b.get(0).styles()).isEmpty();
  }

  private static void assertSpan(Block b, String italic) {
    assertThat(b.styles()).hasSize(1);
    StyleSpan s = b.styles().get(0);
    assertThat(s.style()).isEqualTo(Style.ITALIC);
    assertThat(CodePoints.slice(b.text(), s.start(), s.end())).isEqualTo(italic);
  }

  @Test
  void headingLevelsAndSectionPaths() {
    List<Block> b = sample().blocks();
    assertThat(b.get(2).level()).isEqualTo(1);
    assertThat(b.get(2).sectionPath()).isEmpty();
    assertThat(b.get(3).sectionPath()).containsExactly("Introduction");
    assertThat(b.get(4).level()).isEqualTo(2);
    assertThat(b.get(4).sectionPath()).containsExactly("Introduction");
    assertThat(b.get(5).sectionPath()).containsExactly("Introduction", "Material");
    assertThat(b.get(6).sectionPath()).isEmpty();
  }

  @Test
  void ligaturesAndSurrogatesKeepOffsets() {
    String xml = "<full-text-retrieval-response xmlns:ce=\"http://www.elsevier.com/xml/common/dtd\">"
        + "<originalText><ce:para>😀 ﬁne <ce:italic>Aus</ce:italic> end</ce:para></originalText>"
        + "</full-text-retrieval-response>";
    Block p = ElsevierXml.toDocument(xml.getBytes(StandardCharsets.UTF_8), "d").blocks().get(0);
    assertThat(p.text()).isEqualTo("😀 fine Aus end");
    assertThat(CodePoints.slice(p.text(), p.styles().get(0).start(), p.styles().get(0).end())).isEqualTo("Aus");
  }

  @Test
  void abstractOnlyAnswerHasNoParagraph() {
    String xml = "<full-text-retrieval-response xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><coredata>"
        + "<dc:title>T</dc:title><dc:description>Only an abstract.</dc:description></coredata>"
        + "</full-text-retrieval-response>";
    assertThat(ElsevierXml.toDocument(xml.getBytes(StandardCharsets.UTF_8), "d").blocks())
        .extracting(Block::type).containsExactly(BlockType.TITLE, BlockType.ABSTRACT);
  }

  @Test
  void entitiesAreNeverResolved() {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
        + "<full-text-retrieval-response><coredata><dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">&e;"
        + "</dc:title></coredata></full-text-retrieval-response>";
    assertThatThrownBy(() -> ElsevierXml.toDocument(xml.getBytes(StandardCharsets.UTF_8), "d"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
