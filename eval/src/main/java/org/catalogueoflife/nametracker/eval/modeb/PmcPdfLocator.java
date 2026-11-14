package org.catalogueoflife.nametracker.eval.modeb;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds an open access article's PDF in the PMC Article Datasets on AWS ({@code s3://pmc-oa-opendata}, a public bucket
 * served over HTTPS; free, no account). Pensoft deposits its journals there, and the PDFs are byte-identical to those on
 * pensoft.net (checked by MD5), which throttles downloads to a few KB/s per connection.
 *
 * <p>DOI to PMCID through the Europe PMC search API, then the highest article version {@code PMCxxxx.N/} in the bucket.
 * At most one Europe PMC request per second.
 */
public class PmcPdfLocator {
  public static final String EUROPE_PMC = "https://www.ebi.ac.uk/europepmc/webservices/rest/search";
  public static final String BUCKET = "https://pmc-oa-opendata.s3.amazonaws.com";
  private static final Pattern VERSION = Pattern.compile("<Prefix>(PMC\\d+)\\.(\\d+)/</Prefix>");
  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final HttpClient http;
  private final String europePmc;
  private final String bucket;
  private long lastRequest;

  public PmcPdfLocator(HttpClient http) {
    this(http, EUROPE_PMC, BUCKET);
  }

  PmcPdfLocator(HttpClient http, String europePmc, String bucket) {
    this.http = http;
    this.europePmc = europePmc;
    this.bucket = bucket;
  }

  /** @return the HTTPS URL of the PDF of the latest version, empty if the article is not open access in PMC or has no version there */
  public Optional<String> pdfUrl(String doi) throws IOException, InterruptedException {
    Optional<String> pmcid = pmcid(doi);
    if (pmcid.isEmpty()) return Optional.empty();
    String list = get(bucket + "/?list-type=2&delimiter=/&prefix=" + pmcid.get() + ".");
    Matcher m = VERSION.matcher(list);
    int best = -1;
    while (m.find()) {
      if (m.group(1).equals(pmcid.get())) best = Math.max(best, Integer.parseInt(m.group(2)));
    }
    if (best < 0) return Optional.empty();
    String key = pmcid.get() + "." + best;
    return Optional.of(bucket + "/" + key + "/" + key + ".pdf");
  }

  /** The PMCID of an open access article with this DOI, per Europe PMC. */
  Optional<String> pmcid(String doi) throws IOException, InterruptedException {
    throttle();
    String q = URLEncoder.encode("DOI:\"" + doi + "\"", StandardCharsets.UTF_8);
    JsonNode root = FinderJson.mapper().readTree(get(europePmc + "?format=json&resultType=lite&query=" + q));
    for (JsonNode r : root.path("resultList").path("result")) {
      String d = r.path("doi").asString("");
      String id = r.path("pmcid").asString("");
      if (d.toLowerCase(Locale.ROOT).equals(doi.toLowerCase(Locale.ROOT)) && !id.isEmpty() && "Y".equals(r.path("isOpenAccess").asString(""))) {
        return Optional.of(id);
      }
    }
    return Optional.empty();
  }

  private synchronized void throttle() throws InterruptedException {
    long wait = lastRequest + 1000 - System.currentTimeMillis();
    if (wait > 0) Thread.sleep(wait);
    lastRequest = System.currentTimeMillis();
  }

  private String get(String url) throws IOException, InterruptedException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("User-Agent", PdfDownloader.USER_AGENT).GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
    return resp.body();
  }
}
