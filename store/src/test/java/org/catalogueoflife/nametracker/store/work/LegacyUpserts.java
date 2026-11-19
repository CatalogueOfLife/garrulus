package org.catalogueoflife.nametracker.store.work;

import java.util.List;
import java.util.Optional;

/**
 * The single-argument upserts of the layers before M6a, for tests that build works by hand: layer OPENALEX when the
 * work has an OpenAlex id, else CROSSREF; no extra identifiers; an abstract_jats without a source is Crossref's.
 */
public final class LegacyUpserts {

  private LegacyUpserts() {
  }

  public static WorkUpsert.UpsertResult legacyUpsert(WorkUpsert upsert, Work in) {
    return upsert.upsert(withJatsSource(in), layer(in), List.of());
  }

  public static Optional<Long> legacyInsertIfNew(WorkUpsert upsert, Work in) {
    return upsert.insertIfNew(withJatsSource(in), layer(in), List.of());
  }

  private static Layer layer(Work w) {
    return w.openalexId() != null ? Layer.OPENALEX : Layer.CROSSREF;
  }

  private static Work withJatsSource(Work w) {
    if (w.abstractJats() == null || w.abstractJatsSource() != null) {
      return w;
    }
    return new Work(w.id(), w.openalexId(), w.doi(), w.sourceId(), w.title(), w.publicationDate(), w.year(),
        w.volume(), w.issue(), w.pages(), w.authors(), w.type(), w.language(), w.oaStatus(), w.license(),
        w.bestOaUrl(), w.abstractText(), w.abstractSource(), w.origin(), w.state(), w.textLevel(), w.codes(),
        w.taxgroups(), w.hasActs(), w.firstSeenAt(), w.modified(), w.abstractJats(), w.pmcid(), w.openalexHasTei(),
        w.openalexCheckedAt(), w.metaLayer(), "CROSSREF", w.accessLayer());
  }
}
