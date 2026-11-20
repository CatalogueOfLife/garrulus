package org.catalogueoflife.nametracker.indexer.link;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import tools.jackson.databind.JsonNode;

/** One JSON GET through {@link PoliteHttp} for the lookups: 5xx and 429 are retryable problems, any other non-2xx is not. */
public final class LinkHttp {

  private LinkHttp() {
  }

  public static JsonNode json(PoliteHttp http, URI uri, String accept, String service) {
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", accept));
    int status = resp.statusCode();
    URI safe = PoliteHttp.redact(uri);
    if (status < 200 || status >= 300) {
      throw new HttpProblem(safe, status, status >= 500 || status == 429, service + " answered " + status, null);
    }
    try {
      return FinderJson.mapper().readTree(resp.body());
    } catch (RuntimeException e) {
      throw new HttpProblem(safe, status, false, service + " answered unreadable JSON", e);
    }
  }

  public static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public static String trimSlash(String base) {
    return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }
}
