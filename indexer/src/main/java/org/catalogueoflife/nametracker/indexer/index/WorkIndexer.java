package org.catalogueoflife.nametracker.indexer.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.indexer.match.Placement;
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
    boolean abstractOnly = "ABSTRACT_ONLY".equals(index.textLevel(workId));
    Map<Long, List<MentionRow>> byName = new LinkedHashMap<>();
    boolean hasActs = false;
    for (MentionRow m : index.mentions(workId)) {
      byName.computeIfAbsent(m.nameStringId(), k -> new ArrayList<>()).add(m);
      if (m.actType() != null && !"REFERENCE".equals(m.blockType())) hasActs = true; // a cited act is not this work's
    }
    Map<Long, Double> ubiquity = new HashMap<>();
    List<Long> ids = new ArrayList<>(byName.keySet());
    for (int i = 0; i < ids.size(); i += CHUNK) {
      for (NameUbiquity u : index.ubiquity(ids.subList(i, Math.min(ids.size(), i + CHUNK)))) {
        ubiquity.put(u.nameStringId(), (double) u.share());
      }
    }
    int maxBody = 0;
    for (List<MentionRow> ms : byName.values()) maxBody = Math.max(maxBody, bodyCount(ms));

    List<WorkName> rows = new ArrayList<>();
    Set<String> codes = new LinkedHashSet<>();
    Set<String> groups = new LinkedHashSet<>();
    for (Map.Entry<Long, List<MentionRow>> e : byName.entrySet()) {
      List<MentionRow> ms = e.getValue();
      int body = bodyCount(ms);
      boolean inTitle = false, inAbstract = false, inHeading = false;
      Set<String> acts = new LinkedHashSet<>();
      boolean bodyAct = false;
      MentionRow title = null, abs = null, firstBody = null;
      for (MentionRow m : ms) {
        boolean ref = "REFERENCE".equals(m.blockType());
        if (m.actType() != null) acts.add(m.actType());
        if (ref) continue;
        if (m.actType() != null) bodyAct = true;
        if (firstBody == null) firstBody = m;
        switch (m.blockType()) {
          case "TITLE" -> {
            inTitle = true;
            if (title == null) title = m;
          }
          case "ABSTRACT" -> {
            inAbstract = true;
            if (abs == null) abs = m;
          }
          case "HEADING" -> inHeading = true;
          default -> { }
        }
      }
      if (abstractOnly && body > 0) inAbstract = true;
      MentionRow best = title != null ? title : abs != null ? abs : firstBody != null ? firstBody : ms.get(0);
      double s = salience.score(new Salience.Inputs(inTitle, inAbstract, bodyAct, inHeading, body, maxBody,
          ubiquity.getOrDefault(e.getKey(), 0.0)));
      boolean main = body > 0 && salience.isMainSubject(s);
      Placement p = placements.get(e.getKey());
      if (p == null) p = new Placement(Placement.NONE, null, List.of(), null, null);
      if (main) {
        if (p.code() != null) codes.add(p.code());
        if (p.taxgroup() != null) addGroups(p.taxgroup(), groups);
      }
      rows.add(new WorkName(workId, e.getKey(), ms.size(), body, inTitle, inAbstract, inHeading, body == 0,
          new ArrayList<>(acts), best.snippet(), p.method(), p.usageId(), p.taxonPath(), p.code(), p.taxgroup(),
          (float) s, main));
    }
    index.deleteWorkNames(workId);
    for (int i = 0; i < rows.size(); i += CHUNK) {
      index.insertWorkNames(rows.subList(i, Math.min(rows.size(), i + CHUNK)));
    }
    index.setIndexed(workId, codes.stream().sorted().toArray(String[]::new),
        groups.stream().sorted().toArray(String[]::new), hasActs);
  }

  private static int bodyCount(List<MentionRow> ms) {
    int n = 0;
    for (MentionRow m : ms) if (!"REFERENCE".equals(m.blockType())) n++;
    return n;
  }

  /** The group and all its ancestors. */
  private static void addGroups(String name, Set<String> out) {
    try {
      TaxGroup g = TaxGroup.valueOf(name);
      out.add(g.name());
      for (TaxGroup a : g.classification()) out.add(a.name());
    } catch (IllegalArgumentException e) {
      out.add(name);
    }
  }
}
