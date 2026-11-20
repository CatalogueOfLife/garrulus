package org.catalogueoflife.nametracker.indexer.link.work;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.link.DoiBatching;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The OpenAlex id of a work by its DOI; the calls go through {@link OpenAlexClient} and its budget. */
@Component
public class OpenAlexLinkLookup implements IdentifierLookup {

  public static final String NAME = "openalex";
  private static final String PREFIX = "https://openalex.org/";

  private final OpenAlexClient openAlex;
  private final LinkProperties props;

  public OpenAlexLinkLookup(OpenAlexClient openAlex, LinkProperties props) {
    this.openAlex = openAlex;
    this.props = props;
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.OPENALEX.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.WORK; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 20; }
  @Override public int batchSize() { return 50; }

  @Override
  public boolean applies(LinkTarget t) {
    return t.doi() != null && !t.has(IdScheme.OPENALEX.code());
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<String, String> byDoi = new HashMap<>();
    for (List<JsonNode> works : DoiBatching.each(batch.stream().map(LinkTarget::doi).distinct().toList(),
        openAlex::worksByDois)) {
      for (JsonNode w : works) {
        String doi = org.catalogueoflife.nametracker.store.work.WorkUpsert.normalizeDoi(w.path("doi").asString(""));
        String id = w.path("id").asString("");
        if (id.startsWith(PREFIX)) id = id.substring(PREFIX.length());
        if (doi != null && !id.isBlank()) byDoi.putIfAbsent(doi, id);
      }
    }
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      String id = byDoi.get(DoiBatching.key(t.doi()));
      if (id != null) out.put(t.id(), List.of(new Identifier(IdScheme.OPENALEX.code(), id)));
    }
    return out;
  }
}
