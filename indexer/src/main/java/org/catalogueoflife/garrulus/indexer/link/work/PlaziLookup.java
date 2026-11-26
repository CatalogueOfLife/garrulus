package org.catalogueoflife.garrulus.indexer.link.work;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.link.DoiBatching;
import org.catalogueoflife.garrulus.indexer.link.IdentifierLookup;
import org.catalogueoflife.garrulus.indexer.link.LinkHttp;
import org.catalogueoflife.garrulus.indexer.link.LinkProperties;
import org.catalogueoflife.garrulus.indexer.link.LinkTarget;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Plazi article of a work by its DOI: {@code srsStats/stats} of TreatmentBank filtered on {@code pubLnk.articleDoi},
 * the {@code DocArticleUuid} of the row of exactly that DOI. Same gate as {@link ZenodoBlrLookup}.
 */
@Component
public class PlaziLookup implements IdentifierLookup {

  public static final String NAME = "plazi";

  private final PoliteHttp http;
  private final LinkProperties props;
  private final String base;

  public PlaziLookup(PoliteHttp http, LinkProperties props,
      @Value("${garrulus.links.plazi.base-url:https://tb.plazi.org/GgServer/srsStats/stats}") String baseUrl) {
    this.http = http;
    this.props = props;
    this.base = LinkHttp.trimSlash(baseUrl);
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.PLAZI.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.WORK; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 60; }

  @Override
  public boolean applies(LinkTarget t) {
    return t.doi() != null && !t.has(IdScheme.PLAZI.code()) && (t.hasActs() || t.plaziOrBlrJournal())
        && DoiBatching.quotable(t.doi());
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      URI uri = URI.create(base + "?outputFields=" + LinkHttp.enc("doc.articleUuid pubLnk.articleDoi")
          + "&groupingFields=" + LinkHttp.enc("doc.articleUuid pubLnk.articleDoi") + "&FP-pubLnk.articleDoi="
          + LinkHttp.enc("\"" + t.doi() + "\"") + "&format=JSON");
      JsonNode root = LinkHttp.json(http, uri, "application/json", "Plazi");
      for (JsonNode row : root.path("data")) {
        String uuid = row.path("DocArticleUuid").asString("");
        if (!uuid.isBlank() && DoiBatching.key(row.path("PubLnkArticleDoi").asString("")).equals(DoiBatching.key(t.doi()))) {
          out.put(t.id(), List.of(new Identifier(IdScheme.PLAZI.code(), uuid)));
          break;
        }
      }
    }
    return out;
  }
}
