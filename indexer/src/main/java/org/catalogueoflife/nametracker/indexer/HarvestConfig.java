package org.catalogueoflife.nametracker.indexer;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.cost.OpenAlexBudget;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.source.SeedProperties;
import org.catalogueoflife.nametracker.indexer.source.SourceSeeder;
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
 * {@code nametracker.content.*} and {@code nametracker.crossref.*}. The content and Crossref properties are read by
 * the content tasks.
 */
@Configuration
public class HarvestConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** The default per-host intervals, each overridable as {@code nametracker.http.min-interval.<host>=<duration>}. */
  @Bean
  PoliteHttp politeHttp(Environment env) {
    return new PoliteHttp(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(), intervals(env),
        PoliteHttp.userAgent(), Clock.systemUTC(), d -> Thread.sleep(d));
  }

  static Map<String, Duration> intervals(Environment env) {
    Map<String, Duration> intervals = new HashMap<>(PoliteHttp.defaultIntervals());
    // bound as text: a Map<String, Duration> would split a host name at its dots
    Binder.get(env).bind("nametracker.http.min-interval", Bindable.mapOf(String.class, String.class))
        .ifBound(m -> m.forEach((host, v) -> intervals.put(host, DurationStyle.detectAndParse(v))));
    return intervals;
  }

  @Bean
  OpenAlexBudget openAlexBudget(ApiUsageMapper usage, Clock clock,
      @Value("${nametracker.openalex.max-daily-usd:1.00}") BigDecimal maxDailyUsd,
      @Value("${nametracker.openalex.content.reserve-usd:0.30}") BigDecimal contentReserveUsd) {
    return new OpenAlexBudget(usage, maxDailyUsd, contentReserveUsd, clock);
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
  WorkUpsert workUpsert(WorkMapper works) {
    return new WorkUpsert(works);
  }

  @Bean
  SeedProperties seedProperties(
      @Value("${nametracker.seed.core-min-works:20}") int coreMinWorks,
      @Value("${nametracker.seed.core-min-share:0.30}") double coreMinShare,
      @Value("${nametracker.seed.broad-min-works:30}") int broadMinWorks,
      @Value("${nametracker.seed.broad-min-share:0.50}") double broadMinShare,
      @Value("${nametracker.seed.candidate-min-works:10}") int candidateMinWorks) {
    return new SeedProperties(coreMinWorks, coreMinShare, broadMinWorks, broadMinShare, candidateMinWorks);
  }

  @Bean
  SourceSeeder sourceSeeder(OpenAlexClient client, SourceMapper sources, TaskQueue queue, SeedProperties props,
      Clock clock, @Value("${nametracker.openalex.api-key:}") String apiKey) {
    return new SourceSeeder(client, sources, queue, props, clock, !apiKey.isBlank());
  }
}
