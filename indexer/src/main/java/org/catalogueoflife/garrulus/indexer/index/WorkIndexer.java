package org.catalogueoflife.garrulus.indexer.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.salience.Salience;
import org.catalogueoflife.garrulus.finder.salience.SalienceInputs;
import org.catalogueoflife.garrulus.finder.salience.SalienceInputs.MentionView;
import org.catalogueoflife.garrulus.finder.salience.SalienceProperties;
import org.catalogueoflife.garrulus.indexer.match.PlacedWork;
import org.catalogueoflife.garrulus.indexer.match.Placement;
import org.catalogueoflife.garrulus.indexer.match.TaxGroups;
import org.catalogueoflife.garrulus.indexer.match.WorkIndexing;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.IndexRows;
import org.catalogueoflife.garrulus.store.index.MentionRow;
import org.catalogueoflife.garrulus.store.index.NameUbiquity;
import org.catalogueoflife.garrulus.store.index.WorkFacts;
import org.catalogueoflife.garrulus.store.index.WorkName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggregates the mentions of a work into {@code work_name} rows with salience and main-subject flag, fills the work's
 * codes, taxgroups and has_acts (acts outside the references only; {@code act_types} keeps them all) and moves it to
 * INDEXED, placed in the given release. One transaction that locks the work row first, so a redelivery replaces the
 * rows and gives identical ones. Under that lock the row set the placements were computed from must still be the one
 * to index ({@link IndexRows}); if a newer finder run or a swap came in between, nothing is written. A work that is
 * already listed (a re-index, with or without a pending index) first records the subscriptions its live index matches
 * ({@link PreviousMatches}), placed in the release being indexed: if the live rows are placed in another release (an
 * XR switch came in between), they are re-placed in this one first, so the switch makes no follow look new. A pending
 * index is then swapped in, in the same transaction: readers see either the old or the new index, never a gap.
 */
@Component
public class WorkIndexer implements WorkIndexing {

  static final int CHUNK = 1000;
  /** The states of a work the public API lists. */
  static final Set<String> LISTED = Set.of("INDEXED", "NOTIFIED");
  private static final Logger LOG = LoggerFactory.getLogger(WorkIndexer.class);

  private final IndexMapper index;
  private final Salience salience;
  private final PreviousMatches previous;
  private final TransactionTemplate tx;

  public WorkIndexer(IndexMapper index, SalienceProperties props, PreviousMatches previous, TransactionTemplate tx) {
    this.index = index;
    this.salience = new Salience(props);
    this.previous = previous;
    this.tx = tx;
  }

  @Override
  public Outcome index(long workId, PlacedWork placed, int releaseKey) {
    return tx.execute(status -> doIndex(workId, placed, releaseKey));
  }

  private Outcome doIndex(long workId, PlacedWork placed, int releaseKey) {
    index.lockWork(workId);
    if (!stillCurrent(workId, releaseKey)) return Outcome.RELEASE_CHANGED;
    IndexRows rows = index.indexRows(workId);
    if (rows == null || !rows.equals(placed.rows())) {
      LOG.debug("Work {} not indexed: placed {}, but its index is now {}", workId, placed.rows(), rows);
      return Outcome.STALE;
    }
    Map<Long, Placement> placements = placed.placements();
    Aggregate a = null;
    WorkFacts f = index.workFacts(workId);
    if (f != null && LISTED.contains(f.state())) {
      // the live index decides which follows the new one alerts: what it matches, placed in this release
      Integer placedIn = index.placedRelease(workId);
      if (placedIn == null || placedIn != releaseKey) {
        if (!rows.pending()) {
          a = writeWorkNames(workId, placements); // the live rows are the ones to index
        } else {
          PlacedWork.LiveIndex live = placed.live();
          if (live == null || !live.rows().equals(index.liveIndexRows(workId))) {
            LOG.debug("Work {} not indexed: its live index {} is not the one placed in release {}", workId,
                index.liveIndexRows(workId), releaseKey);
            return Outcome.LIVE_CHANGED;
          }
          writeWorkNames(workId, live.placements());
        }
      }
      index.setReindexPrevSubscriptions(workId, previous.capture(workId));
    }
    if (rows.pending()) index.swapPendingIndex(workId);
    if (a == null) a = writeWorkNames(workId, placements);
    index.setIndexed(workId, a.codes(), a.taxgroups(), a.hasActs(), releaseKey);
    return Outcome.INDEXED;
  }

  /**
   * True if the release is still the current one. Takes a share lock on the current release row until the end of the
   * transaction (lock order: work, then release; no path locks a release and then a work), so the flip of an XR switch
   * ({@code lockReleases}, FOR UPDATE) waits for in-flight indexing, and indexing that starts during the flip sees
   * the new current release afterwards: no work is placed in the old release once the flip has committed.
   */
  private boolean stillCurrent(long workId, int releaseKey) {
    Integer current = index.lockCurrentReleaseKey();
    if (current != null && current == releaseKey) return true;
    LOG.debug("Work {} not written: placed in XR release {}, but the current release is {}", workId, releaseKey,
        current);
    return false;
  }

  /**
   * Re-places a listed work in another XR release (the XR switch): the same aggregate and {@code work_name} rewrite as
   * {@link #index}, then codes, taxgroups, has_acts and {@code placed_release}; state, {@code indexed_at} and
   * {@code first_indexed_at} stay, and nothing follows (no hit evaluation). One transaction, work row locked first.
   * Skipped (false) when the release is no longer the current one, the work is not INDEXED or NOTIFIED, has a pending
   * index (its MATCH_NAMES places it in the current release anyway), or its index is no longer the row set the
   * placements were computed from.
   */
  public boolean replace(long workId, PlacedWork placed, int releaseKey) {
    return Boolean.TRUE.equals(tx.execute(status -> doReplace(workId, placed, releaseKey)));
  }

  private boolean doReplace(long workId, PlacedWork placed, int releaseKey) {
    index.lockWork(workId);
    if (!stillCurrent(workId, releaseKey)) return false;
    WorkFacts f = index.workFacts(workId);
    if (f == null || !LISTED.contains(f.state())) {
      LOG.debug("Work {} not re-placed: it is {}", workId, f == null ? "gone" : f.state());
      return false;
    }
    IndexRows rows = index.indexRows(workId);
    if (rows == null || rows.pending() || !rows.equals(placed.rows())) {
      LOG.debug("Work {} not re-placed: placed {}, but its index is now {}", workId, placed.rows(), rows);
      return false;
    }
    Aggregate a = writeWorkNames(workId, placed.placements());
    index.setPlacement(workId, a.codes(), a.taxgroups(), a.hasActs(), releaseKey);
    return true;
  }

  /** Replaces the work's work_name rows by those of the aggregate; returns it. The caller holds the work lock. */
  private Aggregate writeWorkNames(long workId, Map<Long, Placement> placements) {
    Aggregate a = aggregate(workId, placements);
    index.deleteWorkNames(workId);
    for (int i = 0; i < a.rows().size(); i += CHUNK) {
      index.insertWorkNames(a.rows().subList(i, Math.min(a.rows().size(), i + CHUNK)));
    }
    return a;
  }

  /** The work_name rows of a work and its derived work columns; codes and taxgroups are sorted. */
  public record Aggregate(List<WorkName> rows, String[] codes, String[] taxgroups, boolean hasActs) {}

  /**
   * Computes the work_name rows (salience, main subject) and the work's codes, taxgroups and has_acts from the stored
   * mentions, the current name_ubiquity and the given placements. Abstract-only is the text level the live mentions were
   * found at ({@link IndexMapper#namesTextLevel}). Reads only; the caller holds the work lock and writes. Shared by
   * indexing and {@code RESCORE_SALIENCE}.
   */
  public Aggregate aggregate(long workId, Map<Long, Placement> placements) {
    boolean abstractOnly = "ABSTRACT_ONLY".equals(index.namesTextLevel(workId));
    Map<Long, List<MentionRow>> byName = new LinkedHashMap<>();
    boolean hasActs = false;
    for (MentionRow m : index.mentions(workId)) {
      byName.computeIfAbsent(m.nameStringId(), k -> new ArrayList<>()).add(m);
      if (m.actType() != null && !"REFERENCE".equals(m.blockType())) hasActs = true; // a cited act is not this work's
    }
    // SalienceInputs keys by string: the name string id
    List<MentionView> views = new ArrayList<>();
    for (List<MentionRow> ms : byName.values()) {
      for (MentionRow m : ms) views.add(new MentionView(String.valueOf(m.nameStringId()), m.blockType(), m.actType()));
    }
    Map<String, Double> ubiquity = new HashMap<>();
    List<Long> ids = new ArrayList<>(byName.keySet());
    for (int i = 0; i < ids.size(); i += CHUNK) {
      for (NameUbiquity u : index.ubiquity(ids.subList(i, Math.min(ids.size(), i + CHUNK)))) {
        ubiquity.put(String.valueOf(u.nameStringId()), (double) u.share());
      }
    }
    Map<String, Salience.Inputs> inputs = SalienceInputs.of(views, abstractOnly, ubiquity);

    List<WorkName> rows = new ArrayList<>();
    Set<String> codes = new LinkedHashSet<>();
    Set<String> groups = new LinkedHashSet<>();
    for (Map.Entry<Long, List<MentionRow>> e : byName.entrySet()) {
      List<MentionRow> ms = e.getValue();
      int outsideRefs = outsideReferences(ms);
      int body = abstractOnly ? 0 : bodyCount(ms); // an abstract-only work has no body text, whatever its blocks
      Salience.Inputs in = inputs.get(String.valueOf(e.getKey()));
      boolean inTitle = in.inTitle(), inAbstract = in.inAbstract(), inHeading = in.inHeading();
      Set<String> acts = new LinkedHashSet<>();
      MentionRow title = null, abs = null, firstBody = null;
      for (MentionRow m : ms) {
        if (m.actType() != null) acts.add(m.actType());
        if ("REFERENCE".equals(m.blockType())) continue;
        if (firstBody == null) firstBody = m;
        if (title == null && "TITLE".equals(m.blockType())) title = m;
        if (abs == null && "ABSTRACT".equals(m.blockType())) abs = m;
      }
      MentionRow best = title != null ? title : abs != null ? abs : firstBody != null ? firstBody : ms.get(0);
      double s = salience.score(in);
      boolean main = outsideRefs > 0 && salience.isMainSubject(s);
      Placement p = placements.get(e.getKey());
      if (p == null) p = new Placement(Placement.NONE, null, List.of(), null, null);
      if (main) {
        if (p.code() != null) codes.add(p.code());
        if (p.taxgroup() != null) TaxGroups.addWithAncestors(p.taxgroup(), groups);
      }
      rows.add(new WorkName(workId, e.getKey(), ms.size(), body, inTitle, inAbstract, inHeading, outsideRefs == 0,
          new ArrayList<>(acts), best.snippet(), p.method(), p.usageId(), p.taxonPath(), p.code(), p.taxgroup(),
          (float) s, main));
    }
    return new Aggregate(rows, codes.stream().sorted().toArray(String[]::new),
        groups.stream().sorted().toArray(String[]::new), hasActs);
  }

  /** Mentions outside the references: none means the name is cited only (only_reference, never a main subject). */
  private static int outsideReferences(List<MentionRow> ms) {
    int n = 0;
    for (MentionRow m : ms) if (!"REFERENCE".equals(m.blockType())) n++;
    return n;
  }

  /** The block types that are not body text: the mentioned-in filter's BODY is body_count &gt; 0 or in_heading. */
  static final Set<String> NOT_BODY = Set.of("TITLE", "ABSTRACT", "HEADING", "REFERENCE");

  /** Mentions in the body text (paragraphs, captions, table cells, footnotes): not title, abstract, headings, references. */
  static int bodyCount(List<MentionRow> ms) {
    int n = 0;
    for (MentionRow m : ms) if (!NOT_BODY.contains(m.blockType())) n++;
    return n;
  }
}
