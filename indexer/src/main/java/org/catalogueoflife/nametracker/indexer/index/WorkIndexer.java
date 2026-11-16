package org.catalogueoflife.nametracker.indexer.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.salience.Salience;
import org.catalogueoflife.nametracker.finder.salience.SalienceInputs;
import org.catalogueoflife.nametracker.finder.salience.SalienceInputs.MentionView;
import org.catalogueoflife.nametracker.finder.salience.SalienceProperties;
import org.catalogueoflife.nametracker.indexer.match.Placement;
import org.catalogueoflife.nametracker.indexer.match.TaxGroups;
import org.catalogueoflife.nametracker.indexer.match.WorkIndexing;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.index.NameUbiquity;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggregates the mentions of a work into {@code work_name} rows with salience and main-subject flag, fills the work's
 * codes, taxgroups and has_acts (acts outside the references only; {@code act_types} keeps them all) and moves it to
 * INDEXED. One transaction that locks the work row first, so a redelivery
 * replaces the rows and gives identical ones.
 */
@Component
public class WorkIndexer implements WorkIndexing {

  static final int CHUNK = 1000;

  private final IndexMapper index;
  private final Salience salience;
  private final TransactionTemplate tx;

  public WorkIndexer(IndexMapper index, SalienceProperties props, TransactionTemplate tx) {
    this.index = index;
    this.salience = new Salience(props);
    this.tx = tx;
  }

  @Override
  public void index(long workId, Map<Long, Placement> byNameStringId) {
    tx.executeWithoutResult(status -> doIndex(workId, byNameStringId));
  }

  private void doIndex(long workId, Map<Long, Placement> placements) {
    index.lockWork(workId);
    Aggregate a = aggregate(workId, placements);
    index.deleteWorkNames(workId);
    for (int i = 0; i < a.rows().size(); i += CHUNK) {
      index.insertWorkNames(a.rows().subList(i, Math.min(a.rows().size(), i + CHUNK)));
    }
    index.setIndexed(workId, a.codes(), a.taxgroups(), a.hasActs());
  }

  /** The work_name rows of a work and its derived work columns; codes and taxgroups are sorted. */
  public record Aggregate(List<WorkName> rows, String[] codes, String[] taxgroups, boolean hasActs) {}

  /**
   * Computes the work_name rows (salience, main subject) and the work's codes, taxgroups and has_acts from the stored
   * mentions, the current name_ubiquity and the given placements. Reads only; the caller holds the work lock and
   * writes. Shared by indexing and {@code RESCORE_SALIENCE}.
   */
  public Aggregate aggregate(long workId, Map<Long, Placement> placements) {
    boolean abstractOnly = "ABSTRACT_ONLY".equals(index.textLevel(workId));
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
      int body = bodyCount(ms);
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
      boolean main = body > 0 && salience.isMainSubject(s);
      Placement p = placements.get(e.getKey());
      if (p == null) p = new Placement(Placement.NONE, null, List.of(), null, null);
      if (main) {
        if (p.code() != null) codes.add(p.code());
        if (p.taxgroup() != null) TaxGroups.addWithAncestors(p.taxgroup(), groups);
      }
      rows.add(new WorkName(workId, e.getKey(), ms.size(), body, inTitle, inAbstract, inHeading, body == 0,
          new ArrayList<>(acts), best.snippet(), p.method(), p.usageId(), p.taxonPath(), p.code(), p.taxgroup(),
          (float) s, main));
    }
    return new Aggregate(rows, codes.stream().sorted().toArray(String[]::new),
        groups.stream().sorted().toArray(String[]::new), hasActs);
  }

  private static int bodyCount(List<MentionRow> ms) {
    int n = 0;
    for (MentionRow m : ms) if (!"REFERENCE".equals(m.blockType())) n++;
    return n;
  }
}
