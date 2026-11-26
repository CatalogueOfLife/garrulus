package org.catalogueoflife.garrulus.indexer.link.source;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.link.IdentifierLookup;
import org.catalogueoflife.garrulus.indexer.link.LinkHttp;
import org.catalogueoflife.garrulus.indexer.link.LinkProperties;
import org.catalogueoflife.garrulus.indexer.link.LinkTarget;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.ident.Issn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The IPNI publication of a journal by ISSN (after Wikidata, which may have given it already): {@code /api/1/search?q=
 * issn:<issn>}. The search is fuzzy ({@code issn:1179-3155} also returns {@code 0005-3155}), so only a result of
 * record type {@code publication} whose own ISSN equals the asked one counts. Its BPH number is kept when all digits.
 */
@Component
public class IpniSourceLookup implements IdentifierLookup {

  public static final String NAME = "ipni";
  private static final Pattern DIGITS = Pattern.compile("^\\d+$");

  private final PoliteHttp http;
  private final LinkProperties props;
  private final String base;

  public IpniSourceLookup(PoliteHttp http, LinkProperties props,
      @Value("${garrulus.links.ipni.base-url:https://www.ipni.org}") String baseUrl) {
    this.http = http;
    this.props = props;
    this.base = LinkHttp.trimSlash(baseUrl);
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.IPNI_PUB.code(), IdScheme.BPH.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.SOURCE; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 30; }

  @Override
  public boolean applies(LinkTarget t) {
    return !t.has(IdScheme.IPNI_PUB.code()) && t.issns().stream().anyMatch(i -> Issn.normalise(i) != null);
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      for (String issn : t.issns().stream().map(Issn::normalise).filter(Objects::nonNull).distinct().toList()) {
        JsonNode root = LinkHttp.json(http, URI.create(base + "/api/1/search?q=" + LinkHttp.enc("issn:" + issn)),
            "application/json", "IPNI");
        List<Identifier> ids = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
          if (!"publication".equals(r.path("recordType").asString(""))
              || !issn.equals(Issn.normalise(r.path("issn").asString("")))) continue;
          String id = r.path("id").asString("");
          if (id.isBlank()) continue;
          ids.add(new Identifier(IdScheme.IPNI_PUB.code(), id));
          String bph = r.path("bphNumber").asString("").strip();
          if (DIGITS.matcher(bph).matches()) ids.add(new Identifier(IdScheme.BPH.code(), bph));
          break;
        }
        if (!ids.isEmpty()) {
          out.put(t.id(), ids);
          break;
        }
      }
    }
    return out;
  }
}
