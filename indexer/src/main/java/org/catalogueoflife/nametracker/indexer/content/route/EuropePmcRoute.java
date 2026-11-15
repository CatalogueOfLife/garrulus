package org.catalogueoflife.nametracker.indexer.content.route;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.nametracker.indexer.content.ContentRoute;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Route {@code EUROPEPMC}: the JATS full text from Europe PMC ({@code /rest/<PMCID>/fullTextXML}). The PMCID is the
 * work's, or the one the shared {@link PmcidResolver} finds through the DOI search and stores on the work.
 */
@Component
@Order(2)
public class EuropePmcRoute implements ContentRoute {

  public static final String ROUTE = "EUROPEPMC";

  private final PoliteHttp http;
  private final PmcidResolver resolver;
  private final String base;

  @Autowired
  public EuropePmcRoute(PoliteHttp http, PmcidResolver resolver,
      @Value("${nametracker.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String baseUrl) {
    this.http = http;
    this.resolver = resolver;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public String route() {
    return ROUTE;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return (w.doi() != null && !w.doi().isBlank()) || PmcidResolver.normalise(w.pmcid()) != null;
  }

  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    Optional<String> pmcid = resolver.pmcid(w);
    if (pmcid.isEmpty()) return Optional.empty();
    URI uri = URI.create(base + "/rest/" + pmcid.get() + "/fullTextXML");
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/xml"));
    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) return Optional.empty();
    return JatsSupport.parse(body.get(), "work:" + w.id(), uri)
        .map(p -> JatsSupport.content(ROUTE, "JATS", body.get(), p, p.license(), uri));
  }
}
