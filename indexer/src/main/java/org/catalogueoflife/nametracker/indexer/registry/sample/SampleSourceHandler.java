package org.catalogueoflife.nametracker.indexer.registry.sample;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderUnavailableException;
import org.catalogueoflife.nametracker.finder.remote.FinderUnavailableException;
import org.catalogueoflife.nametracker.indexer.finder.DocumentLoader;
import org.catalogueoflife.nametracker.indexer.finder.ReloadableNameFinder;
import org.catalogueoflife.nametracker.indexer.match.ClbClient;
import org.catalogueoflife.nametracker.indexer.match.ClbMatch;
import org.catalogueoflife.nametracker.indexer.match.MatchQuery;
import org.catalogueoflife.nametracker.indexer.match.TaxGroups;
import org.catalogueoflife.nametracker.indexer.match.UnmatchableNameException;
import org.catalogueoflife.nametracker.indexer.match.XrReleaseService;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.registry.SourceSample;
import org.catalogueoflife.nametracker.store.registry.SourceSampleMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.work.Work;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code SAMPLE_SOURCE}, payload {@code {"sourceId":n}}: reads the newest articles of a candidate journal
 * ({@link SampleFetcher}), runs the name finder over each title and abstract and stores one aggregate row in
 * {@code source_sample}: the article and abstract counts, the mean number of distinct non-OTU names per article, the
 * share of articles with an act and the shares of the five biggest taxonomic groups (up to 60 of the names, the most
 * frequent first, matched in the current release with {@link ClbClient}; memoised within the task). The candidate score
 * is then recomputed. The articles are transient {@link Work}s that are never inserted: nothing is written to
 * {@code work}, {@code mention}, {@code name_string} or {@code name_match}.
 *
 * <p>Skipped: an unknown, CEASED or non-CANDIDATE source (a promoted journal is harvested, not sampled). A finder that
 * is not ready or down, CLB, or a route that is down defers the task by 30 minutes without using an attempt. Without a
 * finder the handler is not enabled and its tasks wait.
 */
@Component
public class SampleSourceHandler implements TaskHandler {

  public static final String TYPE = "SAMPLE_SOURCE";
  static final Duration OUTAGE_DELAY = Duration.ofMinutes(30);
  static final int MAX_MATCHED_NAMES = 60;
  static final int TOP_GROUPS = 5;
  private static final Logger LOG = LoggerFactory.getLogger(SampleSourceHandler.class);

  private final NameFinder finder;
  private final SampleFetcher fetcher;
  private final ClbClient clb;
  private final XrReleaseService xr;
  private final SourceMapper sources;
  private final SourceSampleMapper samples;
  private final CandidateScorer scorer;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SampleSourceHandler(ObjectProvider<NameFinder> finder, SampleFetcher fetcher, ClbClient clb,
      XrReleaseService xr, SourceMapper sources, SourceSampleMapper samples, CandidateScorer scorer,
      TransactionTemplate tx, Clock clock) {
    this.finder = finder.getIfAvailable();
    this.fetcher = fetcher;
    this.clb = clb;
    this.xr = xr;
    this.sources = sources;
    this.samples = samples;
    this.scorer = scorer;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public boolean enabled() {
    return finder != null;
  }

  @Override
  public void handle(Task task) {
    long id = FinderJson.mapper().readTree(task.payload()).path("sourceId").asLong();
    Source s = sources.get(id);
    if (s == null || "CEASED".equals(s.publicationStatus()) || !"CANDIDATE".equals(s.tier())) {
      LOG.info("SAMPLE_SOURCE: source {} is gone, ceased or not a candidate, skipped", id);
      return;
    }
    if (finder instanceof ReloadableNameFinder r && !r.ready()) {
      throw new ServiceOutageException("finder", clock.instant().plus(OUTAGE_DELAY));
    }
    SampleFetcher.Sample sample = fetcher.fetch(s);
    SourceSample row = sample.articles().isEmpty() ? empty(id) : aggregate(id, sample);
    tx.executeWithoutResult(status -> {
      samples.insert(row);
      scorer.rescore(id);
    });
    LOG.info("Sampled source {} ({}): route {}, {} articles, {} names per article", id, s.title(), sample.route(),
        row.articles(), row.namesPerArticle());
  }

  private static SourceSample empty(long id) {
    return new SourceSample(0, id, null, null, 0, 0, null, null, "{}", null);
  }

  private SourceSample aggregate(long id, SampleFetcher.Sample sample) {
    int withAbstract = 0;
    int actArticles = 0;
    long names = 0;
    Map<String, Integer> frequency = new HashMap<>();
    for (SampleArticle a : sample.articles()) {
      if (a.hasAbstract()) withAbstract++;
      Document doc = DocumentLoader.abstractDoc(transientWork(a));
      FinderResult result = doc.blocks().isEmpty() ? null : guarded(() -> finder.find(doc, FinderOptions.defaults()));
      if (result == null) continue;
      Set<String> distinct = new HashSet<>();
      for (Mention m : result.mentions()) {
        if (m.kind() != NameKind.OTU && !m.name().isBlank()) distinct.add(m.name());
      }
      names += distinct.size();
      distinct.forEach(n -> frequency.merge(n, 1, Integer::sum));
      if (!result.acts().isEmpty()) actArticles++;
    }
    int n = sample.articles().size();
    return new SourceSample(0, id, null, sample.route(), n, withAbstract, (float) names / n, (float) actArticles / n,
        taxgroups(frequency), null);
  }

  /** The shares of the five biggest groups among the matched names, as a JSON object. */
  private String taxgroups(Map<String, Integer> frequency) {
    List<String> chosen = new ArrayList<>(frequency.keySet());
    chosen.sort((a, b) -> frequency.get(b).equals(frequency.get(a)) ? a.compareTo(b)
        : Integer.compare(frequency.get(b), frequency.get(a)));
    if (chosen.size() > MAX_MATCHED_NAMES) chosen = chosen.subList(0, MAX_MATCHED_NAMES);
    if (chosen.isEmpty()) return "{}";
    int release = xr.current();
    Map<String, TaxGroup> memo = new HashMap<>();
    Map<TaxGroup, Integer> counts = new LinkedHashMap<>();
    int matched = 0;
    for (String name : chosen) {
      TaxGroup g = memo.computeIfAbsent(name, k -> group(release, k));
      if (g == null) continue;
      counts.merge(g, 1, Integer::sum);
      matched++;
    }
    List<Map.Entry<TaxGroup, Integer>> top = new ArrayList<>(counts.entrySet());
    top.sort((a, b) -> b.getValue().equals(a.getValue()) ? a.getKey().name().compareTo(b.getKey().name())
        : Integer.compare(b.getValue(), a.getValue()));
    Map<String, Double> shares = new TreeMap<>();
    for (var e : top.subList(0, Math.min(TOP_GROUPS, top.size()))) {
      shares.put(e.getKey().name(), Math.round(1000.0 * e.getValue() / matched) / 1000.0);
    }
    return FinderJson.mapper().writeValueAsString(shares);
  }

  /** The group of the name's match, or null when it has none or CLB cannot match the name. A CLB outage defers. */
  private TaxGroup group(int release, String name) {
    try {
      ClbMatch m = clb.match(release, new MatchQuery(name, null, null, new TreeMap<>()));
      return TaxGroups.of(m);
    } catch (UnmatchableNameException e) {
      LOG.debug("Sampling: CLB cannot match {}", name);
      return null;
    }
  }

  private static Work transientWork(SampleArticle a) {
    return new Work(null, null, null, null, a.title(), null, null, null, null, null, null, null, null, null, null,
        null, a.abstractText(), null, null, null, null, null, null, null, null, null, a.abstractJats(), null, null,
        null, null, null, null);
  }

  /** Runs a finder call; an unavailable finder defers the task by 30 minutes without using an attempt. */
  private <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        String service = t instanceof GnfinderUnavailableException ? "gnfinder"
            : t instanceof FinderUnavailableException ? "finder" : null;
        if (service != null) throw new ServiceOutageException(service, clock.instant().plus(OUTAGE_DELAY), e);
        if (t.getCause() == t) break;
      }
      throw e;
    }
  }
}
