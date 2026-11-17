package org.catalogueoflife.nametracker.indexer.hits;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.match.TaxGroups;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.NameString;
import org.catalogueoflife.nametracker.store.index.WorkFacts;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The subscriptions that match a work's live index: candidates by source, taxa, names and taxon groups, then the
 * subscription's filters and {@link SubscriptionMatcher}. No {@code created}, window or paused check beyond what the
 * candidate query does: those belong to hit evaluation. Reads through the caller's transaction.
 */
@Component
public class HitMatcher {

  /** A matching subscription with the names that made it match. */
  public record Matched(Subscription s, SubscriptionMatcher.Match m) {}

  private final IndexMapper index;
  private final SubscriptionMapper subscriptions;
  private final SearchMapper search;

  public HitMatcher(IndexMapper index, SubscriptionMapper subscriptions, SearchMapper search) {
    this.index = index;
    this.subscriptions = subscriptions;
    this.search = search;
  }

  public List<Matched> matching(long workId) {
    return matching(workId, false);
  }

  /** As {@link #matching(long)}, with paused subscriptions too when {@code includePaused}. */
  public List<Matched> matching(long workId, boolean includePaused) {
    return matching(workId, includePaused, null);
  }

  /**
   * As {@link #matching(long, boolean)}, with the work judged at the given text level instead of its current one
   * (null: the current one).
   */
  public List<Matched> matching(long workId, boolean includePaused, @Nullable String textLevel) {
    WorkFacts work = index.workFacts(workId);
    if (work == null) return List.of();
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
        new ArrayList<>(groups), includePaused);
    WorkInfo info = new WorkInfo(workId, work.sourceId(), textLevel != null ? textLevel : work.textLevel());
    List<Matched> out = new ArrayList<>();
    for (Subscription s : candidates) {
      WorkFilter f = filter(s);
      if (f != null && !search.workMatches(workId, f)) continue;
      SubscriptionMatcher.Match m = SubscriptionMatcher.match(s, info, names);
      if (m.matches()) out.add(new Matched(s, m));
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
