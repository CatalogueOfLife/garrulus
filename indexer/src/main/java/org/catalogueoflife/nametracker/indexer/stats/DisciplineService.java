package org.catalogueoflife.nametracker.indexer.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.openalex.OpenAlexClassifications;
import org.catalogueoflife.nametracker.indexer.stats.DisciplineDeriver.Derived;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.discipline.ClassificationRawMapper;
import org.catalogueoflife.nametracker.store.discipline.Discipline;
import org.catalogueoflife.nametracker.store.discipline.DisciplineMapper;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Derives and writes the disciplines of sources from their raw classifications; admin-set ones are never touched. */
@Component
public class DisciplineService {

  private static final Logger LOG = LoggerFactory.getLogger(DisciplineService.class);

  private final SourceMapper sources;
  private final ClassificationRawMapper raw;
  private final DisciplineMapper disciplines;
  private final SubscriptionMapper subscriptions;

  public DisciplineService(SourceMapper sources, ClassificationRawMapper raw, DisciplineMapper disciplines,
      SubscriptionMapper subscriptions) {
    this.sources = sources;
    this.raw = raw;
    this.disciplines = disciplines;
    this.subscriptions = subscriptions;
  }

  /** Stores the subfields and top topics of an OpenAlex source entity as raw rows, then derives; one transaction. */
  @Transactional
  public void applyOpenAlex(long sourceId, JsonNode openAlexSource) {
    for (Map.Entry<String, List<RawClassification>> e : OpenAlexClassifications.raw(openAlexSource).entrySet()) {
      raw.replace(sourceId, e.getKey(), e.getValue());
    }
    apply(sourceId);
  }

  /** Re-derives one source; false when an admin set its disciplines or it is unknown. */
  public boolean apply(long sourceId) {
    return apply(sourceId, deriver());
  }

  /** Re-derives every source not set by an admin, then rewrites the follows of retired disciplines. */
  public int applyAll() {
    Map<String, String> replacements = disciplines.replacements();
    DisciplineDeriver deriver = new DisciplineDeriver(disciplines.mappings(), replacements);
    int n = 0;
    for (long id : sources.listDerivedDisciplineIds()) {
      if (apply(id, deriver)) n++;
    }
    int followed = 0;
    for (Map.Entry<String, String> e : replacements.entrySet()) {
      String to = Discipline.resolve(replacements, e.getKey());
      if (to.equals(e.getKey())) continue; // a cycle or self-reference: nothing to rewrite to
      followed += subscriptions.rewriteDiscipline(e.getKey(), to,
          "The discipline " + e.getKey() + " is now " + to + ".");
    }
    LOG.info("Derived the disciplines of {} sources, rewrote {} follows of retired disciplines", n, followed);
    return n;
  }

  private DisciplineDeriver deriver() {
    return new DisciplineDeriver(disciplines.mappings(), disciplines.replacements());
  }

  private boolean apply(long sourceId, DisciplineDeriver deriver) {
    Source s = sources.get(sourceId);
    if (s == null) return false;
    Derived d = deriver.derive(raw.forSource(sourceId), taxgroupShares(s));
    return sources.updateDisciplines(sourceId, d.codes(), d.primary(), d.source()) > 0;
  }

  private static Map<String, Double> taxgroupShares(Source s) {
    Map<String, Double> out = new LinkedHashMap<>();
    if (s.taxgroupShares() != null && !s.taxgroupShares().isBlank()) {
      FinderJson.mapper().readTree(s.taxgroupShares()).properties()
          .forEach(e -> out.put(e.getKey(), e.getValue().asDouble(0)));
    }
    return out;
  }
}
