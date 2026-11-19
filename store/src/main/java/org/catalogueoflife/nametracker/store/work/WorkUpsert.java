package org.catalogueoflife.nametracker.store.work;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.jspecify.annotations.Nullable;

/**
 * Finds or creates the single work row for a record arriving through one of the harvest layers.
 *
 * <p>Matching order, first hit wins: the record's DOI, its OpenAlex id, any other of its identifiers, then a title
 * match (same source, same year, same {@link TitleKey}, the same first-author family name when both have authors,
 * never between two different DOIs). A found row is locked ({@code FOR UPDATE}, so run the upsert inside the caller's
 * transaction) and merged field by field by layer precedence ({@link WorkMerger}); otherwise the record is inserted.
 * Every upsert then adds the record's identifiers and one sighting per layer. The work's DOI, OpenAlex id and PMCID
 * columns are mirrored into the identifiers and filled from them. Concurrent inserts are resolved by INSERT ... ON
 * CONFLICT DO NOTHING on the unique columns followed by a re-read and merge. The DOI is normalised and the title
 * cleaned of markup and entities ({@link TitleText}) before either.
 */
public class WorkUpsert {

  /**
   * {@code abstractJatsAdded}: the stored work had no abstract_jats, the incoming one has, and the work is already past
   * DISCOVERED, so the content resolution that ran before has not seen it and should run again. {@code duplicateOf}:
   * another work that holds one of the incoming identifiers (the lowest such id); the caller enqueues MERGE_WORKS.
   */
  public record UpsertResult(long id, boolean created, boolean abstractJatsAdded, @Nullable Long duplicateOf) {
  }

  private static final List<String> FIRST_SCHEMES = List.of(IdScheme.DOI.code(), IdScheme.OPENALEX.code());
  private static final int TRIES = 5;

  private final WorkMapper mapper;
  private final WorkIdentifierMapper identifiers;
  private final WorkSightingMapper sightings;

  public WorkUpsert(WorkMapper mapper, WorkIdentifierMapper identifiers, WorkSightingMapper sightings) {
    this.mapper = mapper;
    this.identifiers = identifiers;
    this.sightings = sightings;
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

  /**
   * Finds the work of the record (see the matching order) and merges the record into it, or inserts it. Writes the
   * identifiers and the layer's sighting. Throws IllegalArgumentException for a record without any identifier.
   */
  public UpsertResult upsert(Work in, Layer layer, List<Identifier> ids) {
    List<Identifier> all = identifiers(in, ids);
    Work incoming = normalized(in, all);
    for (int i = 0; i < TRIES; i++) {
      Long found = find(incoming, all);
      if (found == null) {
        Long id = mapper.insertIfAbsent(claimed(incoming, layer));
        if (id == null) {
          continue; // lost a race against a concurrent insert: re-read and merge
        }
        return new UpsertResult(id, true, false, record(id, incoming, layer, all));
      }
      Work existing = mapper.findForUpdate(found);
      if (existing == null) {
        continue; // merged away in the meantime
      }
      Work stored = withoutEmptyAuthors(existing);
      WorkMerger.Merged merged = WorkMerger.merge(stored, incoming, layer);
      if (merged.changed()) {
        mapper.updateMerged(merged.work());
      }
      boolean jatsAdded = blank(stored.abstractJats()) && !blank(merged.work().abstractJats())
          && !"DISCOVERED".equals(stored.state());
      return new UpsertResult(found, false, jatsAdded, record(found, incoming, layer, all));
    }
    throw new IllegalStateException("Could not upsert work " + all);
  }

  /**
   * Inserts the record only when it matches no work (see the matching order), writes its identifiers and sighting and
   * returns the new id; an existing work is never touched. Losing a race against a concurrent insert also returns
   * empty.
   */
  public Optional<Long> insertIfNew(Work in, Layer layer, List<Identifier> ids) {
    List<Identifier> all = identifiers(in, ids);
    Work incoming = normalized(in, all);
    if (find(incoming, all) != null) {
      return Optional.empty();
    }
    Long id = mapper.insertIfAbsent(claimed(incoming, layer));
    if (id == null) {
      return Optional.empty();
    }
    record(id, incoming, layer, all);
    return Optional.of(id);
  }

  /**
   * The upsert of the layers before M6a: layer OPENALEX when the work has an OpenAlex id, else CROSSREF; identifiers
   * from its DOI, OpenAlex id and PMCID; an abstract_jats without a source is Crossref's.
   *
   * @deprecated use {@link #upsert(Work, Layer, List)}.
   */
  @Deprecated(forRemoval = true)
  public UpsertResult upsert(Work in) {
    return upsert(legacy(in), legacyLayer(in), List.of());
  }

  /**
   * The backfill insert before M6a, see {@link #upsert(Work)}.
   *
   * @deprecated use {@link #insertIfNew(Work, Layer, List)}.
   */
  @Deprecated(forRemoval = true)
  public Optional<Long> insertIfNew(Work in) {
    return insertIfNew(legacy(in), legacyLayer(in), List.of());
  }

  private static Layer legacyLayer(Work w) {
    return w.openalexId() != null ? Layer.OPENALEX : Layer.CROSSREF;
  }

  private static Work legacy(Work w) {
    if (w.abstractJats() == null || w.abstractJatsSource() != null) {
      return w;
    }
    return copy(w, w.openalexId(), w.doi(), w.pmcid(), w.title(), w.authors(), "CROSSREF", w.metaLayer(),
        w.accessLayer());
  }

  /** Writes the identifiers and the sighting of the work; returns another work holding one of the identifiers. */
  private @Nullable Long record(long id, Work incoming, Layer layer, List<Identifier> all) {
    identifiers.insertAll(id, all, layer.name());
    sightings.sight(id, layer.name(), incoming.sourceId());
    return identifiers.findWorkIds(all).stream().filter(w -> w != id).findFirst().orElse(null);
  }

  /** The id of the work the record belongs to, by the matching order, or null. */
  private @Nullable Long find(Work w, List<Identifier> all) {
    Long found = byIds(all, i -> i.scheme().equals(IdScheme.DOI.code()));
    if (found == null && w.doi() != null) {
      found = id(mapper.findByDoi(w.doi())); // a DOI the identifier rule rejects lives only in the column
    }
    if (found == null) {
      found = byIds(all, i -> i.scheme().equals(IdScheme.OPENALEX.code()));
    }
    if (found == null && w.openalexId() != null) {
      found = id(mapper.findByOpenalexId(w.openalexId()));
    }
    if (found == null) {
      found = byIds(all, i -> !FIRST_SCHEMES.contains(i.scheme()));
    }
    return found != null ? found : byTitle(w);
  }

  private @Nullable Long byIds(List<Identifier> all, Predicate<Identifier> scheme) {
    List<Identifier> some = all.stream().filter(scheme).toList();
    if (some.isEmpty()) {
      return null;
    }
    List<Long> ids = identifiers.findWorkIds(some);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /** The lowest-id work of the same source and year with the same title key and first author, never another DOI. */
  private @Nullable Long byTitle(Work w) {
    String key = TitleKey.of(w.title());
    Short year = w.year() != null ? w.year()
        : w.publicationDate() == null ? null : (short) w.publicationDate().getYear();
    if (key == null || w.sourceId() == null || year == null) {
      return null;
    }
    String word = Arrays.stream(key.split(" ")).max(Comparator.comparingInt(String::length)).orElseThrow();
    String author = FirstAuthor.family(w.authors());
    for (WorkMapper.TitleCandidate c : mapper.findByTitleMatch(w.sourceId(), year, w.doi(), word)) {
      if (w.doi() != null && c.doi() != null && !w.doi().equals(c.doi())) {
        continue;
      }
      if (!key.equals(TitleKey.of(c.title()))) {
        continue;
      }
      String other = FirstAuthor.family(c.authors());
      if (author != null && other != null && !author.equals(other)) {
        continue;
      }
      return c.id();
    }
    return null;
  }

  /**
   * The given identifiers plus those of the work's DOI, OpenAlex id and PMCID columns, without duplicates. Throws when
   * there is none and the work has neither a DOI nor an OpenAlex id that the identifier rules reject (such a value is
   * kept in its column and matched there).
   */
  private static List<Identifier> identifiers(Work w, List<Identifier> ids) {
    Set<Identifier> all = new LinkedHashSet<>();
    for (Identifier i : ids) {
      if (i != null) {
        all.add(i);
      }
    }
    addIfValid(all, IdScheme.id(IdScheme.DOI, w.doi()));
    addIfValid(all, IdScheme.id(IdScheme.OPENALEX, w.openalexId()));
    addIfValid(all, IdScheme.id(IdScheme.PMCID, w.pmcid()));
    if (all.isEmpty() && normalizeDoi(w.doi()) == null && w.openalexId() == null) {
      throw new IllegalArgumentException("A work needs at least one identifier");
    }
    return new ArrayList<>(all);
  }

  private static void addIfValid(Set<Identifier> all, @Nullable Identifier i) {
    if (i != null) {
      all.add(i);
    }
  }

  /** The layer claims the fields it supplies on a new row. */
  private static Work claimed(Work w, Layer layer) {
    return copy(w, w.openalexId(), w.doi(), w.pmcid(), w.title(), w.authors(), w.abstractJatsSource(),
        WorkMerger.hasBiblio(w) ? layer.name() : null, WorkMerger.hasAccess(w) ? layer.name() : null);
  }

  /** DOI normalised, title cleaned, "[]" authors as none; DOI, OpenAlex id and PMCID filled from the identifiers. */
  private static Work normalized(Work w, List<Identifier> all) {
    String doi = normalizeDoi(w.doi());
    String openalexId = w.openalexId();
    String pmcid = w.pmcid();
    if (doi == null) {
      doi = first(all, IdScheme.DOI);
    }
    if (openalexId == null) {
      openalexId = first(all, IdScheme.OPENALEX);
    }
    if (pmcid == null) {
      pmcid = first(all, IdScheme.PMCID);
    }
    return copy(w, openalexId, doi, pmcid, TitleText.clean(w.title()), emptyAuthorsAsNull(w.authors()),
        w.abstractJatsSource(), w.metaLayer(), w.accessLayer());
  }

  private static @Nullable String first(List<Identifier> all, IdScheme scheme) {
    return all.stream().filter(i -> i.scheme().equals(scheme.code())).map(Identifier::value).findFirst().orElse(null);
  }

  private static Work withoutEmptyAuthors(Work w) {
    String authors = emptyAuthorsAsNull(w.authors());
    return authors == w.authors() ? w : copy(w, w.openalexId(), w.doi(), w.pmcid(), w.title(), authors,
        w.abstractJatsSource(), w.metaLayer(), w.accessLayer());
  }

  private static @Nullable String emptyAuthorsAsNull(@Nullable String authors) {
    return authors == null || authors.isBlank() || authors.replaceAll("\\s", "").equals("[]") ? null : authors;
  }

  private static @Nullable Long id(@Nullable Work w) {
    return w == null ? null : w.id();
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }

  private static Work copy(Work w, String openalexId, String doi, String pmcid, String title, String authors,
      String abstractJatsSource, String metaLayer, String accessLayer) {
    return new Work(w.id(), openalexId, doi, w.sourceId(), title, w.publicationDate(), w.year(), w.volume(),
        w.issue(), w.pages(), authors, w.type(), w.language(), w.oaStatus(), w.license(), w.bestOaUrl(),
        w.abstractText(), w.abstractSource(), w.origin(), w.state(), w.textLevel(), w.codes(), w.taxgroups(),
        w.hasActs(), w.firstSeenAt(), w.modified(), w.abstractJats(), pmcid, w.openalexHasTei(),
        w.openalexCheckedAt(), metaLayer, abstractJatsSource, accessLayer);
  }
}
