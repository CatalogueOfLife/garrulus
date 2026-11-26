package org.catalogueoflife.garrulus.finder.doc;

import org.catalogueoflife.garrulus.finder.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlatTextTest {
  private static Block block(String id, String text) {
    return new Block(id, BlockType.PARAGRAPH, 0, text, List.of(), List.of());
  }

  private static Document doc(String... texts) {
    List<Block> blocks = new java.util.ArrayList<>();
    for (int i = 0; i < texts.length; i++) blocks.add(block("b" + (i + 1), texts[i]));
    return new Document("d", null, blocks, Map.of());
  }

  @Test void flatOffsetsMapBackToBlocks() {
    Document d = doc("Ab 𝐴 cd", "Carabus", "x𝐴y");
    FlatText f = FlatText.of(d);
    assertThat(f.text()).isEqualTo("Ab 𝐴 cd\n\nCarabus\n\nx𝐴y");
    int flatLen = CodePoints.length(f.text());
    for (Block b : d.blocks()) {
      int len = CodePoints.length(b.text());
      for (int cp = 0; cp <= len; cp++) {
        int flat = f.toFlat(b.id(), cp);
        // the exclusive end of a block is only reachable through toFlat; reading back must give the same block and offset
        FlatText.Position p = f.toBlock(flat);
        assertThat(p).isEqualTo(new FlatText.Position(b.id(), cp));
        if (cp < len) {
          assertThat(CodePoints.slice(f.text(), flat, flat + 1)).isEqualTo(CodePoints.slice(b.text(), cp, cp + 1));
        }
      }
    }
    assertThat(flatLen).isEqualTo(7 + 2 + 7 + 2 + 3);
    assertThat(f.toBlock(0)).isEqualTo(new FlatText.Position("b1", 0));
    assertThat(f.toBlock(9)).isEqualTo(new FlatText.Position("b2", 0));
    // a name in the middle block
    assertThat(CodePoints.slice(f.text(), f.toFlat("b2", 0), f.toFlat("b2", 7))).isEqualTo("Carabus");
    // the separator is not a position inside any block
    assertThatThrownBy(() -> f.toBlock(8)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.toBlock(flatLen + 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.toFlat("zz", 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> f.toFlat("b2", 8)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void chunksSplitOnlyAtBlockBoundaries() {
    Document d = doc("aaaa", "bbbb", "cccccccccc", "dd", "ee");
    FlatText f = FlatText.of(d);
    List<FlatText> chunks = f.chunks(10);
    assertThat(chunks).extracting(FlatText::text).containsExactly("aaaa\n\nbbbb", "cccccccccc", "dd\n\nee");
    // chunks keep their own mapping
    FlatText second = chunks.get(1);
    assertThat(second.toBlock(3)).isEqualTo(new FlatText.Position("b3", 3));
    assertThat(chunks.get(2).toBlock(4)).isEqualTo(new FlatText.Position("b5", 0));
    assertThat(chunks.get(0).toFlat("b2", 1)).isEqualTo(7);
    assertThat(f.chunks(1000)).extracting(FlatText::text).containsExactly(f.text());
    assertThat(FlatText.of(doc()).chunks(10)).isEmpty();
  }
}
