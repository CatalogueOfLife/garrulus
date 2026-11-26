package org.catalogueoflife.garrulus.indexer.content.route;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.garrulus.indexer.content.ContentRoute;
import org.catalogueoflife.garrulus.indexer.content.FetchedContent;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.work.Work;
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
  private static final String PENSOFT_HOST_SUFFIX = ".pensoft.net";
  private static final Pattern SLUG_AND_ID = Pattern.compile("([a-z0-9-]+)\\.e?(\\d+)");

  private final PoliteHttp http;
  private final String base;

  /** {@code baseTemplate} holds {@code {slug}}, for example {@code https://{slug}.pensoft.net}. */
  @Autowired
  public PensoftRoute(PoliteHttp http, @Value("${garrulus.pensoft.base-url:" + DEFAULT_BASE + "}") String baseTemplate) {
    this.http = http;
    this.base = baseTemplate.endsWith("/") ? baseTemplate.substring(0, baseTemplate.length() - 1) : baseTemplate;
  }

  /** The XML URL for a Pensoft DOI on the journal's own {@code *.pensoft.net} host. */
  public static String xmlUrl(String doi) {
    return xmlUrl(DEFAULT_BASE, doi);
  }

  static String xmlUrl(String baseTemplate, String doi) {
    Matcher m = parse(doi);
    if (m == null) throw new IllegalArgumentException("Not a Pensoft article DOI: " + doi);
    String url = baseTemplate.replace("{slug}", m.group(1)) + "/article/" + m.group(2) + "/download/xml/";
    // with the default template the host must stay a pensoft.net host; tests template the slug into a path instead
    String host = URI.create(url).getHost();
    if (baseTemplate.equals(DEFAULT_BASE) && (host == null || !host.endsWith(PENSOFT_HOST_SUFFIX))) {
      throw new IllegalArgumentException("Not a pensoft.net host for DOI " + doi);
    }
    return url;
  }

  /**
   * Splits a Pensoft DOI into the journal slug (group 1, lower case, {@code [a-z0-9-]+}) and the article id (group 2,
   * digits without the leading {@code e}). Anything else, such as a slug with {@code /}, {@code :} or {@code #} that
   * would change the request host, gives {@code null}.
   */
  private static @Nullable Matcher parse(@Nullable String doi) {
    if (doi == null) return null;
    String lower = doi.toLowerCase(Locale.ROOT);
    if (!lower.startsWith(PREFIX)) return null;
    String suffix = lower.substring(PREFIX.length());
    int first = suffix.indexOf('.');
    int last = suffix.lastIndexOf('.');
    if (first <= 0 || last >= suffix.length() - 1) return null;
    Matcher m = SLUG_AND_ID.matcher(suffix.substring(0, first) + "." + suffix.substring(last + 1));
    return m.matches() ? m : null;
  }

  @Override
  public String route() {
    return ROUTE;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return parse(w.doi()) != null;
  }

  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    if (!applies(w, s)) return Optional.empty();
    URI uri = URI.create(xmlUrl(base, w.doi().toLowerCase(Locale.ROOT)));
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/xml, text/xml"));
    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) return Optional.empty();
    return JatsSupport.parse(body.get(), "work:" + w.id(), uri).map(p -> JatsSupport.content(ROUTE, "TAXPUB",
        body.get(), p, p.license() != null ? p.license() : "cc-by", uri));
  }
}
