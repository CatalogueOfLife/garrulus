package org.catalogueoflife.nametracker.indexer.content.route;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Locale;
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
 * Route {@code PENSOFT}: the TaxPub XML of a Pensoft article ({@code 10.3897/} DOIs) from the journal's own site.
 * {@code 10.3897/zookeys.1297.218447} is {@code https://zookeys.pensoft.net/article/218447/download/xml/};
 * {@code 10.3897/BDJ.14.e202849} is {@code https://bdj.pensoft.net/article/202849/download/xml/} (the leading
 * {@code e} of BDJ ids is not part of the article id). All Pensoft hosts share one PoliteHttp interval.
 */
@Component
@Order(1)
public class PensoftRoute implements ContentRoute {

  public static final String ROUTE = "PENSOFT";
  static final String PREFIX = "10.3897/";
  static final String DEFAULT_BASE = "https://{slug}.pensoft.net";

  private final PoliteHttp http;
  private final String base;

  /** {@code baseTemplate} holds {@code {slug}}, for example {@code https://{slug}.pensoft.net}. */
  @Autowired
  public PensoftRoute(PoliteHttp http, @Value("${nametracker.pensoft.base-url:" + DEFAULT_BASE + "}") String baseTemplate) {
    this.http = http;
    this.base = baseTemplate.endsWith("/") ? baseTemplate.substring(0, baseTemplate.length() - 1) : baseTemplate;
  }

  /** The XML URL for a Pensoft DOI. */
  public static String xmlUrl(String doi) {
    return xmlUrl(DEFAULT_BASE, doi);
  }

  static String xmlUrl(String baseTemplate, String doi) {
    String suffix = doi.substring(PREFIX.length());
    String slug = suffix.substring(0, suffix.indexOf('.')).toLowerCase(Locale.ROOT);
    String last = suffix.substring(suffix.lastIndexOf('.') + 1);
    String id = last.replaceFirst("^[eE]", "");
    return baseTemplate.replace("{slug}", slug) + "/article/" + id + "/download/xml/";
  }

  @Override
  public String route() {
    return ROUTE;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    String doi = w.doi();
    return doi != null && doi.toLowerCase(Locale.ROOT).startsWith(PREFIX) && doi.indexOf('.', PREFIX.length()) > 0
        && doi.indexOf('.', PREFIX.length()) < doi.length() - 1;
  }

  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    URI uri = URI.create(xmlUrl(base, w.doi().toLowerCase(Locale.ROOT)));
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/xml, text/xml"));
    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) return Optional.empty();
    return JatsSupport.parse(body.get(), "work:" + w.id(), uri).map(p -> JatsSupport.content(ROUTE, "TAXPUB",
        body.get(), p, p.license() != null ? p.license() : "cc-by", uri));
  }
}
