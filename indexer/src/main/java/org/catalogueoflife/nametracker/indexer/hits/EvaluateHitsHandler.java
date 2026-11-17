package org.catalogueoflife.nametracker.indexer.hits;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.store.account.HitMapper;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.WorkFacts;
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
  private final HitMatcher matcher;
  private final HitMapper hits;
  private final TransactionTemplate tx;
  private final HitProperties props;
  private final Clock clock;

  public EvaluateHitsHandler(IndexMapper index, HitMatcher matcher, HitMapper hits,
      TransactionTemplate tx, HitProperties props, Clock clock) {
    this.index = index;
    this.matcher = matcher;
    this.hits = hits;
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
    if (work == null || work.indexedAt() == null || !"INDEXED".equals(work.state())) return; // a stale task
    Set<Long> previous = new HashSet<>(index.reindexPrevSubscriptions(workId));
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    List<HitMatcher.Matched> matched = matcher.matching(workId);
    int made = 0;
    for (HitMatcher.Matched h : matched) {
      Subscription s = h.s();
      if (!s.created().isBefore(work.indexedAt())) continue;
      if (!HitWindow.contains(work.publicationDate(), work.year() == null ? null : work.year(), work.firstSeenAt(),
          s.sinceYear(), today, props)) continue;
      made += record(s, workId, h.m().names(), previous.contains(s.id()));
    }
    LOG.debug("Evaluated work {}: {} matching, {} hits", workId, matched.size(), made);
  }

  /** {@code matchedBefore}: the follow matched the work's previous index, so a re-index gives it no new ARTICLE. */
  private int record(Subscription s, long workId, List<WorkNameView> matched, boolean matchedBefore) {
    Hit article = hits.find(s.id(), workId, "ARTICLE");
    if (article == null) {
      if (matchedBefore) return 0;
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
}
