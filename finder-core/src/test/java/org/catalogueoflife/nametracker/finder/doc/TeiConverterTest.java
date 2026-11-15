package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

  // ---- GROBID token joining (excerpts of cached TEIs in tei/tokenisation.tei.xml)

  private static List<String> tokenisation() {
    try (InputStream in = TeiConverterTest.class.getResourceAsStream("/tei/tokenisation.tei.xml")) {
      return new TeiConverter().convert(in, "tok").blocks().stream().map(Block::text).toList();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test void removesASpaceBeforePunctuationAtANodeBoundary() {
    List<String> t = tokenisation();
    assertThat(t.get(0)).contains("PartitionFinder 2.1.1 (Lanfear et al. 2012) using the Bayesian");
    assertThat(t.get(1)).contains("were cited (Table 7). When these");
    assertThat(t.get(2)).isEqualTo("Ereunetis zebrina: Walsingham 1907: 715, pl. 25, fig. 16. Erechthias zebrina: Meyrick 1915b: 253.");
    assertThat(t.get(3)).isEqualTo("Vitosha Mt. (Sander 1985); Belasitsa Mts. (Bajrjamova 1988) ; Rila Mts.");
    // after a line break and after a reference
    assertThat(t.get(8)).isEqualTo("Aus bus L., 1758 and Aus bus L., 1758");
    // a note split into two paragraphs
    assertThat(t.get(11)).contains("MrModeltest v.2.3 (Nylander 2004) with the Akaike");
  }

  @Test void keepsASpaceBeforePunctuationInsideOneNode() {
    List<String> t = tokenisation();
    assertThat(t.get(3)).endsWith("(Bajrjamova 1988) ; Rila Mts.");
    assertThat(t.get(4)).contains("(Porembski andBarthlott 2000 , Twidale andVidal Romaní 2005)");
  }

  @Test void insertsASpaceAfterAFullStopAtANodeBoundary() {
    List<String> t = tokenisation();
    assertThat(t.get(5)).isEqualTo("The taxonomic basis for recent records of C. radiata (e.g. Wu et al. 2025) is unreliable.");
    assertThat(t.get(6)).startsWith("Lizard Island, Great Barrier Reef, Australia. Magalhães et al. (2019) and");
    assertThat(t.get(10)).startsWith("The description of Tuber pseudoexcavatum Y. Wang et al. (1998) was originally");
  }

  @Test void keepsAbbreviationsAndInitials() {
    List<String> t = tokenisation();
    // inside one node nothing changes, not even a missing space after a sentence
    assertThat(t.get(7)).isEqualTo(
        "Arecophila clypeata Q.R. Li, J.C. Kang & K.D. Hyde, from the U.S. collection, e.g. this one. Rhodobacteria.New class Nitrospinia");
    // initials split over two nodes stay together
    assertThat(t.get(9)).isEqualTo("collected by E.P. Turpaeva and U.S. staff");
  }

  @Test void keepsLabelLineBackslashes() {
    // ZooKeys marks the lines of a transcribed label by " \ "; that is source text, the finder handles it
    assertThat(tokenisation().get(12)).endsWith("[h]\"Themus \\ (Telephorops) \\ coelestis \\");
  }

  @Test void rejectsMalformedAndUtf16() {
    assertThatThrownBy(() -> new TeiConverter().convert(new ByteArrayInputStream("<TEI><p>".getBytes(StandardCharsets.UTF_8)), "x"))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TeiConverter().convert(new ByteArrayInputStream(new byte[]{(byte) 0xFE, (byte) 0xFF, 0, '<'}), "x"))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
