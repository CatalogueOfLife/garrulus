package org.catalogueoflife.nametracker.store.work;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Finds or creates the single work row for a record arriving through OpenAlex, Crossref or both.
 *
 * <p>Merge rule: a row is found by openalex_id, else by (normalised) doi. If none exists it is inserted. Otherwise
 * columns that are null in the stored row are filled from the incoming one, and the bibliographic columns are
 * overwritten by non-null incoming values. origin, first_seen_at, state and text_level never change. Concurrent
 * inserts are resolved by INSERT ... ON CONFLICT DO NOTHING followed by a re-read and merge.
 */
public class WorkUpsert {

  /**
   * {@code abstractJatsAdded}: the stored work had no abstract_jats, the incoming one has, and the work is already past
   * DISCOVERED, so the content resolution that ran before has not seen it and should run again.
   */
  public record UpsertResult(long id, boolean created, boolean abstractJatsAdded) {
  }

  private final WorkMapper mapper;

  public WorkUpsert(WorkMapper mapper) {
    this.mapper = mapper;
  }

  /** Lower-cases and strips https://doi.org/, http://dx.doi.org/ (and the other resolver forms) and doi:. */
  public static @Nullable String normalizeDoi(@Nullable String doi) {
    if (doi == null) {
      return null;
    }
    String d = doi.strip().toLowerCase(Locale.ROOT);
    for (String prefix : new String[] {"https://doi.org/", "http://doi.org/", "https://dx.doi.org/",
        "http://dx.doi.org/", "doi:"}) {
      if (d.startsWith(prefix)) {
        d = d.substring(prefix.length()).strip();
        break;
      }
    }
    return d.isEmpty() ? null : d;
  }

  public UpsertResult upsert(Work in) {
    Work incoming = withDoi(in, normalizeDoi(in.doi()));
    if (incoming.openalexId() == null && incoming.doi() == null) {
      throw new IllegalArgumentException("A work needs an openalex id or a doi");
    }
    for (int i = 0; i < 5; i++) {
      Work existing = find(incoming);
      if (existing == null) {
        Long id = mapper.insertIfAbsent(incoming);
        if (id != null) {
          return new UpsertResult(id, true, false);
        }
        // Lost a race against a concurrent insert: re-read and merge.
        continue;
      }
      mapper.merge(existing.id(), incoming);
      boolean jatsAdded = existing.abstractJats() == null && incoming.abstractJats() != null
          && !"DISCOVERED".equals(existing.state());
      return new UpsertResult(existing.id(), false, jatsAdded);
    }
    throw new IllegalStateException("Could not upsert work " + incoming.openalexId() + " / " + incoming.doi());
  }

  private @Nullable Work find(Work w) {
    Work found = w.openalexId() == null ? null : mapper.findByOpenalexId(w.openalexId());
    if (found == null && w.doi() != null) {
      found = mapper.findByDoi(w.doi());
    }
    return found;
  }

  private static Work withDoi(Work w, @Nullable String doi) {
    return new Work(w.id(), w.openalexId(), doi, w.sourceId(), w.title(), w.publicationDate(), w.year(), w.volume(),
        w.issue(), w.pages(), w.authors(), w.type(), w.language(), w.oaStatus(), w.license(), w.bestOaUrl(),
        w.abstractText(), w.abstractSource(), w.origin(), w.state(), w.textLevel(), w.codes(), w.taxgroups(),
        w.hasActs(), w.firstSeenAt(), w.modified(), w.abstractJats(), w.pmcid(), w.openalexHasTei(),
        w.openalexCheckedAt());
  }
}
