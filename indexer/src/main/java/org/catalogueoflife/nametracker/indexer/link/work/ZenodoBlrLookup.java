package org.catalogueoflife.nametracker.indexer.link.work;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.blr.ZenodoClient;
import org.catalogueoflife.nametracker.indexer.link.DoiBatching;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Biodiversity Literature Repository (Zenodo, community {@code biosyslit}) record of a work by its DOI. Only for
 * works that can have one: with acts, or of a journal Plazi or BLR already knows. A record whose DOI is not the work's
 * (the figures of the article are records of their own) is ignored.
 */
@Component
public class ZenodoBlrLookup implements IdentifierLookup {

  public static final String NAME = "zenodo-blr";

  private final ZenodoClient zenodo;
  private final LinkProperties props;

  public ZenodoBlrLookup(PoliteHttp http, LinkProperties props,
      @Value("${nametracker.zenodo.base-url:https://zenodo.org}") String baseUrl) {
    this.zenodo = new ZenodoClient(http, baseUrl);
    this.props = props;
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.BLR.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.WORK; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 50; }
  @Override public int batchSize() { return ZenodoClient.PAGE_SIZE; }

  @Override
  public boolean applies(LinkTarget t) {
    return t.doi() != null && !t.has(IdScheme.BLR.code()) && (t.hasActs() || t.plaziOrBlrJournal())
        && DoiBatching.quotable(t.doi());
  }

  /** The DOIs of one request and what Zenodo answered. */
  private record Asked(List<String> dois, ZenodoClient.DoiHits hits) {
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<String, String> byDoi = new HashMap<>();
    List<Asked> asked = DoiBatching.each(batch.stream().map(LinkTarget::doi).distinct().toList(),
        dois -> new Asked(dois, zenodo.byDois(dois)));
    for (Asked a : asked) collect(a.hits(), byDoi);
    for (Asked a : asked) {
      // a page cut short may leave out a target: ask each one it lacks alone before it counts as a miss
      if (!a.hits().truncated()) continue;
      for (String doi : a.dois()) {
        if (!byDoi.containsKey(DoiBatching.key(doi))) collect(zenodo.byDois(List.of(doi)), byDoi);
      }
    }
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      String id = byDoi.get(DoiBatching.key(t.doi()));
      if (id != null) out.put(t.id(), List.of(new Identifier(IdScheme.BLR.code(), id)));
    }
    return out;
  }

  private static void collect(ZenodoClient.DoiHits hits, Map<String, String> byDoi) {
    for (JsonNode r : hits.records()) {
      String doi = r.path("doi").asString("");
      String id = r.path("id").asString("");
      if (!doi.isBlank() && !id.isBlank()) byDoi.putIfAbsent(DoiBatching.key(doi), id);
    }
  }
}
