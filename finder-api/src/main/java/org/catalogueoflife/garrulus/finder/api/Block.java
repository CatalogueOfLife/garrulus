package org.catalogueoflife.garrulus.finder.api;

import java.util.List;

public record Block(String id, BlockType type, int level, String text, List<StyleSpan> styles, List<String> sectionPath) {
  public Block {
    styles = Util.list(styles);
    sectionPath = Util.list(sectionPath);
  }
}
