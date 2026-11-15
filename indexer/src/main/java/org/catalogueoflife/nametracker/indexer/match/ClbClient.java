package org.catalogueoflife.nametracker.indexer.match;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import tools.jackson.databind.JsonNode;

/**
 * The ChecklistBank API: the current COL eXtended Release (dataset 3LXR) and name usage matching in a given release
 * ({@code mode=RELAXED}). A CLB that is down (retryable failure, or its name does not resolve) is a
 * {@link ServiceOutageException} due after the harvest outage delay; any other failure is an {@link HttpProblem}.
 * A match call that fails (retryably after the HTTP retries, or with a 4xx) is checked with a probe of the same
 * endpoint and release for a name that always matches ({@link #PROBE_NAME}, rank kingdom). Varnish sends the match
 * endpoint to another CLB server than {@code /dataset/3LXR}, so only the endpoint itself tells. The probe answers: the
 * name is at fault ({@link UnmatchableNameException}). The probe fails retryably: CLB is down
 * ({@link ServiceOutageException}). The probe gets a 4xx or any other error: the release or the API is broken, an
 * ordinary failure ({@link HttpProblem}) that must not place names NONE.
 */
public class ClbClient {

  static final String SERVICE = "CLB";
  static final String XR_DATASET = "3LXR";
  static final String PROBE_NAME = "Animalia";

  public record XrInfo(int key, String alias) {}

  private final PoliteHttp http;
  private final String base;
  private final HarvestOutage outage;

  public ClbClient(PoliteHttp http, String baseUrl, HarvestOutage outage) {
    this.http = http;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.outage = outage;
  }

  public XrInfo currentXr() {
    JsonNode d = getJson(URI.create(base + "/dataset/" + XR_DATASET));
    JsonNode key = d.path("key");
    if (!key.isNumber()) {
      throw new HttpProblem(URI.create(base + "/dataset/" + XR_DATASET), 200, false,
          "CLB dataset " + XR_DATASET + " has no key", null);
    }
    JsonNode alias = d.path("alias");
    return new XrInfo(key.asInt(), alias.isString() ? alias.asString() : "XR " + key.asInt());
  }

  public ClbMatch match(int releaseKey, MatchQuery q) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("scientificName", q.name());
    if (q.rank() != null) p.put("rank", q.rank().toLowerCase(Locale.ROOT));
    if (q.authorship() != null) p.put("authorship", q.authorship());
    p.putAll(q.hints());
    p.put("mode", "RELAXED");
    JsonNode json;
    try {
      json = getJson(matchUri(releaseKey, p));
    } catch (ServiceOutageException e) {
      probe(releaseKey, e);
      throw new UnmatchableNameException(q.name(), e.getCause() == null ? e : e.getCause());
    } catch (HttpProblem e) {
      if (e.status() < 400 || e.status() >= 500) throw e;
      probe(releaseKey, e);
      throw new UnmatchableNameException(q.name(), e);
    }
    return ClbMatch.parse(json);
  }

  private URI matchUri(int releaseKey, Map<String, String> params) {
    StringBuilder sb = new StringBuilder(base).append("/dataset/").append(releaseKey).append("/match/nameusage");
    char sep = '?';
    for (Map.Entry<String, String> e : params.entrySet()) {
      sb.append(sep).append(e.getKey()).append('=').append(enc(e.getValue()));
      sep = '&';
    }
    return URI.create(sb.toString());
  }

  /**
   * After a failed match call, asks the same endpoint and release for {@link #PROBE_NAME}. Returns if it answers (the
   * name was at fault). Throws the original outage if the probe fails retryably (CLB is down), else an HttpProblem
   * (release or API broken) that fails the task.
   */
  private void probe(int releaseKey, RuntimeException failure) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("scientificName", PROBE_NAME);
    p.put("rank", "kingdom");
    p.put("mode", "RELAXED");
    URI uri = matchUri(releaseKey, p);
    try {
      getJson(uri);
    } catch (ServiceOutageException down) {
      throw failure instanceof ServiceOutageException o ? o : down;
    } catch (HttpProblem broken) {
      HttpProblem e = new HttpProblem(uri, broken.status(), false, "CLB match endpoint of release " + releaseKey
          + " is broken: the probe for " + PROBE_NAME + " failed with " + broken.getMessage(), broken);
      e.addSuppressed(failure);
      throw e;
    }
  }

  private JsonNode getJson(URI uri) {
    HttpResponse<byte[]> resp;
    try {
      resp = http.get(uri, Map.of("Accept", "application/json"));
    } catch (HttpProblem e) {
      if (HarvestOutage.retryable(e) != null) throw new ServiceOutageException(SERVICE, outage.retryAt(), e);
      throw e;
    }
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      throw new HttpProblem(uri, status, false, "HTTP " + status + " from CLB: " + uri, null);
    }
    try {
      return FinderJson.mapper().readTree(resp.body());
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, status, false, "Unreadable CLB response: " + uri, e);
    }
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
