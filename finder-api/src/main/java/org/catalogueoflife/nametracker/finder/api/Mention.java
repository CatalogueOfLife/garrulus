package org.catalogueoflife.nametracker.finder.api;

import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/** A name occurrence. start/end are code-point offsets into the text of block blockId, end exclusive. */
public record Mention(String id, String blockId, int start, int end, String verbatim, String name, NameKind kind,
                      @Nullable Rank rank, @Nullable String authorship, @Nullable String qualifier,
                      double confidence, Evidence evidence, @Nullable String actId, @Nullable String contextId,
                      Map<String, String> hints) {
  public Mention {
    hints = Util.map(hints);
  }
}
