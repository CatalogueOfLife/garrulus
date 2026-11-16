package org.catalogueoflife.nametracker.indexer.hits;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.match.TaxGroups;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.store.account.HitMapper;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.NameString;
import org.catalogueoflife.nametracker.store.index.WorkFacts;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.catalogueoflife.nametracker.store.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code EVALUATE_HITS}, payload {@code {"workId":n}}. After a work is indexed, records an {@code ARTICLE} hit for
 * every unpaused subscription that follows it and, when a re-index brings names with acts that the {@code ARTICLE}
 * snapshot lacks, one {@code NEW_NAMES} hit. Rules: design spec, "Hit evaluation (indexer)". Idempotent (unique key,
 * ON CONFLICT DO NOTHING); one transaction with the work row locked; never changes the work state.
 */
@Component
public class EvaluateHitsHandler implements TaskHandler {

  public static final String TYPE = "EVALUATE_HITS";
  private static final Logger LOG = LoggerFactory.getLogger(EvaluateHitsHandler.class);

  private final IndexMapper index;
  private final SubscriptionMapper subscriptions;
  private final HitMapper hits;
  private final SearchMapper search;
  private final TransactionTemplate tx;
  private final HitProperties props;
  private final Clock clock;

  public EvaluateHitsHandler(IndexMapper index, SubscriptionMapper subscriptions, HitMapper hits,
      SearchMapper search, TransactionTemplate tx, HitProperties props, Clock clock) {
    this.index = index;
    this.subscriptions = subscriptions;
    this.hits = hits;
    this.search = search;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public void handle(Task task) throws Exception {
    long workId = FinderJson.mapper().readTree(task.payload()).path("workId").asLong();
    tx.executeWithoutResult(status -> evaluate(workId));
  }

  private void evaluate(long workId) {
    index.lockWork(workId);
    WorkFacts work = index.workFacts(workId);
    if (work == null || work.indexedAt() == null) return;
    List<WorkNameView> names = names(workId);
    Set<String> taxa = new LinkedHashSet<>();
    Set<String> nameIds = new LinkedHashSet<>();
    Set<String> groups = new LinkedHashSet<>();
    for (WorkNameView n : names) {
      taxa.addAll(List.of(n.taxonPath()));
      nameIds.add(String.valueOf(n.nameStringId()));
      if (n.taxgroup() != null) groups.addAll(TaxGroups.withAncestors(n.taxgroup()));
    }
    List<Subscription> candidates = subscriptions.candidates(
        work.sourceId() == null ? null : work.sourceId().toString(), new ArrayList<>(taxa), new ArrayList<>(nameIds),
        new ArrayList<>(groups));
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    WorkInfo info = new WorkInfo(workId, work.sourceId(), work.textLevel());
    int made = 0;
    for (Subscription s : candidates) {
      if (!s.created().isBefore(work.indexedAt())) continue;
      if (!HitWindow.contains(work.publicationDate(), work.year() == null ? null : work.year(), work.firstSeenAt(),
          s.sinceYear(), today, props)) continue;
      WorkFilter f = filter(s);
      if (f != null && !search.workMatches(workId, f)) continue;
      SubscriptionMatcher.Match m = SubscriptionMatcher.match(s, info, names);
      if (!m.matches()) continue;
      made += record(s, workId, m.names());
    }
    LOG.debug("Evaluated work {}: {} candidates, {} hits", workId, candidates.size(), made);
  }

  private int record(Subscription s, long workId, List<WorkNameView> matched) {
    Hit article = hits.find(s.id(), workId, "ARTICLE");
    if (article == null) {
      return hits.insertIfAbsent(new Hit(null, s.id(), workId, "ARTICLE", null, snapshot(matched, null).toString(),
          null));
    }
    Set<String> known = new HashSet<>();
    for (JsonNode n : FinderJson.mapper().readTree(article.names())) {
      for (JsonNode a : n.path("actTypes")) known.add(n.path("nameStringId").asLong() + "|" + a.asString());
    }
    ArrayNode fresh = snapshot(matched, known);
    if (fresh.isEmpty()) return 0;
    return hits.insertIfAbsent(new Hit(null, s.id(), workId, "NEW_NAMES", null, fresh.toString(), null));
  }

  /** The names as JSON; with {@code known} only the (name, act type) pairs not in it, a name with just those acts. */
  private static ArrayNode snapshot(List<WorkNameView> names, Set<String> known) {
    ArrayNode out = FinderJson.mapper().createArrayNode();
    for (WorkNameView n : names) {
      ArrayNode acts = FinderJson.mapper().createArrayNode();
      for (String a : n.actTypes()) {
        if (known == null || !known.contains(n.nameStringId() + "|" + a)) acts.add(a);
      }
      if (known != null && acts.isEmpty()) continue;
      ObjectNode o = out.addObject();
      o.put("nameStringId", n.nameStringId());
      o.put("name", n.name());
      o.set("actTypes", acts);
      o.put("placement", n.placementMethod());
      o.put("mainSubject", n.mainSubject());
    }
    return out;
  }

  /** Null for a subscription without filters (R3); otherwise the work filter of its keys, name options aside. */
  private static WorkFilter filter(Subscription s) {
    JsonNode f = FinderJson.mapper().readTree(s.filters());
    if (f == null || !f.isObject() || f.isEmpty()) return null;
    return new WorkFilter(null, null, text(f, "code"), text(f, "taxgroup"), text(f, "subfield"),
        text(f, "journalType"), text(f, "tier"), text(f, "oa"), text(f, "country"), null, null, null, null, null,
        false, null);
  }

  private static String text(JsonNode f, String key) {
    JsonNode v = f.get(key);
    return v == null || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }

  private List<WorkNameView> names(long workId) {
    List<WorkName> rows = index.workNames(workId);
    if (rows.isEmpty()) return List.of();
    var byId = new java.util.HashMap<Long, String>();
    for (NameString n : index.nameStrings(rows.stream().map(WorkName::nameStringId).toList())) {
      byId.put(n.id(), n.name());
    }
    List<WorkNameView> out = new ArrayList<>(rows.size());
    for (WorkName r : rows) {
      out.add(new WorkNameView(r.nameStringId(), byId.getOrDefault(r.nameStringId(), ""),
          r.taxonPath().toArray(String[]::new), r.taxgroup(), r.mainSubject(), r.onlyReference(),
          r.actTypes().toArray(String[]::new), r.placementMethod()));
    }
    return out;
  }
}
