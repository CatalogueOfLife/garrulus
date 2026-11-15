package org.catalogueoflife.nametracker.indexer.content.route;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.content.ContentRoute;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.content.LicencePolicy;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Route {@code PMC}: the article XML in the public PMC Open Access bucket ({@code pmc-oa-opendata}). Layout: one
 * {@code <PMCID>.<version>/} folder per version, holding {@code .xml}, {@code .json} (metadata, with
 * {@code license_code}), {@code .txt} and {@code .pdf}. The highest version is used; the licence is the metadata
 * {@code license_code}, else the XML's own.
 */
@Component
@Order(3)
public class PmcBucketRoute implements ContentRoute {

  public static final String ROUTE = "PMC";
  private static final Pattern VERSION = Pattern.compile("<Prefix>(PMC\\d+)\\.(\\d+)/</Prefix>");

  private final PoliteHttp http;
  private final PmcidResolver resolver;
  private final String base;

  @Autowired
  public PmcBucketRoute(PoliteHttp http, PmcidResolver resolver,
      @Value("${nametracker.pmc.base-url:https://pmc-oa-opendata.s3.amazonaws.com}") String baseUrl) {
    this.http = http;
    this.resolver = resolver;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public String route() {
    return ROUTE;
  }

  /** The PMCID may still have to be found through the DOI (the resolver remembers what Europe PMC already looked up). */
  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return (w.doi() != null && !w.doi().isBlank()) || PmcidResolver.normalise(w.pmcid()) != null;
  }

  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    Optional<String> pmcid = resolver.pmcid(w);
    if (pmcid.isEmpty()) return Optional.empty();
    int version = latestVersion(pmcid.get());
    if (version < 0) return Optional.empty();
    String key = pmcid.get() + "." + version;
    URI xml = URI.create(base + "/" + key + "/" + key + ".xml");
    Optional<byte[]> body = JatsSupport.okBody(http.get(xml, Map.of("Accept", "application/xml")));
    if (body.isEmpty()) return Optional.empty();
    return JatsSupport.parse(body.get(), "work:" + w.id(), xml).map(p -> {
      String license = metadataLicense(URI.create(base + "/" + key + "/" + key + ".json"));
      return JatsSupport.content(ROUTE, "JATS", body.get(), p, license != null ? license : p.license(), xml);
    });
  }

  /** The highest version folder, or -1 when the article is not in the bucket. */
  private int latestVersion(String pmcid) {
    URI uri = URI.create(base + "/?list-type=2&prefix=" + pmcid + ".&delimiter=/");
    HttpResponse<byte[]> resp = http.get(uri, Map.of());
    Optional<byte[]> body = JatsSupport.okBody(resp);
    if (body.isEmpty()) {
      if (resp.statusCode() >= 400 && resp.statusCode() != 404) {
        throw new HttpProblem(uri, resp.statusCode(), false, "HTTP " + resp.statusCode() + " listing " + uri, null);
      }
      return -1;
    }
    Matcher m = VERSION.matcher(new String(body.get(), StandardCharsets.UTF_8));
    int best = -1;
    while (m.find()) {
      if (m.group(1).equals(pmcid)) best = Math.max(best, Integer.parseInt(m.group(2)));
    }
    return best;
  }

  private @Nullable String metadataLicense(URI uri) {
    Optional<byte[]> body = JatsSupport.okBody(http.get(uri, Map.of("Accept", "application/json")));
    if (body.isEmpty()) return null;
    try {
      JsonNode code = FinderJson.mapper().readTree(body.get()).path("license_code");
      String v = code.asString("").strip();
      return v.isEmpty() ? null : LicencePolicy.normalise(v);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
