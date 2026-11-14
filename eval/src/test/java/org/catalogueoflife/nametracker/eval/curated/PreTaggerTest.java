package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.ItalicsFinder;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreTaggerTest {
  @Test
  void unionDeduplicatesBySpan() {
    Document d = new Document("d", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, "We saw Carabus auratus here.",
        List.of(new StyleSpan(7, 22, Style.ITALIC)), List.of())), Map.of());
    List<Mention> one = PreTagger.pretag(d, List.of(new ItalicsFinder()));
    assertThat(one).hasSize(1);
    List<Mention> two = PreTagger.pretag(d, List.of(new ItalicsFinder(), new ItalicsFinder()));
    assertThat(two).hasSize(1);
    assertThat(PreTagger.pretag(d, List.of())).isEmpty();
  }
}
