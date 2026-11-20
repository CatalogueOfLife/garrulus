package org.catalogueoflife.nametracker.indexer;

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
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.blob.FileBlobStore;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.catalogueoflife.nametracker.indexer.backfill.SnapshotClient;
import org.catalogueoflife.nametracker.indexer.content.LicencePolicy;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.match.ClbClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.source.SeedProperties;
import org.catalogueoflife.nametracker.indexer.source.SourceSeeder;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineService;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.usage.ApiUsageMapper;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The one place that creates and binds the harvest beans. Properties (all optional, env names in
 * {@code application.yml}): {@code nametracker.openalex.*}, {@code nametracker.seed.*}, {@code nametracker.http.*},
 * {@code nametracker.content.*}, {@code nametracker.crossref.*} and {@code nametracker.snapshot.*}. The content and
 * Crossref properties are read by the content tasks.
 */
@Configuration
public class HarvestConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** The default per-host intervals, each overridable as {@code nametracker.http.min-interval.<host>=<duration>}. */
  @Bean
  PoliteHttp politeHttp(Environment env,
      @Value("${nametracker.http.max-response-bytes:52428800}") long maxResponseBytes) {
    return new PoliteHttp(httpClient(), intervals(env), PoliteHttp.userAgent(), Clock.systemUTC(),
        d -> Thread.sleep(d), maxResponseBytes);
  }

  /**
   * HTTP/1.1 only: Europe PMC (www.ebi.ac.uk) ends every HTTP/2 stream of the JDK client with an EOF, while HTTP/1.1
   * works. Requests are spaced per host anyway, so HTTP/2 multiplexing buys nothing here.
   */
  static HttpClient httpClient() {
    return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  static Map<String, Duration> intervals(Environment env) {
    Map<String, Duration> intervals = new HashMap<>(PoliteHttp.defaultIntervals());
    // a local CLB bundle is not rate limited (unless configured below)
    String match = env.getProperty("nametracker.clb.match-base-url", "");
    if (!match.isBlank()) {
      try {
        String host = URI.create(match.strip()).getHost();
        if (host != null) intervals.put(host.toLowerCase(Locale.ROOT), Duration.ZERO);
      } catch (IllegalArgumentException e) {
        // an unusable URL fails where it is used
      }
    }
    // bound as text: a Map<String, Duration> would split a host name at its dots
    Binder.get(env).bind("nametracker.http.min-interval", Bindable.mapOf(String.class, String.class))
        .ifBound(m -> m.forEach((host, v) -> intervals.put(host, DurationStyle.detectAndParse(v))));
    return intervals;
  }

  @Bean
  OpenAlexBudget openAlexBudget(ApiUsageMapper usage, Clock clock,
      @Value("${nametracker.openalex.max-daily-usd:1.00}") BigDecimal maxDailyUsd,
      @Value("${nametracker.openalex.content.reserve-usd:0.30}") BigDecimal contentReserveUsd,
      @Value("${nametracker.openalex.api-key:}") String apiKey) {
    return new OpenAlexBudget(usage, maxDailyUsd, contentReserveUsd, clock, apiKey.isBlank() ? null : apiKey);
  }

  @Bean
  OpenAlexClient openAlexClient(PoliteHttp http, OpenAlexBudget budget,
      @Value("${nametracker.openalex.api-key:}") String apiKey,
      @Value("${nametracker.openalex.base-url:https://api.openalex.org}") String baseUrl) {
    return new OpenAlexClient(http, budget, apiKey.isBlank() ? null : apiKey, baseUrl);
  }

  @Bean
  CrossrefClient crossrefClient(PoliteHttp http, @Value("${nametracker.crossref.mailto:}") String mailto,
      @Value("${nametracker.crossref.base-url:https://api.crossref.org}") String baseUrl) {
    return new CrossrefClient(http, mailto.isBlank() ? null : mailto, baseUrl);
  }

  @Bean
  ClbClient clbClient(PoliteHttp http, HarvestOutage outage,
      @Value("${nametracker.clb.base-url:https://api.checklistbank.org}") String baseUrl,
      @Value("${nametracker.clb.match-base-url:}") String matchBaseUrl) {
    return new ClbClient(http, baseUrl, matchBaseUrl, outage);
  }

  /** The free OpenAlex snapshot: the manifest through PoliteHttp, the works files streamed with a read timeout. */
  @Bean
  SnapshotClient snapshotClient(PoliteHttp http,
      @Value("${nametracker.snapshot.base-url:https://openalex.s3.amazonaws.com}") String baseUrl,
      @Value("${nametracker.snapshot.read-timeout:5m}") Duration readTimeout) {
    return new SnapshotClient(http, baseUrl, readTimeout);
  }

  @Bean
  WorkUpsert workUpsert(WorkMapper works, WorkIdentifierMapper identifiers, WorkSightingMapper sightings) {
    return new WorkUpsert(works, identifiers, sightings);
  }

  @Bean
  SeedProperties seedProperties(
      @Value("${nametracker.seed.core-min-works:20}") int coreMinWorks,
      @Value("${nametracker.seed.core-min-share:0.30}") double coreMinShare,
      @Value("${nametracker.seed.broad-min-works:30}") int broadMinWorks,
      @Value("${nametracker.seed.broad-min-share:0.50}") double broadMinShare,
      @Value("${nametracker.seed.candidate-min-works:10}") int candidateMinWorks,
      @Value("${nametracker.seed.taxonomy-topics:}") String taxonomyTopics) {
    List<String> topics = Arrays.stream(taxonomyTopics.split("[,|\\s]+")).filter(x -> !x.isEmpty()).toList();
    return new SeedProperties(coreMinWorks, coreMinShare, broadMinWorks, broadMinShare, candidateMinWorks,
        topics.isEmpty() ? SeedProperties.DEFAULT_TAXONOMY_TOPICS : topics);
  }

  @Bean
  SourceSeeder sourceSeeder(OpenAlexClient client, SourceMapper sources, TaskQueue queue, SeedProperties props,
      Clock clock, DisciplineService disciplines, @Value("${nametracker.openalex.api-key:}") String apiKey) {
    return new SourceSeeder(client, sources, queue, props, clock, disciplines, !apiKey.isBlank());
  }

  @Bean
  BlobStore blobStore(@Value("${nametracker.blobs.dir:./data/blobs}") String dir) {
    return new FileBlobStore(Path.of(dir));
  }

  @Bean
  LicencePolicy licencePolicy(
      @Value("${nametracker.content.open-licences:cc-by,cc-by-sa,cc0,public-domain}") String open) {
    return new LicencePolicy(Arrays.stream(open.split(",")).map(String::trim).filter(x -> !x.isEmpty())
        .collect(Collectors.toSet()));
  }
}
