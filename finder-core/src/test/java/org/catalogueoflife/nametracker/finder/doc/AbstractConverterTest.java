package org.catalogueoflife.nametracker.finder.doc;

import org.catalogueoflife.nametracker.finder.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractConverterTest {
  @Test void invertedIndexRestoresWordOrder() {
    Map<String, List<Integer>> idx = Map.of("The", List.of(0, 4), "genus", List.of(1), "Carabus", List.of(2), "is", List.of(3), "large", List.of(5));
    assertThat(AbstractConverter.fromInvertedIndex(idx)).isEqualTo("The genus Carabus is The large");
    assertThat(AbstractConverter.fromInvertedIndex(Map.of())).isEmpty();
    assertThat(AbstractConverter.fromInvertedIndex(null)).isEmpty();
  }

  @Test void crossrefJatsAbstractKeepsItalics() {
    String jats = "<jats:title>Abstract</jats:title><jats:p>The genus <jats:italic>Carabus</jats:italic> L. is large.</jats:p>"
      + "<jats:p>Second ﬁnd.</jats:p>";
    Document d = AbstractConverter.fromParts("d1", "A  title", "plain text ignored", jats);
    assertThat(d.id()).isEqualTo("d1");
    assertThat(d.blocks()).extracting(Block::type).containsExactly(BlockType.TITLE, BlockType.ABSTRACT, BlockType.ABSTRACT);
    assertThat(d.blocks()).extracting(Block::id).containsExactly("b1", "b2", "b3");
    assertThat(d.blocks().get(0).text()).isEqualTo("A title");
    Block p = d.blocks().get(1);
    assertThat(p.text()).isEqualTo("The genus Carabus L. is large.");
    assertThat(p.styles()).hasSize(1);
    StyleSpan s = p.styles().get(0);
    assertThat(s.style()).isEqualTo(Style.ITALIC);
    assertThat(CodePoints.slice(p.text(), s.start(), s.end())).isEqualTo("Carabus");
    assertThat(d.blocks().get(2).text()).isEqualTo("Second find.");
  }

  @Test void plainPartsAndMissingParts() {
    Document d = AbstractConverter.fromParts("d", "T", "Some abstract.", null);
    assertThat(d.blocks()).extracting(Block::type).containsExactly(BlockType.TITLE, BlockType.ABSTRACT);
    assertThat(AbstractConverter.fromParts("d", null, null, null).blocks()).isEmpty();
    assertThat(AbstractConverter.fromParts("d", " ", "", " ").blocks()).isEmpty();
    // unparseable JATS falls back to the plain text
    Document bad = AbstractConverter.fromParts("d", null, "fallback", "<jats:p>broken");
    assertThat(bad.blocks()).extracting(Block::text).containsExactly("fallback");
  }
}
