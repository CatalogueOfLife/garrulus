package org.catalogueoflife.nametracker.eval.corpus;

import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Samples open-access Pensoft articles via OpenAlex and fetches their TaxPub XML at 1 request per second. */
public class PensoftFetcher {
  public static final String USER_AGENT = OpenAlexClient.USER_AGENT;
  public static final String FILTER = "primary_location.source.id:S199213172|S138605562|S4210227917|S2764367193,"
      + "open_access.is_oa:true,type:article,from_publication_date:2024-01-01";
  private static final Map<String, String> JOURNALS = Map.of(
      "zookeys", "zookeys", "phytokeys", "phytokeys", "mycokeys", "mycokeys", "bdj", "bdj");
  private static final long MIN_INTERVAL_MS = 1000;

  private final HttpClient http;
  private final OpenAlexClient openAlex;
  private final long minIntervalMs;
  private long retryDefaultMs = 10_000;
  private long lastRequest;

  public PensoftFetcher(HttpClient http, OpenAlexClient openAlex) {
    this(http, openAlex, MIN_INTERVAL_MS);
  }

  PensoftFetcher(HttpClient http, OpenAlexClient openAlex, long minIntervalMs) {
    this.http = http;
    this.openAlex = openAlex;
    this.minIntervalMs = minIntervalMs;
  }

  /** The journal key of a Pensoft DOI: "10.3897/zookeys.1297.218447" gives "zookeys", "10.3897/BDJ.14.e202849" gives "bdj". */
  static String journal(String doi) {
    String suffix = doi.substring(doi.indexOf('/') + 1);
    int dot = suffix.indexOf('.');
    if (dot < 0) throw new IllegalArgumentException("Not a supported Pensoft DOI: " + doi);
    String j = suffix.substring(0, dot).toLowerCase(Locale.ROOT);
    if (!doi.startsWith("10.3897/") || !JOURNALS.containsKey(j)) {
      throw new IllegalArgumentException("Not a supported Pensoft DOI: " + doi);
    }
    return JOURNALS.get(j);
  }

  static String articleId(String doi) {
    String last = doi.substring(doi.lastIndexOf('.') + 1);
    return last.replaceFirst("^e", "");
  }

  public static String xmlUrl(String doi) {
    return "https://" + journal(doi) + ".pensoft.net/article/" + articleId(doi) + "/download/xml/";
  }

  public static String pdfUrl(String doi) {
    return "https://" + journal(doi) + ".pensoft.net/article/" + articleId(doi) + "/download/pdf/";
  }

  /** Only these OpenAlex licences are committed as text; the value is the label stored in the manifest. */
  static String licenseLabel(String openAlexLicense) {
    String l = openAlexLicense == null ? "" : openAlexLicense.trim().toLowerCase(Locale.ROOT);
    return switch (l) {
      case "cc-by" -> "CC-BY";
      case "cc0" -> "CC0";
      default -> null;
    };
  }

  /**
   * All CC-BY / CC0 candidates, sorted by DOI and then shuffled deterministically by seed. The split column is empty.
   * Sorting first makes the result independent of the API's page order.
   */
  public List<CorpusManifest.Row> candidates(long seed) {
    Map<String, CorpusManifest.Row> byDoi = new LinkedHashMap<>();
    for (JsonNode w : openAlex.listWorks(FILTER, Integer.MAX_VALUE, "doi,primary_location")) {
      String doi = w.path("doi").asString("").replaceFirst("^https?://doi.org/", "");
      if (!doi.startsWith("10.3897/")) continue;
      String lic = licenseLabel(w.path("primary_location").path("license").asString(""));
      if (lic == null) continue;
      String j;
      try {
        j = journal(doi);
      } catch (IllegalArgumentException e) {
        continue;
      }
      byDoi.putIfAbsent(doi, new CorpusManifest.Row(docId(doi), doi, j, lic, "", xmlUrl(doi), pdfUrl(doi)));
    }
    List<CorpusManifest.Row> all = new ArrayList<>(byDoi.values());
    all.sort((a, b) -> a.doi().compareTo(b.doi()));
    Collections.shuffle(all, new Random(seed));
    return all;
  }

  /** n distinct candidates; the first devCount are "dev", the rest "test". */
  public List<CorpusManifest.Row> sample(int n, int devCount, long seed) {
    List<CorpusManifest.Row> all = candidates(seed);
    List<CorpusManifest.Row> out = new ArrayList<>();
    for (int i = 0; i < Math.min(n, all.size()); i++) {
      out.add(all.get(i).withSplit(i < devCount ? "dev" : "test"));
    }
    return out;
  }

  static String docId(String doi) {
    return "pensoft:" + doi.substring(doi.indexOf('/') + 1).toLowerCase(Locale.ROOT);
  }

  /** Downloads the XML (at most 1 request per second) and extracts the gold document. */
  public GoldDocument fetch(CorpusManifest.Row row) {
    byte[] xml = download(row.xmlUrl());
    return new TaxPubGoldExtractor().extract(new ByteArrayInputStream(xml), row.docId(), row.split(), row.license());
  }

  private synchronized byte[] download(String url) {
    try {
      long wait = lastRequest + minIntervalMs - System.currentTimeMillis();
      if (wait > 0) Thread.sleep(wait);
      lastRequest = System.currentTimeMillis();
      HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
          .header("User-Agent", USER_AGENT).GET().build();
      HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
        long pause = resp.headers().firstValue("Retry-After").map(v -> parseSeconds(v) * 1000).orElse(retryDefaultMs);
        Thread.sleep(pause);
        lastRequest = System.currentTimeMillis();
        resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      }
      if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
      return resp.body();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted", e);
    }
  }

  /** Test hook: the pause before the single retry when the server sends no Retry-After. */
  PensoftFetcher retryDelay(long ms) {
    this.retryDefaultMs = ms;
    return this;
  }

  private static long parseSeconds(String v) {
    try {
      return Math.max(0, Long.parseLong(v.trim()));
    } catch (NumberFormatException e) {
      return 10;
    }
  }

  static HttpClient defaultClient() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
  }

}
