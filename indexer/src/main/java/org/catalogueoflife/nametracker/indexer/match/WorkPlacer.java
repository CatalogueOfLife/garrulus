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
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.ContextRow;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.IndexRows;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.index.NameMatch;
import org.catalogueoflife.nametracker.store.index.NameString;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * Places every distinct name of a work in an XR release: hints come from the classification context of the name's first
 * mention that has one, else the document context. A name CLB cannot match while it is up
 * ({@link UnmatchableNameException}) is placed NONE (a context element counts as unmatched), logged as a WARN and not
 * cached; the work goes on. More than {@link #MAX_NAMES_AT_FAULT} such names in one call is not the names' fault: the
 * call fails as a CLB outage and nothing is returned. Matches are cached per name, hint set and release. Context
 * elements are asked with the rank stored on their name string, so a cached match always answers the same query.
 */
@Component
public class WorkPlacer {

  /** More names "at fault" than this in one call trip the breaker: something systemic, deferred as an outage. */
  static final int MAX_NAMES_AT_FAULT = 3;
  private static final Logger LOG = LoggerFactory.getLogger(WorkPlacer.class);
  private static final TypeReference<List<RankedName>> RANKED = new TypeReference<>() {};

  private static final ClbMatch NO_MATCH = new ClbMatch("none", null, null, null, null, null, null, List.of(), List.of());

  private final IndexMapper index;
  private final MatchCache cache;
  private final HarvestOutage outage;

  public WorkPlacer(IndexMapper index, MatchCache cache, HarvestOutage outage) {
    this.index = index;
    this.cache = cache;
    this.outage = outage;
  }

  /** Counts the names of one call that CLB fails on while it is up; trips after {@link #MAX_NAMES_AT_FAULT}. */
  private final class Faults {
    private int n;

    void add(String what, long nameStringId, UnmatchableNameException e) {
      if (++n > MAX_NAMES_AT_FAULT) {
        throw new ServiceOutageException(ClbClient.SERVICE, outage.retryAt(), new IllegalStateException(
            "CLB failed on " + n + " names of one task, last " + what + " \"" + e.name() + "\"", e));
      }
      LOG.warn("{} \"{}\" ({}) left unmatched (NONE): {}", what, e.name(), nameStringId, e.getMessage());
    }
  }

  /**
   * Places the row set of the work's index that is to be indexed: its pending index if it has one, else its live index.
   * The row set is read before the rows, so a change in between shows as a changed {@link IndexRows} at indexing time.
   */
  public PlacedWork place(long workId, int releaseKey) {
    IndexRows rows = index.indexRows(workId);
    if (rows == null) rows = new IndexRows(false, null, null); // no such work: nothing to place, nothing to index
    return new PlacedWork(rows, place(workId, releaseKey, rows.pending()));
  }

  /**
   * Placements per name string id of the work's mentions in the given release. With {@code pending} the mentions and
   * contexts come from the work's pending index (a re-index not yet swapped in).
   */
  public Map<Long, Placement> place(long workId, int releaseKey, boolean pending) {
    List<MentionRow> mentions = pending ? index.pendingMentions(workId) : index.mentions(workId);
    Map<Long, Placement> placements = new LinkedHashMap<>();
    if (mentions.isEmpty()) return placements;
    Map<Long, ContextRow> contexts = new HashMap<>();
    ContextRow document = null;
    for (ContextRow c : pending ? index.pendingContexts(workId) : index.contexts(workId)) {
      contexts.put(c.id(), c);
      if (document == null && "DOCUMENT".equals(c.scope())) document = c;
    }
    Map<Long, List<MentionRow>> byName = new LinkedHashMap<>();
    for (MentionRow m : mentions) byName.computeIfAbsent(m.nameStringId(), k -> new ArrayList<>()).add(m);
    // the higher taxa of every verbatim classification are matched and stored, whether a placement needs them or not
    Map<Long, ClbMatch> contextMatches = new HashMap<>();
    Faults faults = new Faults();
    for (ContextRow c : contexts.values()) {
      for (RankedName r : ranked(c)) contextMatch(r, releaseKey, contextMatches, faults);
    }
    for (Map.Entry<Long, List<MentionRow>> e : byName.entrySet()) {
      placements.put(e.getKey(),
          placeName(e.getKey(), releaseKey, e.getValue(), contexts, document, contextMatches, faults));
    }
    return placements;
  }

  private Placement placeName(long nameStringId, int releaseKey, List<MentionRow> mentions, Map<Long, ContextRow> contexts,
      @Nullable ContextRow document, Map<Long, ClbMatch> contextMatches, Faults faults) {
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

    ClbMatch own;
    try {
      own = toClb(cache.match(nameStringId, new MatchQuery(n.name(), n.rank(), authorship, hints(ranked)), releaseKey));
    } catch (UnmatchableNameException e) {
      faults.add("Name", nameStringId, e);
      return withContextGroup(Placer.place(n, NO_MATCH, null), n, ranked);
    }
    List<ClbMatch> ctxMatches = null;
    if (Placer.needsContext(n, own)) {
      ctxMatches = new ArrayList<>();
      for (RankedName r : ranked) ctxMatches.add(contextMatch(r, releaseKey, contextMatches, faults));
    }
    return withContextGroup(Placer.place(n, own, ctxMatches), n, ranked);
  }

  /** Nothing on the XR: the verbatim classification may still tell the group. */
  private static Placement withContextGroup(Placement p, NameString n, List<RankedName> ranked) {
    if (p.taxgroup() == null && Placement.NONE.equals(p.method())) {
      TaxGroup g = TaxGroups.of(n.name(), n.rank(), ranked);
      NomCode c = TaxGroups.codeOf(g);
      if (g != null) p = p.withGroup(c == null ? null : c.name(), g.name());
    }
    return p;
  }

  private static List<RankedName> ranked(ContextRow c) {
    return FinderJson.mapper().readValue(c.ranks(), RANKED);
  }

  /**
   * Matches one context element alone, without hints, as its own UNINOMIAL name string; once per work. The query uses
   * the rank of the stored name string (its first-seen rank), not the rank of this context: the cache key has no rank,
   * so the cached match must always answer the same query.
   */
  private ClbMatch contextMatch(RankedName r, int releaseKey, Map<Long, ClbMatch> done, Faults faults) {
    long id = index.nameStringId(r.name(), "UNINOMIAL", r.rank().name());
    ClbMatch cm = done.get(id);
    if (cm == null) {
      NameString ns = index.nameString(id);
      String rank = ns == null ? r.rank().name() : ns.rank();
      try {
        cm = toClb(cache.match(id, new MatchQuery(r.name(), rank, null, new TreeMap<>()), releaseKey));
      } catch (UnmatchableNameException e) {
        faults.add("Context name", id, e);
        cm = NO_MATCH;
      }
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
