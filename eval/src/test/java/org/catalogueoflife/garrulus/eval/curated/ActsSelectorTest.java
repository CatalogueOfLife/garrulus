package org.catalogueoflife.garrulus.eval.curated;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActsSelectorTest {
  private static Block b(String id, BlockType t, String text) {
    return new Block(id, t, 0, text, List.of(), List.of());
  }

  private static String filler(int n) {
    return "x".repeat(n);
  }

  @Test
  void excerptKeepsTitleAbstractAndTheBodyRunWithTheMostActs() {
    List<Block> blocks = new ArrayList<>();
    blocks.add(b("t", BlockType.TITLE, "A revision of Aus"));
    blocks.add(b("f", BlockType.FOOTNOTE, "University of Somewhere"));
    blocks.add(b("a", BlockType.ABSTRACT, "Aus bus sp. nov. is described."));
    blocks.add(b("p1", BlockType.PARAGRAPH, "Introduction " + filler(3000)));
    blocks.add(b("p2", BlockType.PARAGRAPH, "Aus cus sp. nov. and Aus dus comb. nov."));
    blocks.add(b("h1", BlockType.HEADING, "Aus eus syn. nov."));
    blocks.add(b("p3", BlockType.PARAGRAPH, filler(8000)));
    blocks.add(b("p4", BlockType.PARAGRAPH, "Aus fus stat. rev."));
    blocks.add(b("r1", BlockType.REFERENCE, "Smith 1900. Aus gus sp. nov."));
    Document d = ActsSelector.excerpt(new Document("w", "en", blocks, Map.of("x", "y")), "doi:10.1/x");

    assertThat(d.id()).isEqualTo("doi:10.1/x");
    assertThat(d.meta()).containsEntry("stratum", "ACTS").containsEntry("x", "y");
    // p2 + h1 hold 3 markers; p3 does not fit after them, so p4 cannot join; p1 has no marker and is no heading
    assertThat(d.blocks()).extracting(Block::id).containsExactly("t", "a", "p2", "h1");
  }

  @Test
  void aDocumentWithoutActsInItsBodyFails() {
    Document d = new Document("w", "en", List.of(b("t", BlockType.TITLE, "Aus bus sp. nov."),
        b("p", BlockType.PARAGRAPH, "Nothing new here.")), Map.of());
    assertThatThrownBy(() -> ActsSelector.excerpt(d, "doi:10.1/x")).hasMessageContaining("doi:10.1/x").hasMessageContaining("no act marker");
  }

  @Test
  void splitTakesTheFirstOfEachKindForDev() {
    Document d = new Document("w", "en", List.of(b("t", BlockType.TITLE, "x")), Map.of());
    CuratedSelector.Pick p = new CuratedSelector.Pick("p", ActsSelector.ACTS, "", "en", "CC-BY", true, "", "", "h", d);
    List<CuratedSelector.Pick> out = ActsSelector.split(List.of(p, p, p), List.of(p, p), 2);
    assertThat(out).extracting(CuratedSelector.Pick::split).containsExactly("dev", "dev", "test", "dev", "dev");
  }
}
