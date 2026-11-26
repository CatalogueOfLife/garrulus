package org.catalogueoflife.garrulus.indexer.registry.discovery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.harvest.HarvestOutage;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.http.StreamingGet;
import org.catalogueoflife.garrulus.indexer.registry.Candidate;
import org.catalogueoflife.garrulus.indexer.registry.CandidateRegistry;
import org.catalogueoflife.garrulus.indexer.task.NonRetryableTaskException;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.ident.Issn;
import org.catalogueoflife.garrulus.store.registry.DiscoveryTitleMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Discovery from the references of the COL extended release. Phase 1 (payload {@code {}}) streams the XR zip
 * ({@code garrulus.finder.xr-zip-url}, never kept on disk, outages as in {@code BuildDictionaryHandler}), reads its
 * {@code Reference.tsv} (also {@code .txt}, any directory; headers with or without {@code col:}) and
 * groups the references by normalised {@code issn}, else by the {@link JournalTitleKey} of the {@code containerTitle}
 * (references with neither are skipped). A group needs {@code garrulus.discovery.col-references.min-references} (10)
 * references. ISSN groups are offered at once, detail {@code {"references","datasets","title"}}; title groups (the
 * {@code max-titles}, 2000, largest) go to {@code discovery_title} with the same detail, and phase 2
 * ({@code {"resolve":true,"after":key}}) resolves them through {@link TitleResolver}.
 */
@Component
public class ColReferencesDiscovery implements DiscoverySource {

  public static final String ORIGIN = "COL_REFERENCES";
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Logger LOG = LoggerFactory.getLogger(ColReferencesDiscovery.class);

  /** The references of one journal: the count, the distinct source datasets and the first title seen. */
  private static final class Group {
    final String title;
    int references;
    final Set<String> datasets = new HashSet<>();

    Group(String title) {
      this.title = title;
    }
  }

  private final CandidateRegistry registry;
  private final DiscoveryTitleMapper titles;
  private final TitleResolver resolver;
  private final HarvestOutage outage;
  private final String zipUrl;
  private final Duration readTimeout;
  private final int minReferences;
  private final int maxTitles;

  public ColReferencesDiscovery(CandidateRegistry registry, DiscoveryTitleMapper titles, TitleResolver resolver,
      HarvestOutage outage,
      @Value("${garrulus.finder.xr-zip-url:https://download.checklistbank.org/col/xr_latest_coldp.zip}") String zipUrl,
      @Value("${garrulus.finder.xr-zip-read-timeout:5m}") Duration readTimeout,
      @Value("${garrulus.discovery.col-references.min-references:10}") int minReferences,
      @Value("${garrulus.discovery.col-references.max-titles:2000}") int maxTitles) {
    this.registry = registry;
    this.titles = titles;
    this.resolver = resolver;
    this.outage = outage;
    this.zipUrl = zipUrl.strip();
    this.readTimeout = readTimeout;
    this.minReferences = minReferences;
    this.maxTitles = maxTitles;
  }

  @Override
  public String origin() {
    return ORIGIN;
  }

  @Override
  public @Nullable String page(JsonNode payload) throws IOException {
    if (payload.path("resolve").asBoolean(false)) return TitleDiscovery.resolveStep(ORIGIN, payload, resolver);
    Map<String, Group> byIssn = new HashMap<>();
    Map<String, Group> byTitle = new HashMap<>();
    long skipped = read(byIssn, byTitle);

    Offers offers = new Offers(registry);
    for (var e : byIssn.entrySet()) {
      Group g = e.getValue();
      if (g.references >= minReferences) {
        offers.offer(new Candidate(ORIGIN, "", List.of(e.getKey()), g.title.isEmpty() ? null : g.title, null, null,
            null, detail(g), List.of(), null));
      }
    }
    List<Map.Entry<String, Group>> chosen = new ArrayList<>(byTitle.entrySet().stream()
        .filter(e -> e.getValue().references >= minReferences).toList());
    chosen.sort(Comparator.<Map.Entry<String, Group>>comparingInt(e -> e.getValue().references).reversed()
        .thenComparing(Map.Entry::getKey));
    if (chosen.size() > maxTitles) chosen = chosen.subList(0, maxTitles);
    List<String> keep = new ArrayList<>();
    for (var e : chosen) {
      Group g = e.getValue();
      titles.upsert(ORIGIN, e.getKey(), g.title, null, g.references, g.datasets.size(), detail(g));
      keep.add(e.getKey());
    }
    titles.deleteExcept(ORIGIN, keep);
    LOG.info("COL references: {} ISSN groups ({}), {} title groups kept, {} references without ISSN or title skipped",
        byIssn.size(), offers.summary(), keep.size(), skipped);
    return keep.isEmpty() ? null : TitleDiscovery.resolveStart(ORIGIN);
  }

  private static String detail(Group g) {
    return FinderJson.mapper().createObjectNode().put("references", g.references).put("datasets", g.datasets.size())
        .put("title", g.title).toString();
  }

  /** Streams the zip and fills the groups; returns the number of references with neither ISSN nor title. */
  private long read(Map<String, Group> byIssn, Map<String, Group> byTitle) throws IOException {
    StreamingGet.Opened resp;
    try {
      resp = StreamingGet.open(URI.create(zipUrl), CONNECT_TIMEOUT, readTimeout);
    } catch (HttpProblem e) {
      throw new ServiceOutageException("xr-zip", outage.retryAt(), e);
    }
    HttpURLConnection conn = resp.connection();
    try {
      int status = resp.status();
      if (status >= 500 || status == 429) {
        throw new ServiceOutageException("xr-zip", outage.retryAt(), new IOException("HTTP " + status + " for " + zipUrl));
      }
      if (status != 200) throw new IOException("HTTP " + status + " for " + zipUrl);
      try (InputStream raw = conn.getInputStream(); ZipInputStream zin = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
          if (isReferenceFile(e.getName())) {
            try {
              return readTable(zin, byIssn, byTitle);
            } catch (IllegalStateException bad) { // unreadable table: not an outage, the task fails
              throw new NonRetryableTaskException("Reference table " + e.getName() + " of " + zipUrl + ": " + bad.getMessage());
            }
          }
        }
      } catch (NonRetryableTaskException ex) {
        throw ex;
      } catch (IOException | UncheckedIOException ex) {
        throw new ServiceOutageException("xr-zip", outage.retryAt(), ex); // broke off or stalled
      }
      throw new IOException("No Reference.tsv in the XR zip " + zipUrl);
    } finally {
      conn.disconnect();
    }
  }

  static boolean isReferenceFile(String entry) {
    String n = entry.substring(entry.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return n.equals("reference.tsv") || n.equals("reference.txt");
  }

  /** Reads the table from the stream (not closing it); a broken stream is an IOException. */
  static long readTable(InputStream in, Map<String, Group> byIssn, Map<String, Group> byTitle) throws IOException {
    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    String header = r.readLine();
    if (header == null) throw new IllegalStateException("the table is empty");
    String[] cols = header.split("\t", -1);
    int issn = column(cols, "issn"), title = column(cols, "containertitle"), source = column(cols, "sourceid");
    if (issn < 0 || title < 0) {
      throw new IllegalStateException("no " + (issn < 0 ? "issn" : "containerTitle") + " column in header " + header);
    }
    long skipped = 0;
    String line;
    while ((line = r.readLine()) != null) {
      String[] f = line.split("\t", -1);
      String i = Issn.normalise(field(f, issn));
      String t = field(f, title);
      String key = i != null ? null : JournalTitleKey.of(t);
      if (i == null && key == null) {
        skipped++;
        continue;
      }
      Group g = i != null ? byIssn.computeIfAbsent(i, k -> new Group(t)) : byTitle.computeIfAbsent(key, k -> new Group(t));
      g.references++;
      String s = field(f, source);
      if (!s.isEmpty()) g.datasets.add(s);
    }
    return skipped;
  }

  private static int column(String[] cols, String name) {
    for (int i = 0; i < cols.length; i++) {
      String c = cols[i].strip().toLowerCase(Locale.ROOT);
      if (c.startsWith("col:")) c = c.substring(4);
      if (c.equals(name)) return i;
    }
    return -1;
  }

  private static String field(String[] f, int i) {
    return i < 0 || i >= f.length ? "" : f[i].strip();
  }
}
