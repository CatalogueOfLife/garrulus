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
import org.jspecify.annotations.Nullable;
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
  private final String matchBase;
  private final String matchHost;
  private final HarvestOutage outage;

  public ClbClient(PoliteHttp http, String baseUrl, HarvestOutage outage) {
    this(http, baseUrl, null, outage);
  }

  /**
   * @param matchBaseUrl a local CLB release bundle that serves the match calls (and the release key, see
   *     {@link #currentXr()}); null or blank means everything goes to {@code baseUrl}
   */
  public ClbClient(PoliteHttp http, String baseUrl, @Nullable String matchBaseUrl, HarvestOutage outage) {
    this.http = http;
    this.base = trim(baseUrl);
    this.matchBase = matchBaseUrl == null || matchBaseUrl.isBlank() ? null : trim(matchBaseUrl.strip());
    this.matchHost = matchBase == null ? null : URI.create(matchBase).getHost();
    this.outage = outage;
  }

  private static String trim(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /** True when matching goes to a local release bundle that also tells the release key. */
  public boolean localBundle() {
    return matchBase != null;
  }

  private String matchRoot() {
    return matchBase != null ? matchBase : base;
  }

  /**
   * The current XR: dataset 3LXR of the CLB API, or with a local bundle the one release it serves ({@code GET
   * /dataset} of a bundle is rewritten to that release).
   */
  public XrInfo currentXr() {
    URI uri = URI.create(localBundle() ? matchBase + "/dataset" : base + "/dataset/" + XR_DATASET);
    return xrInfo(uri, localBundle() ? "of the bundle" : XR_DATASET);
  }

  /** Key and alias of the dataset (a release) with this key, from the CLB API (never the local bundle). */
  public XrInfo dataset(int key) {
    return xrInfo(URI.create(base + "/dataset/" + key), String.valueOf(key));
  }

  private XrInfo xrInfo(URI uri, String what) {
    JsonNode d = getJson(uri);
    if (d.isArray() && !d.isEmpty()) d = d.get(0);
    JsonNode key = d.path("key");
    if (!key.isNumber()) throw new HttpProblem(uri, 200, false, "CLB dataset " + what + " has no key", null);
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
    StringBuilder sb = new StringBuilder(matchRoot()).append("/dataset/").append(releaseKey).append("/match/nameusage");
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
      if (HarvestOutage.retryable(e) != null || unknownMatchHost(e)) throw new ServiceOutageException(SERVICE, outage.retryAt(), e);
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

  /** A bundle host that does not resolve is as down as a CLB host that does not. */
  private boolean unknownMatchHost(HttpProblem e) {
    return matchHost != null && PoliteHttp.unknownHost(e) && e.uri() != null && matchHost.equalsIgnoreCase(e.uri().getHost());
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
