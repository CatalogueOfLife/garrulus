package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeiConverterTest {
  private Document sample() {
    try (InputStream in = getClass().getResourceAsStream("/tei/grobid-sample.tei.xml")) {
      return new TeiConverter().convert(in, "tei1");
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test void mapsTeiBlocks() {
    Document d = sample();
    assertThat(d.id()).isEqualTo("tei1");
    assertThat(d.blocks()).extracting(Block::type).containsExactly(
      BlockType.TITLE, BlockType.ABSTRACT,
      BlockType.HEADING, BlockType.PARAGRAPH, BlockType.PARAGRAPH, BlockType.FOOTNOTE,
      BlockType.HEADING, BlockType.PARAGRAPH, BlockType.CAPTION, BlockType.CAPTION,
      BlockType.TABLE_CELL, BlockType.TABLE_CELL, BlockType.TABLE_CELL, BlockType.TABLE_CELL,
      BlockType.REFERENCE, BlockType.REFERENCE);
    assertThat(d.blocks().get(0).text()).isEqualTo("A revision of the ground beetle genus Pterostichus in the Alps");
    assertThat(d.blocks().get(2).text()).isEqualTo("Introduction");
    assertThat(d.blocks().get(2).level()).isEqualTo(1);
    // line breaks are normalised away, hyphenation is covered separately
    assertThat(d.blocks().get(3).text()).isEqualTo("The genus Pterosti-chus niger is common in forests of northern Europe.");
    assertThat(d.blocks().get(5).text()).isEqualTo("See also Carabus auratus.");
    assertThat(d.blocks().get(8).text()).isEqualTo("Habitus of Pterostichus niger, male.");
    assertThat(d.blocks()).allSatisfy(b -> assertThat(b.styles()).isEmpty());
    assertThat(d.blocks()).extracting(Block::id).startsWith("b1", "b2", "b3");
    assertThat(d.blocks().get(3).sectionPath()).isEmpty();
  }

  @Test void referencesAreReferenceBlocks() {
    Document d = sample();
    var refs = d.blocks().stream().filter(b -> b.type() == BlockType.REFERENCE).toList();
    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).text()).contains("Ground beetles of Europe").contains("Smith").contains("Zootaxa");
    assertThat(refs.get(1).text()).contains("Carabidae of the World").contains("Doe");
    // the sourceDesc biblStruct of the header is not a reference
    assertThat(d.blocks().stream().anyMatch(b -> b.text().contains("Example"))).isFalse();
  }

  @Test void dehyphenatesInConverterWithDictionary() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/tei/grobid-sample.tei.xml")) {
      Document d = new TeiConverter().convert(in, "t", "pterostichus"::equals);
      assertThat(d.blocks().get(3).text()).isEqualTo("The genus Pterostichus niger is common in forests of northern Europe.");
    }
  }

  @Test void keepsHyphenWithoutDictionary() {
    assertThat(sample().blocks().get(3).text()).contains("Pterosti-chus niger");
  }

  @Test void midLineHyphenIsNotALineBreak() {
    String xml = "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><text><body><div><p>pre- and post-treatment</p></div></body></text></TEI>";
    Document d = new TeiConverter().convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "x", w -> true);
    assertThat(d.blocks().get(0).text()).isEqualTo("pre- and post-treatment");
  }

  @Test void doesNotResolveExternalEntities() {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE TEI [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
      + "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><text><body><div><p>a &x; b</p></div></body></text></TEI>";
    try {
      Document d = new TeiConverter().convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "x");
      assertThat(d.blocks().toString()).doesNotContain("root:");
    } catch (IllegalArgumentException expected) {
      // rejecting is fine too
    }
  }

  @Test void rejectsMalformedAndUtf16() {
    assertThatThrownBy(() -> new TeiConverter().convert(new ByteArrayInputStream("<TEI><p>".getBytes(StandardCharsets.UTF_8)), "x"))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TeiConverter().convert(new ByteArrayInputStream(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, '<'}), "x"))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
