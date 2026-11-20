package org.catalogueoflife.nametracker.indexer.source;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClassifications;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClient.GroupCount;
import org.catalogueoflife.nametracker.indexer.registry.SourceIdentity;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineService;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.catalogueoflife.nametracker.store.registry.SourceSignalMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import tools.jackson.databind.JsonNode;

/**
 * Builds the journal registry from OpenAlex: counts the articles of the last three years per journal in the taxonomy
 * topics and in the broad subfields, and sets the tier from the counts and their share of all the journal's articles.
 * Tiers set by an admin are never touched.
 */
public class SourceSeeder {

  private static final Logger LOG = LoggerFactory.getLogger(SourceSeeder.class);

  /** The harvested subfield vocabulary. */
  static final List<String> SUBFIELDS = List.of("1105", "1109", "1103", "1104", "1110", "1911", "2404", "2405", "2303");
  static final int TOTALS_BATCH = 50;
  static final int YEARS = 3;
  /** The group-by page size of OpenAlex; a keyless client sees no more than one page. */
  private static final int PAGE = 200;

  public record SeedReport(int core, int broad, int candidate, int skipped, boolean truncated) {
  }

  private final OpenAlexClient client;
  private final SourceMapper sources;
  private final TaskQueue queue;
  private final SeedProperties props;
  private final Clock clock;
  private final DisciplineService disciplines;
  private final SourceIdentity identity;
  private final SourceSignalMapper signals;
  private final boolean paging;

  /** @param paging true with an API key, when group-by calls return all pages */
  public SourceSeeder(OpenAlexClient client, SourceMapper sources, TaskQueue queue, SeedProperties props, Clock clock,
      DisciplineService disciplines, SourceIdentity identity, SourceSignalMapper signals, boolean paging) {
    this.identity = identity;
    this.signals = signals;
    this.disciplines = disciplines;
    this.client = client;
    this.sources = sources;
    this.queue = queue;
    this.props = props;
    this.clock = clock;
    this.paging = paging;
  }

  public SeedReport seed() {
    for (String topic : props.taxonomyTopics()) {
      if (client.topic(topic).isEmpty()) LOG.warn("configured taxonomy topic {} is unknown to OpenAlex", topic);
    }
    String window = "from_publication_date:" + LocalDate.now(clock).minusYears(YEARS)
        + ",type:article,primary_location.source.type:journal";
    List<GroupCount> taxGroups = client.groupBy(window + ",primary_topic.id:" + String.join("|", props.taxonomyTopics()),
        "primary_location.source.id");
    List<GroupCount> broadGroups = client.groupBy(
        window + ",primary_topic.subfield.id:" + String.join("|", SUBFIELDS), "primary_location.source.id");
    boolean truncated = !paging && (taxGroups.size() >= PAGE || broadGroups.size() >= PAGE);
    Map<String, Long> taxonomy = counts(taxGroups);
    Map<String, Long> broad = counts(broadGroups);

    Set<String> ids = new LinkedHashSet<>(taxonomy.keySet());
    ids.addAll(broad.keySet());
    Map<String, Long> totals = new LinkedHashMap<>();
    List<String> all = new ArrayList<>(ids);
    for (int i = 0; i < all.size(); i += TOTALS_BATCH) {
      List<String> batch = all.subList(i, Math.min(all.size(), i + TOTALS_BATCH));
      totals.putAll(counts(client.groupBy(window + ",primary_location.source.id:" + String.join("|", batch),
          "primary_location.source.id")));
    }

    int core = 0, bro = 0, cand = 0, skipped = 0;
    for (String id : all) {
      String tier = tier(taxonomy.getOrDefault(id, 0L), broad.getOrDefault(id, 0L), totals.getOrDefault(id, 0L));
      JsonNode node = tier == null ? null : client.source(id).orElse(null);
      if (node == null) {
        skipped++;
        continue;
      }
      long total = totals.get(id);
      Source row = build(node, id, tier, "SEED_TOPIC", "SEED", (int) (total / YEARS));
      Source existing = sources.findByOpenalexId(id);
      if (existing == null) existing = adoptCandidate(id, row);
      long sourceId = sources.upsertSeed(row);
      disciplines.applyOpenAlex(sourceId, node);
      signals.upsert(sourceId, "SEED_TOPIC", "", FinderJson.mapper().createObjectNode()
          .put("taxonomyWorks", taxonomy.getOrDefault(id, 0L)).put("broadWorks", broad.getOrDefault(id, 0L))
          .put("totalWorks", total).toString());
      if (existing != null && "SEED".equals(existing.tierSetBy()) && !tier.equals(existing.tier())
          && !"CEASED".equals(existing.publicationStatus())) {
        sources.setTier(sourceId, tier, Boolean.TRUE.equals(existing.active()), "SEED");
      }
      switch (tier) {
        case "CORE" -> core++;
        case "BROAD" -> bro++;
        default -> cand++;
      }
    }
    int queued = enqueueOnboarding();
    LOG.info("Seeded sources: {} core, {} broad, {} candidate, {} skipped{}; {} awaiting onboarding", core, bro, cand,
        skipped, truncated ? " (truncated)" : "", queued);
    return new SeedReport(core, bro, cand, skipped, truncated);
  }

  /**
   * A journal the registry already holds as an ISSN-only candidate (found through its ISSNs or ISSN-L) gets the OpenAlex
   * id, so the seed updates that row instead of adding a second one. Returns that source, or null.
   */
  private @Nullable Source adoptCandidate(String openalexId, Source row) {
    List<String> issns = new ArrayList<>();
    for (String i : row.issns()) {
      String n = Issn.normalise(i);
      if (n != null && !issns.contains(n)) issns.add(n);
    }
    if (row.issnL() != null && Issn.normalise(row.issnL()) != null && !issns.contains(Issn.normalise(row.issnL()))) {
      issns.add(Issn.normalise(row.issnL()));
    }
    if (issns.isEmpty()) return null;
    Source hit = identity.find(issns, null);
    if (hit == null || hit.openalexId() != null) return null;
    sources.updateIdentity(hit.id(), null, issns, null, null, null, openalexId, null);
    return hit;
  }

  /** One ONBOARD_SOURCE task per active CORE/BROAD source not yet onboarded; the dedup key keeps it to one. */
  int enqueueOnboarding() {
    List<Source> todo = sources.listToOnboard();
    for (Source s : todo) {
      enqueueOnboarding(s.id());
    }
    return todo.size();
  }

  void enqueueOnboarding(long sourceId) {
    queue.enqueue("ONBOARD_SOURCE", "{\"sourceId\":" + sourceId + "}", TaskPriority.BACKFILL, "onboard:" + sourceId);
  }

  /** CORE, BROAD, CANDIDATE or null (skipped). A journal without any article in the window has no share and is skipped. */
  @Nullable String tier(long taxonomy, long broad, long total) {
    if (total <= 0) return null;
    if (taxonomy >= props.coreMinWorks() && (double) taxonomy / total >= props.coreMinShare()) return "CORE";
    if (broad >= props.broadMinWorks() && (double) broad / total >= props.broadMinShare()) return "BROAD";
    if (broad >= props.candidateMinWorks()) return "CANDIDATE";
    return null;
  }

  /**
   * The registry row for an OpenAlex source entity; its disciplines are derived by {@link DisciplineService}. {@code
   * worksPerYear} may be null (admin-added sources).
   * Tier, origin and tier_set_by are only used when the row is new.
   */
  public static Source build(JsonNode n, String id, String tier, String origin, String tierSetBy,
      @Nullable Integer worksPerYear) {
    String issnL = text(n, "issn_l");
    List<String> issns = new ArrayList<>();
    n.path("issn").forEach(i -> issns.add(i.asString()));
    Boolean isOa = bool(n, "is_oa");
    Boolean inDoaj = bool(n, "is_in_doaj");
    JsonNode apc = n.path("apc_usd");
    Double apcUsd = apc.isNumber() ? apc.asDouble(0) : null;
    return new Source(null, id, issnL, issns, text(n, "display_name") == null ? id : text(n, "display_name"),
        text(n, "host_organization_name"), host(text(n, "homepage_url")), isOa, inDoaj, tier, origin, null, true,
        List.of(), null, "CORE".equals(tier) ? "TAXONOMIC" : "GENERAL", null,
        null, null, oaModel(isOa, inDoaj, apcUsd), text(n, "country_code"), null, null, worksPerYear, null, null, null,
        null, tierSetBy);
  }

  /** DIAMOND: in DOAJ without a charge; GOLD: open access with a charge; otherwise unknown. */
  static @Nullable String oaModel(@Nullable Boolean isOa, @Nullable Boolean inDoaj, @Nullable Double apcUsd) {
    boolean noCharge = apcUsd == null || apcUsd == 0;
    if (Boolean.TRUE.equals(inDoaj) && noCharge) return "DIAMOND";
    if (Boolean.TRUE.equals(isOa) && apcUsd != null && apcUsd > 0) return "GOLD";
    return null;
  }

  /** The last path segment: {@code https://openalex.org/S123} gives {@code S123}. */
  static String bareId(String key) {
    return OpenAlexClassifications.bareId(key);
  }

  private static Map<String, Long> counts(List<GroupCount> groups) {
    Map<String, Long> m = new LinkedHashMap<>();
    for (GroupCount g : groups) m.merge(bareId(g.key()), g.count(), Long::sum);
    return m;
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isString() && !v.asString().isBlank() ? v.asString() : null;
  }

  private static @Nullable Boolean bool(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isBoolean() ? v.asBoolean() : null;
  }

  private static @Nullable String host(@Nullable String url) {
    if (url == null) return null;
    try {
      String h = URI.create(url.strip()).getHost();
      return h == null ? null : h.toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
