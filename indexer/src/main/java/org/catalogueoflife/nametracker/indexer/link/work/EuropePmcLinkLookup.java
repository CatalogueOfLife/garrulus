package org.catalogueoflife.nametracker.indexer.link.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.europepmc.EuropePmcSearchClient;
import org.catalogueoflife.nametracker.indexer.link.DoiBatching;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The PMID and PMCID of a work by its DOI: {@code DOI:"a" OR DOI:"b"} as a {@code lite} search of Europe PMC. */
@Component
public class EuropePmcLinkLookup implements IdentifierLookup {

  public static final String NAME = "europepmc";
  static final int PAGE = 100;

  private final EuropePmcSearchClient client;
  private final LinkProperties props;

  public EuropePmcLinkLookup(PoliteHttp http, LinkProperties props,
      @Value("${nametracker.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String baseUrl) {
    this.client = new EuropePmcSearchClient(http, baseUrl);
    this.props = props;
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.PMID.code(), IdScheme.PMCID.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.WORK; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 30; }
  @Override public int batchSize() { return 25; }

  @Override
  public boolean applies(LinkTarget t) {
    return t.doi() != null && !t.has(IdScheme.PMID.code()) && DoiBatching.quotable(t.doi());
  }

  /** The PMID and PMCID Europe PMC gives a DOI, merged over its rows (a PMC copy or preprint may carry only a PMCID). */
  private record Ids(String pmid, String pmcid) {
    Ids merge(Ids o) {
      return new Ids(pmid.isBlank() ? o.pmid : pmid, pmcid.isBlank() ? o.pmcid : pmcid);
    }
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<String, Ids> byDoi = new HashMap<>();
    for (List<JsonNode> rows : DoiBatching.each(batch.stream().map(LinkTarget::doi).distinct().toList(), this::search)) {
      for (JsonNode r : rows) {
        String doi = r.path("doi").asString("");
        Ids ids = new Ids(r.path("pmid").asString(""), r.path("pmcid").asString(""));
        if (!doi.isBlank() && !(ids.pmid().isBlank() && ids.pmcid().isBlank())) {
          byDoi.merge(DoiBatching.key(doi), ids, Ids::merge);
        }
      }
    }
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      Ids r = byDoi.get(DoiBatching.key(t.doi()));
      if (r == null) continue;
      List<Identifier> ids = new ArrayList<>();
      if (!r.pmid().isBlank()) ids.add(new Identifier(IdScheme.PMID.code(), r.pmid()));
      if (!r.pmcid().isBlank()) ids.add(new Identifier(IdScheme.PMCID.code(), r.pmcid()));
      out.put(t.id(), ids);
    }
    return out;
  }

  private List<JsonNode> search(List<String> dois) {
    String q = String.join(" OR ", dois.stream().map(d -> "DOI:\"" + d + "\"").toList());
    return client.lite(q, PAGE).results();
  }
}
