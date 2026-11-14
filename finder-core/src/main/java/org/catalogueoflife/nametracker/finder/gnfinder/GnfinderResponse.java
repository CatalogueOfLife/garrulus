package org.catalogueoflife.nametracker.finder.gnfinder;

import java.util.List;
import java.util.Map;

/** names is never null (gnfinder sends null when nothing is found). metadata is gnfinder's own, kept as it came. */
public record GnfinderResponse(Map<String, Object> metadata, List<GnName> names) {
  public GnfinderResponse {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    names = names == null ? List.of() : List.copyOf(names);
  }
}
