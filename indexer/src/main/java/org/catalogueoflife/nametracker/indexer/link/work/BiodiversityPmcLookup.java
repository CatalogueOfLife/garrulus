package org.catalogueoflife.nametracker.indexer.link.work;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkHttp;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Whether Biodiversity PMC (SIBiLS) has the work: {@code /api/fetch?ids=…&col=pmc} for the PMCIDs, then
 * {@code col=medline} for the PMIDs of the works that stayed without. The value is {@code pmc/PMC<n>} (preferred) or
 * {@code medline/<n>}. A full text answer is large (about 1 MB a document), so a batch is small.
 */
@Component
public class BiodiversityPmcLookup implements IdentifierLookup {

  public static final String NAME = "biodiversitypmc";
  private static final Logger LOG = LoggerFactory.getLogger(BiodiversityPmcLookup.class);

  private final PoliteHttp http;
  private final LinkProperties props;
  private final String base;

  public BiodiversityPmcLookup(PoliteHttp http, LinkProperties props,
      @Value("${nametracker.links.biodiversitypmc.base-url:https://biodiversitypmc.sibils.org}") String baseUrl) {
    this.http = http;
    this.props = props;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.BIODIVERSITY_PMC.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.WORK; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 40; }
  /** Fewer than the 50 a plain id list would allow: PMC documents come with their full text. */
  @Override public int batchSize() { return 10; }

  @Override
  public boolean applies(LinkTarget t) {
    return !t.has(IdScheme.BIODIVERSITY_PMC.code()) && (t.has(IdScheme.PMCID.code()) || t.has(IdScheme.PMID.code()));
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<Long, List<Identifier>> out = new HashMap<>();
    List<LinkTarget> withPmc = batch.stream().filter(t -> t.has(IdScheme.PMCID.code())).toList();
    Set<String> pmc = fetch("pmc", withPmc.stream().map(t -> t.first(IdScheme.PMCID.code())).toList());
    for (LinkTarget t : withPmc) {
      String id = t.first(IdScheme.PMCID.code()).toUpperCase(Locale.ROOT);
      if (pmc.contains(id)) out.put(t.id(), List.of(new Identifier(IdScheme.BIODIVERSITY_PMC.code(), "pmc/" + id)));
    }
    List<LinkTarget> withPmid = batch.stream().filter(t -> !out.containsKey(t.id()) && t.has(IdScheme.PMID.code()))
        .toList();
    Set<String> medline = fetch("medline", withPmid.stream().map(t -> t.first(IdScheme.PMID.code())).toList());
    for (LinkTarget t : withPmid) {
      String id = t.first(IdScheme.PMID.code());
      if (medline.contains(id)) out.put(t.id(), List.of(new Identifier(IdScheme.BIODIVERSITY_PMC.code(), "medline/" + id)));
    }
    return out;
  }

  /** The {@code _id}s of the documents the collection has among the ids. */
  private Set<String> fetch(String collection, List<String> ids) {
    Set<String> found = new HashSet<>();
    if (ids.isEmpty()) return found;
    URI uri = URI.create(base + "/api/fetch?ids=" + String.join(",", ids.stream().map(LinkHttp::enc).toList())
        + "&col=" + collection);
    JsonNode root = LinkHttp.json(http, uri, "application/json", "Biodiversity PMC");
    if (!root.path("success").asBoolean(true)) {
      LOG.warn("Biodiversity PMC {} answered an error: {}", collection, root.path("error").asString(""));
      return found;
    }
    for (JsonNode a : root.path("sibils_article_set")) {
      String id = a.path("_id").asString("").strip();
      if (id.isEmpty()) continue;
      String upper = id.toUpperCase(Locale.ROOT);
      found.add(!collection.equals("pmc") ? id : upper.startsWith("PMC") ? upper : "PMC" + id);
    }
    return found;
  }
}
