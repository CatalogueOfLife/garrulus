package org.catalogueoflife.nametracker.indexer.registry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.catalogueoflife.nametracker.store.registry.IssnLinkMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Which source is this journal: by ISSN-L (through every sibling ISSN), then by any ISSN, then by OpenAlex id. */
@Component
public class SourceIdentity {

  private final SourceMapper sources;
  private final IssnLinkMapper links;

  public SourceIdentity(SourceMapper sources, IssnLinkMapper links) {
    this.sources = sources;
    this.links = links;
  }

  /** {@code issns} are normalised. The lowest source id wins when several of one step match. */
  public @Nullable Source find(List<String> issns, @Nullable String openalexId) {
    Map<Long, Source> found = new TreeMap<>();
    for (String issn : issns) {
      String issnL = links.issnL(issn);
      if (issnL == null) continue;
      sources.findByIssnL(issnL).forEach(s -> found.put(s.id(), s));
      for (String sibling : links.siblings(issnL)) sources.findByIssn(sibling).forEach(s -> found.put(s.id(), s));
    }
    if (found.isEmpty()) {
      for (String issn : issns) sources.findByIssn(issn).forEach(s -> found.put(s.id(), s));
    }
    if (!found.isEmpty()) return found.values().iterator().next();
    return openalexId == null ? null : sources.findByOpenalexId(openalexId);
  }
}
