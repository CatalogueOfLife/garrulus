package org.catalogueoflife.nametracker.indexer.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.ContextRow;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.index.NameMatch;
import org.catalogueoflife.nametracker.store.index.NameString;
import org.catalogueoflife.nametracker.store.task.Task;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * {@code MATCH_NAMES}, payload {@code {"workId":n}}. Matches every distinct name of the work in the current XR (hints
 * from the classification context of its first mention that has one, else the document context), places it, and hands
 * the placements to {@link WorkIndexing}. A CLB outage surfaces as a {@code ServiceOutageException} from the match
 * cache and defers the task without using an attempt. Matches are cached per name and hint set, so a repeat is cheap.
 */
@Component
public class MatchNamesHandler implements TaskHandler {

  public static final String TYPE = "MATCH_NAMES";
  private static final Logger LOG = LoggerFactory.getLogger(MatchNamesHandler.class);
  private static final TypeReference<List<RankedName>> RANKED = new TypeReference<>() {};

  private static final ClbMatch NO_MATCH = new ClbMatch("none", null, null, null, null, null, null, List.of(), List.of());

  private final IndexMapper index;
  private final MatchCache cache;
  private final XrReleaseService xr;
  private final WorkIndexing indexing;

  public MatchNamesHandler(IndexMapper index, MatchCache cache, XrReleaseService xr, WorkIndexing indexing) {
    this.index = index;
    this.cache = cache;
    this.xr = xr;
    this.indexing = indexing;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    long workId = FinderJson.mapper().readTree(task.payload()).path("workId").asLong();
    List<MentionRow> mentions = index.mentions(workId);
    Map<Long, Placement> placements = new LinkedHashMap<>();
    if (!mentions.isEmpty()) {
      xr.current(); // pin (or outage) before any name is matched
      Map<Long, ContextRow> contexts = new HashMap<>();
      ContextRow document = null;
      for (ContextRow c : index.contexts(workId)) {
        contexts.put(c.id(), c);
        if (document == null && "DOCUMENT".equals(c.scope())) document = c;
      }
      Map<Long, List<MentionRow>> byName = new LinkedHashMap<>();
      for (MentionRow m : mentions) byName.computeIfAbsent(m.nameStringId(), k -> new ArrayList<>()).add(m);
      // the higher taxa of every verbatim classification are matched and stored, whether a placement needs them or not
      Map<Long, ClbMatch> contextMatches = new HashMap<>();
      for (ContextRow c : contexts.values()) {
        for (RankedName r : ranked(c)) contextMatch(r, contextMatches);
      }
      for (Map.Entry<Long, List<MentionRow>> e : byName.entrySet()) {
        placements.put(e.getKey(), placeName(e.getKey(), e.getValue(), contexts, document, contextMatches));
      }
    }
    indexing.index(workId, placements);
    LOG.debug("Matched {} names of work {}", placements.size(), workId);
  }

  private Placement placeName(long nameStringId, List<MentionRow> mentions, Map<Long, ContextRow> contexts,
      @Nullable ContextRow document, Map<Long, ClbMatch> contextMatches) {
    NameString n = index.nameString(nameStringId);
    if (n == null) throw new IllegalStateException("Unknown name string " + nameStringId);
    if (!Placer.needsMatch(n)) return Placer.place(n, NO_MATCH, null);

    ContextRow ctx = document;
    for (MentionRow m : mentions) {
      if (m.contextId() != null && contexts.containsKey(m.contextId())) {
        ctx = contexts.get(m.contextId());
        break;
      }
    }
    List<RankedName> ranked = ctx == null ? List.of() : ranked(ctx);
    String authorship = mentions.stream().map(MentionRow::authorship).filter(a -> a != null && !a.isBlank())
        .findFirst().orElse(null);

    ClbMatch own = toClb(cache.match(nameStringId, new MatchQuery(n.name(), n.rank(), authorship, hints(ranked))));
    List<ClbMatch> ctxMatches = null;
    if (Placer.needsContext(n, own)) {
      ctxMatches = new ArrayList<>();
      for (RankedName r : ranked) ctxMatches.add(contextMatch(r, contextMatches));
    }
    Placement p = Placer.place(n, own, ctxMatches);
    if (p.taxgroup() == null && Placement.NONE.equals(p.method())) {
      // nothing on the XR: the verbatim classification may still tell the group
      TaxGroup g = TaxGroups.of(n.name(), n.rank(), ranked);
      NomCode c = TaxGroups.codeOf(g);
      if (g != null) p = p.withGroup(c == null ? null : c.name(), g.name());
    }
    return p;
  }

  private static List<RankedName> ranked(ContextRow c) {
    return FinderJson.mapper().readValue(c.ranks(), RANKED);
  }

  /** Matches one context element alone, without hints, as its own UNINOMIAL name string; once per work. */
  private ClbMatch contextMatch(RankedName r, Map<Long, ClbMatch> done) {
    long id = index.nameStringId(r.name(), "UNINOMIAL", r.rank().name());
    ClbMatch cm = done.get(id);
    if (cm == null) {
      cm = toClb(cache.match(id, new MatchQuery(r.name(), r.rank().name(), null, new TreeMap<>())));
      done.put(id, cm);
    }
    return cm;
  }

  static TreeMap<String, String> hints(List<RankedName> ranked) {
    TreeMap<String, String> h = new TreeMap<>();
    for (RankedName r : ranked) {
      String key = key(r.rank());
      if (key != null) h.putIfAbsent(key, r.name());
    }
    return h;
  }

  private static @Nullable String key(Rank r) {
    return switch (r) {
      case KINGDOM -> "kingdom";
      case PHYLUM -> "phylum";
      case CLASS -> "class";
      case ORDER -> "order";
      case FAMILY -> "family";
      case GENUS -> "genus";
      default -> null;
    };
  }

  static ClbMatch toClb(NameMatch m) {
    List<ClbMatch.Taxon> cls = FinderJson.mapper().readValue(m.classification(),
        new TypeReference<List<ClbMatch.Taxon>>() {});
    return new ClbMatch(m.matchType(), m.usageId(), m.usageName(), m.usageRank(), m.usageStatus(), m.acceptedId(),
        m.code(), m.taxonPath(), cls, m.taxgroup());
  }
}
