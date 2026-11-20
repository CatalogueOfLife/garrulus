package org.catalogueoflife.nametracker.indexer.link;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.jspecify.annotations.Nullable;

/**
 * A work or a journal a lookup is asked about. {@code ids} holds what is known by scheme (a work's identifiers; a
 * journal's ISSN, ISSN_L, OPENALEX columns and its stored lookup identifiers), {@code issns} the ISSN-L first and the
 * ISSNs of the work's journal or of the journal itself, {@code sourceId} the journal (a source's own id),
 * {@code plaziOrBlrJournal} whether the journal has a PLAZI discovery signal or a BLR sighting.
 */
public record LinkTarget(IdScheme.Entity entity, long id, @Nullable String doi, List<String> issns,
    Map<String, List<String>> ids, boolean hasActs, @Nullable Long sourceId, boolean plaziOrBlrJournal) {

  public LinkTarget(IdScheme.Entity entity, long id, @Nullable String doi, List<String> issns,
      Map<String, List<String>> ids, boolean hasActs, @Nullable Long sourceId) {
    this(entity, id, doi, issns, ids, hasActs, sourceId, false);
  }

  /** The first value of the scheme, or null. */
  public @Nullable String first(String scheme) {
    List<String> v = ids.get(scheme);
    return v == null || v.isEmpty() ? null : v.get(0);
  }

  public boolean has(String scheme) {
    return first(scheme) != null;
  }
}
