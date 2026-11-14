package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JatsConverterTest {
  private static final String FIXTURE = "/jats/zookeys-218447-excerpt.xml";

  private static InputStream fixture() {
    return JatsConverterTest.class.getResourceAsStream(FIXTURE);
  }

  private static Conversion convert(JatsOptions opts) {
    return new JatsConverter().convert(fixture(), "doc1", opts);
  }

  private static Conversion convertXml(String xml) {
    return new JatsConverter().convert(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "d", JatsOptions.defaults());
  }

  private static String slice(Block b, int start, int end) {
    return CodePoints.slice(b.text(), start, end);
  }

  private static List<String> spans(Block b, Style style) {
    return b.styles().stream().filter(s -> s.style() == style).map(s -> slice(b, s.start(), s.end())).toList();
  }

  private static Block first(Conversion c, BlockType type, String startsWith) {
    return c.document().blocks().stream()
      .filter(b -> b.type() == type && b.text().startsWith(startsWith)).findFirst().orElseThrow();
  }

  @Test void mapsBlockTypes() {
    Conversion c = convert(JatsOptions.defaults());
    List<Block> blocks = c.document().blocks();
    assertThat(c.document().id()).isEqualTo("doc1");
    assertThat(c.document().language()).isEqualTo("en");
    assertThat(c.document().meta()).containsEntry("doi", "10.3897/zookeys.1297.218447");
    assertThat(blocks.get(0).type()).isEqualTo(BlockType.TITLE);
    assertThat(blocks.get(0).text()).startsWith("Ophelia yangi sp. nov. (Annelida, Opheliidae)");
    assertThat(blocks.stream().map(Block::id).toList()).startsWith("b1", "b2", "b3");
    assertThat(blocks.stream().filter(b -> b.type() == BlockType.ABSTRACT)).hasSize(1);
    assertThat(blocks.stream().filter(b -> b.type() == BlockType.CAPTION).map(Block::text))
      .anyMatch(t -> t.startsWith("Ophelia yangi sp. nov., holotype"))
      .anyMatch(t -> t.startsWith("Descriptions of Ophelia limacina"));
    assertThat(blocks.stream().filter(b -> b.type() == BlockType.TABLE_CELL).map(Block::text))
      .contains("Character", "Rathke (1843)");
    assertThat(blocks.stream().filter(b -> b.type() == BlockType.REFERENCE)).hasSize(2);
    Block heading = first(c, BlockType.HEADING, "Introduction");
    assertThat(heading.level()).isEqualTo(1);
    Block para = first(c, BlockType.PARAGRAPH, "In Chinese waters");
    assertThat(para.sectionPath()).containsExactly("Introduction");
    assertThat(blocks).allSatisfy(b -> assertThat(b.text()).isNotBlank().doesNotContain("  ").doesNotStartWith(" ").doesNotEndWith(" "));
  }

  @Test void italicAndTaxonSpansPointAtNames() {
    Conversion c = convert(JatsOptions.defaults());
    Block p = first(c, BlockType.PARAGRAPH, "Ophelia Savigny, 1822");
    assertThat(p.text()).startsWith("Ophelia Savigny, 1822 (Opheliidae Malmgren, 1867)");
    assertThat(spans(p, Style.ITALIC)).containsExactly("Ophelia");
    assertThat(spans(p, Style.TAXON_NAME)).containsExactly("Ophelia", "Opheliidae");
    List<TaxonMarkup> marks = c.taxonMarkup().stream().filter(m -> m.blockId().equals(p.id())).toList();
    assertThat(marks).hasSize(2);
    assertThat(marks.get(0).parts()).containsExactly(new NamePart("genus", "Ophelia", "Ophelia"));
    assertThat(marks.get(1).parts()).containsExactly(new NamePart("family", "Opheliidae", "Opheliidae"));
    assertThat(slice(p, marks.get(1).start(), marks.get(1).end())).isEqualTo("Opheliidae");
  }

  @Test void abbreviatedTaxonMarkupKeepsReg() {
    Conversion c = convert(JatsOptions.defaults());
    Block p = first(c, BlockType.PARAGRAPH, "In Chinese waters");
    TaxonMarkup m = c.taxonMarkup().stream()
      .filter(t -> t.blockId().equals(p.id()) && slice(p, t.start(), t.end()).equals("O. limacina")).findFirst().orElseThrow();
    assertThat(m.parts()).containsExactly(new NamePart("genus", "Ophelia", "O."), new NamePart("species", "limacina", "limacina"));
  }

  @Test void treatmentHeadingAndStatus() {
    Conversion c = convert(JatsOptions.defaults());
    Block h = first(c, BlockType.HEADING, "Ophelia yangi");
    assertThat(h.text()).isEqualTo("Ophelia yangi sp. nov.");
    // section Introduction (1) > taxon-treatment (2) > nomenclature (3)
    assertThat(h.level()).isEqualTo(3);
    assertThat(h.sectionPath()).containsExactly("Introduction");
    assertThat(first(c, BlockType.HEADING, "Material examined").level()).isEqualTo(3);
    assertThat(c.statusMarkup()).hasSize(1);
    StatusMarkup s = c.statusMarkup().get(0);
    assertThat(s.blockId()).isEqualTo(h.id());
    assertThat(s.status()).isEqualTo("sp. nov.");
    assertThat(slice(h, s.start(), s.end())).isEqualTo("sp. nov.");
    TaxonMarkup t = c.taxonMarkup().get(s.taxonMarkupIndex());
    assertThat(t.blockId()).isEqualTo(h.id());
    assertThat(slice(h, t.start(), t.end())).isEqualTo("Ophelia yangi");
    assertThat(t.parts()).extracting(NamePart::reg).containsExactly("Ophelia", "yangi");
  }

  @Test void withoutTaxonMarkupNoTaxonSpans() {
    Conversion c = convert(new JatsOptions(false, true));
    assertThat(c.document().blocks()).allSatisfy(b -> assertThat(spans(b, Style.TAXON_NAME)).isEmpty());
    assertThat(c.document().blocks().stream().flatMap(b -> b.styles().stream())).anyMatch(s -> s.style() == Style.ITALIC);
    assertThat(c.taxonMarkup()).isNotEmpty();
    assertThat(c.statusMarkup()).hasSize(1);
  }

  @Test void referencesCanBeExcluded() {
    Conversion c = convert(new JatsOptions(true, false));
    assertThat(c.document().blocks()).noneMatch(b -> b.type() == BlockType.REFERENCE);
  }

  @Test void spansStayAlignedAfterLigatureAndNbsp() {
    // ligature fi expands to two chars, soft hyphen vanishes, NBSP collapses: the later spans must still slice correctly
    Conversion c = convertXml("<article><body><p>ﬁnd\u00A0the\u00AD\u00A0 <italic>Ophelia</italic> and ﬃx <bold>Ab\u00ADc</bold></p></body></article>");
    Block b = c.document().blocks().get(0);
    assertThat(b.text()).isEqualTo("find the Ophelia and ffix Abc");
    assertThat(spans(b, Style.ITALIC)).containsExactly("Ophelia");
    assertThat(spans(b, Style.BOLD)).containsExactly("Abc");
  }

  @Test void spansUseCodePointsNotChars() {
    Conversion c = convertXml("<article><body><p>🐛 <italic>Ophelia</italic></p></body></article>");
    Block b = c.document().blocks().get(0);
    StyleSpan s = b.styles().get(0);
    assertThat(s.start()).isEqualTo(2);
    assertThat(slice(b, s.start(), s.end())).isEqualTo("Ophelia");
  }

  @Test void unknownEntityBecomesSpace() {
    Conversion c = convertXml("<article><body><p>a&nbsp;b&amp;c</p></body></article>");
    assertThat(c.document().blocks().get(0).text()).isEqualTo("a b&c");
  }

  @Test void doesNotResolveExternalDtd() {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE article SYSTEM \"http://unresolvable.invalid/x.dtd\">"
      + "<article><body><p>Hello <italic>world</italic></p></body></article>";
    Conversion c = convertXml(xml);
    assertThat(c.document().blocks().get(0).text()).isEqualTo("Hello world");
  }

  @Test void markupStaysAlignedAfterLigatureAndNbsp() {
    Conversion c = convertXml("<article xmlns:tp=\"http://www.plazi.org/taxpub\"><body><sec><title>S</title><tp:taxon-treatment><tp:nomenclature>"
      + "\uFB01nd\u00A0\u00A0the\u00AD <tp:taxon-name><tp:taxon-name-part taxon-name-part-type=\"genus\" reg=\"Ophelia\">Ophe\u00ADlia</tp:taxon-name-part></tp:taxon-name>"
      + " \uFB03x <tp:taxon-status>sp.\u00A0nov.</tp:taxon-status></tp:nomenclature></tp:taxon-treatment></sec></body></article>");
    Block h = first(c, BlockType.HEADING, "find");
    assertThat(h.text()).isEqualTo("find the Ophelia ffix sp. nov.");
    assertThat(c.taxonMarkup()).hasSize(1);
    TaxonMarkup t = c.taxonMarkup().get(0);
    assertThat(slice(h, t.start(), t.end())).isEqualTo("Ophelia");
    assertThat(spans(h, Style.TAXON_NAME)).containsExactly("Ophelia");
    assertThat(c.statusMarkup()).hasSize(1);
    StatusMarkup s = c.statusMarkup().get(0);
    assertThat(slice(h, s.start(), s.end())).isEqualTo("sp. nov.");
    assertThat(s.status()).isEqualTo("sp. nov.");
  }

  @Test void taxonMarkupCoversTextAfterTheLastNamePart() {
    // bdj.12.e132915: the closing parenthesis of the subgenus sits inside tp:taxon-name but outside every name part
    Conversion c = convertXml("<article xmlns:tp=\"http://www.plazi.org/taxpub\"><body><p>revision of the subgenus "
      + "<tp:taxon-name><tp:taxon-name-part taxon-name-part-type=\"genus\" reg=\"Crematogaster\">Crematogaster</tp:taxon-name-part> "
      + "(<tp:taxon-name-part taxon-name-part-type=\"subgenus\" reg=\"Oxygyne\">Oxygyne</tp:taxon-name-part>)</tp:taxon-name>"
      + " in Madagascar</p></body></article>");
    Block b = c.document().blocks().get(0);
    assertThat(b.text()).isEqualTo("revision of the subgenus Crematogaster (Oxygyne) in Madagascar");
    assertThat(c.taxonMarkup()).hasSize(1);
    TaxonMarkup t = c.taxonMarkup().get(0);
    assertThat(slice(b, t.start(), t.end())).isEqualTo("Crematogaster (Oxygyne)");
    assertThat(spans(b, Style.TAXON_NAME)).containsExactly("Crematogaster (Oxygyne)");
  }

  @Test void taxonMarkupCoversTextBeforeTheFirstNamePart() {
    Conversion c = convertXml("<article xmlns:tp=\"http://www.plazi.org/taxpub\"><body><p>the subgenus "
      + "<tp:taxon-name>(<tp:taxon-name-part taxon-name-part-type=\"subgenus\" reg=\"Oxygyne\">Oxygyne</tp:taxon-name-part>)</tp:taxon-name>"
      + " is</p></body></article>");
    Block b = c.document().blocks().get(0);
    TaxonMarkup t = c.taxonMarkup().get(0);
    assertThat(slice(b, t.start(), t.end())).isEqualTo("(Oxygyne)");
  }

  @Test void taxonMarkupExcludesWhitespaceAndSkippedIdsInsideTheName() {
    // mycokeys.128.172807: a nomenclature name wraps object ids and line breaks around its parts
    Conversion c = convertXml("<article xmlns:tp=\"http://www.plazi.org/taxpub\"><body><sec><title>S</title><tp:taxon-treatment>"
      + "<tp:nomenclature>\n  <tp:taxon-name><object-id content-type=\"arpha\">F6CD5733-1CFE-549D-B234-739D39B89D55</object-id>\n\t\t"
      + "<tp:taxon-name-part taxon-name-part-type=\"genus\" reg=\"Hyphodermella\">Hyphodermella</tp:taxon-name-part> "
      + "<tp:taxon-name-part taxon-name-part-type=\"species\" reg=\"ryvardenii\">ryvardenii</tp:taxon-name-part>\n\t\n\t\t"
      + "<object-id content-type=\"mycobank\">MB860665</object-id>\n\t</tp:taxon-name>\n"
      + "  <tp:taxon-status>sp. nov</tp:taxon-status></tp:nomenclature></tp:taxon-treatment></sec></body></article>");
    Block h = first(c, BlockType.HEADING, "Hyphodermella");
    assertThat(h.text()).isEqualTo("Hyphodermella ryvardenii sp. nov");
    TaxonMarkup t = c.taxonMarkup().get(0);
    assertThat(slice(h, t.start(), t.end())).isEqualTo("Hyphodermella ryvardenii");
    assertThat(spans(h, Style.TAXON_NAME)).containsExactly("Hyphodermella ryvardenii");
  }

  @Test void byteOrderMarkAndZeroWidthSpaceLeaveNoTraceAndSpansStayAligned() {
    // Pensoft article titles start with U+FEFF; a zero width space inside a name part must not shift later offsets either
    Conversion c = convertXml("<article xmlns:tp=\"http://www.plazi.org/taxpub\"><front><article-meta><title-group><article-title>"
      + "\uFEFFNomenclature of two <italic><tp:taxon-name><tp:taxon-name-part taxon-name-part-type=\"genus\" reg=\"Cocconeis\">"
      + "Coc\u200Bconeis</tp:taxon-name-part></tp:taxon-name></italic> species from <italic>Lake\u200B</italic> Baikal"
      + "</article-title></title-group></article-meta></front></article>");
    Block t = c.document().blocks().get(0);
    assertThat(t.type()).isEqualTo(BlockType.TITLE);
    assertThat(t.text()).isEqualTo("Nomenclature of two Cocconeis species from Lake Baikal");
    assertThat(spans(t, Style.ITALIC)).containsExactly("Cocconeis", "Lake");
    assertThat(spans(t, Style.TAXON_NAME)).containsExactly("Cocconeis");
    TaxonMarkup m = c.taxonMarkup().get(0);
    assertThat(slice(t, m.start(), m.end())).isEqualTo("Cocconeis");
    assertThat(m.parts()).containsExactly(new NamePart("genus", "Cocconeis", "Cocconeis"));
  }

  @Test void entityLookalikeInCdataSurvives() {
    Conversion c = convertXml("<article><body><p>a <![CDATA[x &foo; y]]> b &nbsp; c</p></body></article>");
    assertThat(c.document().blocks().get(0).text()).isEqualTo("a x &foo; y b c");
  }

  @Test void utf16InputIsRejected() {
    byte[] be = "\uFEFF<article><body><p>x</p></body></article>".getBytes(StandardCharsets.UTF_16BE);
    byte[] le = "\uFEFF<article><body><p>x</p></body></article>".getBytes(StandardCharsets.UTF_16LE);
    assertThatThrownBy(() -> new JatsConverter().convert(new ByteArrayInputStream(be), "d", JatsOptions.defaults()))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-16");
    assertThatThrownBy(() -> new JatsConverter().convert(new ByteArrayInputStream(le), "d", JatsOptions.defaults()))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("UTF-16");
  }
}
