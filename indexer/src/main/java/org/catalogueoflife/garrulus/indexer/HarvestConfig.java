package org.catalogueoflife.garrulus.indexer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.garrulus.store.blob.BlobStore;
import org.catalogueoflife.garrulus.store.net.PublicHost;
import org.catalogueoflife.garrulus.store.blob.FileBlobStore;
import org.catalogueoflife.garrulus.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.garrulus.store.ident.WorkSightingMapper;
import org.catalogueoflife.garrulus.indexer.backfill.SnapshotClient;
import org.catalogueoflife.garrulus.indexer.content.LicencePolicy;
import org.catalogueoflife.garrulus.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.http.TrustedAuthorities;
import org.catalogueoflife.garrulus.indexer.match.ClbClient;
import org.catalogueoflife.garrulus.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.garrulus.indexer.registry.DoajClient;
import org.catalogueoflife.garrulus.indexer.registry.SeedUpsert;
import org.catalogueoflife.garrulus.indexer.source.SeedProperties;
import org.catalogueoflife.garrulus.indexer.source.SourceSeeder;
import org.catalogueoflife.garrulus.indexer.stats.DisciplineService;
import org.catalogueoflife.garrulus.store.registry.SourceSignalMapper;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.usage.ApiUsageMapper;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.catalogueoflife.garrulus.store.work.WorkUpsert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one place that creates and binds the harvest beans. Properties (all optional, env names in
 * {@code application.yml}): {@code garrulus.openalex.*}, {@code garrulus.seed.*}, {@code garrulus.http.*},
 * {@code garrulus.content.*}, {@code garrulus.crossref.*} and {@code garrulus.snapshot.*}. The content and
 * Crossref properties are read by the content tasks.
 */
@Configuration
public class HarvestConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** The default per-host intervals, each overridable as {@code garrulus.http.min-interval.<host>=<duration>}. */
  @Bean
  PoliteHttp politeHttp(Environment env,
      @Value("${garrulus.http.max-response-bytes:52428800}") long maxResponseBytes) {
    return new PoliteHttp(httpClient(), intervals(env), PoliteHttp.userAgent(), Clock.systemUTC(),
        d -> Thread.sleep(d), maxResponseBytes, new PublicHost(PublicHost.DNS), trustedAuthorities(env));
  }

  /**
   * HTTP/1.1 only: Europe PMC (www.ebi.ac.uk) ends every HTTP/2 stream of the JDK client with an EOF, while HTTP/1.1
   * works. Requests are spaced per host anyway, so HTTP/2 multiplexing buys nothing here. No redirects: PoliteHttp
   * follows them itself and checks every hop.
   */
  static HttpClient httpClient() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** The base URLs of the services PoliteHttp fetches (never public-url, gnfinder or a finder service). */
  static final List<String> SERVICE_URLS = List.of("garrulus.openalex.base-url",
      "garrulus.openalex.content.base-url", "garrulus.snapshot.base-url", "garrulus.clb.base-url",
      "garrulus.clb.match-base-url", "garrulus.crossref.base-url", "garrulus.europepmc.base-url",
      "garrulus.doaj.base-url", "garrulus.pmc.base-url", "garrulus.pensoft.base-url",
      "garrulus.zenodo.base-url", "garrulus.discovery.plazi.base-url", "garrulus.discovery.issnl.url",
      "garrulus.discovery.pkp-beacon.url", "garrulus.finder.xr-zip-url");

  /**
   * The authorities PoliteHttp may fetch whatever their addresses, while a chain stays on them: the exact scheme, host
   * and port of each configured service base URL ({@link #SERVICE_URLS}: a local CLB bundle, a test server) and the
   * entries of {@code garrulus.http.trusted-hosts} (comma-separated; {@code host:port}, a URL, or a bare host for
   * any port of it). URLs from registrations, discovery or route configs are never trusted.
   */
  static TrustedAuthorities trustedAuthorities(Environment env) {
    List<String> entries = new java.util.ArrayList<>();
    for (String key : SERVICE_URLS) {
      String v = env.getProperty(key);
      if (v != null && !v.isBlank()) entries.add(v.strip());
    }
    entries.addAll(Arrays.asList(env.getProperty("garrulus.http.trusted-hosts", "").split(",")));
    return TrustedAuthorities.of(entries);
  }

  static Map<String, Duration> intervals(Environment env) {
    Map<String, Duration> intervals = new HashMap<>(PoliteHttp.defaultIntervals());
    // a local CLB bundle is not rate limited (unless configured below)
    String match = env.getProperty("garrulus.clb.match-base-url", "");
    if (!match.isBlank()) {
      try {
        String host = URI.create(match.strip()).getHost();
        if (host != null) intervals.put(host.toLowerCase(Locale.ROOT), Duration.ZERO);
      } catch (IllegalArgumentException e) {
        // an unusable URL fails where it is used
      }
    }
    // bound as text: a Map<String, Duration> would split a host name at its dots
    Binder.get(env).bind("garrulus.http.min-interval", Bindable.mapOf(String.class, String.class))
        .ifBound(m -> m.forEach((host, v) -> intervals.put(host, DurationStyle.detectAndParse(v))));
    return intervals;
  }

  @Bean
  OpenAlexBudget openAlexBudget(ApiUsageMapper usage, Clock clock,
      @Value("${garrulus.openalex.max-daily-usd:1.00}") BigDecimal maxDailyUsd,
      @Value("${garrulus.openalex.content.reserve-usd:0.30}") BigDecimal contentReserveUsd,
      @Value("${garrulus.openalex.api-key:}") String apiKey) {
    return new OpenAlexBudget(usage, maxDailyUsd, contentReserveUsd, clock, apiKey.isBlank() ? null : apiKey);
  }

  @Bean
  OpenAlexClient openAlexClient(PoliteHttp http, OpenAlexBudget budget,
      @Value("${garrulus.openalex.api-key:}") String apiKey,
      @Value("${garrulus.openalex.base-url:https://api.openalex.org}") String baseUrl) {
    return new OpenAlexClient(http, budget, apiKey.isBlank() ? null : apiKey, baseUrl);
  }

  @Bean
  CrossrefClient crossrefClient(PoliteHttp http, @Value("${garrulus.crossref.mailto:}") String mailto,
      @Value("${garrulus.crossref.base-url:https://api.crossref.org}") String baseUrl) {
    return new CrossrefClient(http, mailto.isBlank() ? null : mailto, baseUrl);
  }

  /** The DOAJ search API v4 (journal metadata for IDENTIFY_SOURCE and DOAJ discovery). */
  @Bean
  DoajClient doajClient(PoliteHttp http,
      @Value("${garrulus.doaj.base-url:https://doaj.org/api/v4}") String baseUrl) {
    return new DoajClient(http, baseUrl);
  }

  @Bean
  ClbClient clbClient(PoliteHttp http, HarvestOutage outage,
      @Value("${garrulus.clb.base-url:https://api.checklistbank.org}") String baseUrl,
      @Value("${garrulus.clb.match-base-url:}") String matchBaseUrl) {
    return new ClbClient(http, baseUrl, matchBaseUrl, outage);
  }

  /** The free OpenAlex snapshot: the manifest through PoliteHttp, the works files streamed with a read timeout. */
  @Bean
  SnapshotClient snapshotClient(PoliteHttp http,
      @Value("${garrulus.snapshot.base-url:https://openalex.s3.amazonaws.com}") String baseUrl,
      @Value("${garrulus.snapshot.read-timeout:5m}") Duration readTimeout) {
    return new SnapshotClient(http, baseUrl, readTimeout);
  }

  @Bean
  WorkUpsert workUpsert(WorkMapper works, WorkIdentifierMapper identifiers, WorkSightingMapper sightings) {
    return new WorkUpsert(works, identifiers, sightings);
  }

  @Bean
  SeedProperties seedProperties(
      @Value("${garrulus.seed.core-min-works:20}") int coreMinWorks,
      @Value("${garrulus.seed.core-min-share:0.30}") double coreMinShare,
      @Value("${garrulus.seed.broad-min-works:30}") int broadMinWorks,
      @Value("${garrulus.seed.broad-min-share:0.50}") double broadMinShare,
      @Value("${garrulus.seed.candidate-min-works:10}") int candidateMinWorks,
      @Value("${garrulus.seed.taxonomy-topics:}") String taxonomyTopics) {
    List<String> topics = Arrays.stream(taxonomyTopics.split("[,|\\s]+")).filter(x -> !x.isEmpty()).toList();
    return new SeedProperties(coreMinWorks, coreMinShare, broadMinWorks, broadMinShare, candidateMinWorks,
        topics.isEmpty() ? SeedProperties.DEFAULT_TAXONOMY_TOPICS : topics);
  }

  @Bean
  SourceSeeder sourceSeeder(OpenAlexClient client, SourceMapper sources, SourceRouteMapper routes, TaskQueue queue,
      SeedProperties props, Clock clock, DisciplineService disciplines, SeedUpsert upsert,
      SourceSignalMapper signals, @Value("${garrulus.openalex.api-key:}") String apiKey) {
    return new SourceSeeder(client, sources, routes, queue, props, clock, disciplines, upsert, signals,
        !apiKey.isBlank());
  }

  @Bean
  BlobStore blobStore(@Value("${garrulus.blobs.dir:./data/blobs}") String dir) {
    return new FileBlobStore(Path.of(dir));
  }

  @Bean
  LicencePolicy licencePolicy(
      @Value("${garrulus.content.open-licences:cc-by,cc-by-sa,cc0,public-domain}") String open) {
    return new LicencePolicy(Arrays.stream(open.split(",")).map(String::trim).filter(x -> !x.isEmpty())
        .collect(Collectors.toSet()));
  }
}
