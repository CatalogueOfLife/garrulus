package org.catalogueoflife.nametracker.indexer.link.source;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkHttp;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Wikidata item of a journal by its ISSNs (P236 and P7363), with the identifiers it carries: IPNI publication
 * (P2008), BPH (P4569; P829 is the OEIS id), BHL bibliography (P4327) and DOAJ (P5115). One SPARQL request for a batch
 * of journals; the ISSNs in {@code VALUES} are only {@link Issn#normalise normalised} ones, never free text. An item
 * that matches two journals of the batch, or a journal that matches two items, is used for none (WARN).
 */
@Component
public class WikidataSourceLookup implements IdentifierLookup {

  public static final String NAME = "wikidata";
  private static final Logger LOG = LoggerFactory.getLogger(WikidataSourceLookup.class);
  private static final String ENTITY = "http://www.wikidata.org/entity/";

  private final PoliteHttp http;
  private final LinkProperties props;
  private final String endpoint;

  public WikidataSourceLookup(PoliteHttp http, LinkProperties props,
      @Value("${nametracker.links.wikidata.base-url:https://query.wikidata.org/sparql}") String endpoint) {
    this.http = http;
    this.props = props;
    this.endpoint = LinkHttp.trimSlash(endpoint);
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() {
    return List.of(IdScheme.WIKIDATA.code(), IdScheme.IPNI_PUB.code(), IdScheme.BPH.code(), IdScheme.BHL_TITLE.code(),
        IdScheme.DOAJ.code());
  }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.SOURCE; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 20; }
  @Override public int batchSize() { return 50; }

  @Override
  public boolean applies(LinkTarget t) {
    return !t.has(IdScheme.WIKIDATA.code()) && !issns(t).isEmpty();
  }

  private static List<String> issns(LinkTarget t) {
    return t.issns().stream().map(Issn::normalise).filter(Objects::nonNull).distinct().toList();
  }

  /** What one item says: its identifiers by scheme. */
  private record Item(String qid, Map<String, String> ids) {
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<String, List<LinkTarget>> byIssn = new LinkedHashMap<>();
    for (LinkTarget t : batch) for (String i : issns(t)) byIssn.computeIfAbsent(i, k -> new ArrayList<>()).add(t);
    if (byIssn.isEmpty()) return Map.of();
    StringBuilder values = new StringBuilder();
    for (String i : byIssn.keySet()) values.append('"').append(i).append("\" ");
    String sparql = "SELECT ?issn ?item ?ipni ?bph ?bhl ?doaj WHERE { VALUES ?issn { " + values
        + "} ?item wdt:P236|wdt:P7363 ?issn . OPTIONAL { ?item wdt:P2008 ?ipni } OPTIONAL { ?item wdt:P4569 ?bph } "
        + "OPTIONAL { ?item wdt:P4327 ?bhl } OPTIONAL { ?item wdt:P5115 ?doaj } }";
    JsonNode root = LinkHttp.json(http, URI.create(endpoint + "?query=" + LinkHttp.enc(sparql)),
        "application/sparql-results+json", "Wikidata");

    // item -> its identifiers (first value per scheme, in answer order); target -> the items its ISSNs matched
    Map<String, Map<String, SortedSet<String>>> items = new LinkedHashMap<>();
    Map<Long, Set<String>> itemsOf = new HashMap<>();
    Map<String, Set<Long>> targetsOf = new HashMap<>();
    for (JsonNode b : root.path("results").path("bindings")) {
      String issn = Issn.normalise(b.path("issn").path("value").asString(""));
      String item = b.path("item").path("value").asString("");
      if (issn == null || !item.startsWith(ENTITY) || !byIssn.containsKey(issn)) continue;
      String qid = item.substring(ENTITY.length());
      Map<String, SortedSet<String>> ids = items.computeIfAbsent(qid, k -> new LinkedHashMap<>());
      put(ids, IdScheme.IPNI_PUB, b.path("ipni"));
      put(ids, IdScheme.BPH, b.path("bph"));
      put(ids, IdScheme.BHL_TITLE, b.path("bhl"));
      put(ids, IdScheme.DOAJ, b.path("doaj"));
      for (LinkTarget t : byIssn.get(issn)) {
        itemsOf.computeIfAbsent(t.id(), k -> new LinkedHashSet<>()).add(qid);
        targetsOf.computeIfAbsent(qid, k -> new LinkedHashSet<>()).add(t.id());
      }
    }
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (Map.Entry<Long, Set<String>> e : itemsOf.entrySet()) {
      if (e.getValue().size() > 1) {
        LOG.warn("Source {} matches several Wikidata items {}: none used", e.getKey(), new TreeSet<>(e.getValue()));
        continue;
      }
      String qid = e.getValue().iterator().next();
      if (targetsOf.get(qid).size() > 1) {
        LOG.warn("Wikidata item {} matches several sources {}: used for none", qid, new TreeSet<>(targetsOf.get(qid)));
        continue;
      }
      List<Identifier> ids = new ArrayList<>();
      ids.add(new Identifier(IdScheme.WIKIDATA.code(), qid));
      // several values of one property: the smallest, so a repeat chooses the same
      items.get(qid).forEach((scheme, v) -> ids.add(new Identifier(scheme, v.first())));
      out.put(e.getKey(), ids);
    }
    return out;
  }

  /** Collects the values the scheme accepts (a value of another form, such as a non-numeric BPH, is left out). */
  private static void put(Map<String, SortedSet<String>> ids, IdScheme scheme, JsonNode value) {
    String v = value.path("value").asString("");
    if (v.isBlank()) return;
    String n = scheme.normalise(v);
    if (n != null) ids.computeIfAbsent(scheme.code(), k -> new TreeSet<>()).add(n);
  }
}
