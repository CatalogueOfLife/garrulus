package org.catalogueoflife.nametracker.store.work;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
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
 * never between two different DOIs; a record without a DOI joins nothing when the candidates hold two DOIs). Upserts
 * of the same title in the same source and year are serialised by a transaction-level advisory lock. A found row is locked ({@code FOR UPDATE}, so run the upsert inside the caller's
 * transaction) and merged field by field by layer precedence ({@link WorkMerger}); otherwise the record is inserted.
 * Every upsert then adds the record's identifiers and one sighting per layer. The work's DOI, OpenAlex id and PMCID
 * columns are mirrored into the identifiers and filled from them. Concurrent inserts are resolved by INSERT ... ON
 * CONFLICT DO NOTHING on the unique columns followed by a re-read and merge. The DOI is normalised and the title
 * cleaned of markup and entities ({@link TitleText}) before either.
 */
public class WorkUpsert {

  /**
   * {@code abstractChanged}: the effective abstract (the JATS one, else the plain text) of a work already past
   * DISCOVERED changed, so the content resolution that ran before has not seen it and should run again;
   * {@code abstractSha} is then the first 12 hex digits of the SHA-256 of the new effective abstract, else null.
   * {@code duplicateOf}: another work that holds one of the incoming identifiers (the lowest such id); the caller
   * enqueues MERGE_WORKS.
   */
  public record UpsertResult(long id, boolean created, boolean abstractChanged, @Nullable String abstractSha,
      @Nullable Long duplicateOf) {
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
    TitleSlot slot = lockTitle(incoming);
    for (int i = 0; i < TRIES; i++) {
      Long found = find(incoming, all, slot);
      if (found == null) {
        Long id = mapper.insertIfAbsent(claimed(incoming, layer));
        if (id == null) {
          continue; // lost a race against a concurrent insert: re-read and merge
        }
        return new UpsertResult(id, true, false, null, record(id, incoming, layer, all));
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
      String before = effectiveAbstract(stored);
      String after = effectiveAbstract(merged.work());
      String sha = after != null && !after.equals(before) && !"DISCOVERED".equals(stored.state())
          ? BlobStore.sha256(after).substring(0, 12) : null;
      return new UpsertResult(found, false, sha != null, sha, record(found, incoming, layer, all));
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
    if (find(incoming, all, lockTitle(incoming)) != null) {
      return Optional.empty();
    }
    Long id = mapper.insertIfAbsent(claimed(incoming, layer));
    if (id == null) {
      return Optional.empty();
    }
    record(id, incoming, layer, all);
    return Optional.of(id);
  }

  /** Writes the identifiers and the sighting of the work; returns another work holding one of the identifiers. */
  private @Nullable Long record(long id, Work incoming, Layer layer, List<Identifier> all) {
    identifiers.insertAll(id, all, layer.name());
    sightings.sight(id, layer.name(), incoming.sourceId());
    return identifiers.findWorkIds(all).stream().filter(w -> w != id).findFirst().orElse(null);
  }

  /** The id of the work the record belongs to, by the matching order, or null. */
  private @Nullable Long find(Work w, List<Identifier> all, @Nullable TitleSlot slot) {
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
    return found != null ? found : byTitle(w, slot);
  }

  private @Nullable Long byIds(List<Identifier> all, Predicate<Identifier> scheme) {
    List<Identifier> some = all.stream().filter(scheme).toList();
    if (some.isEmpty()) {
      return null;
    }
    List<Long> ids = identifiers.findWorkIds(some);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /** Source, year and title key of a record that can take part in a title match. */
  private record TitleSlot(long sourceId, short year, String key) {

    /** The advisory-lock key: same-title upserts of one source and year wait for each other's commit. */
    String lockKey() {
      return sourceId + ":" + year + ":" + key;
    }
  }

  private static @Nullable TitleSlot slot(Work w) {
    String key = TitleKey.of(w.title());
    Short year = w.year() != null ? w.year()
        : w.publicationDate() == null ? null : (short) w.publicationDate().getYear();
    return key == null || w.sourceId() == null || year == null ? null : new TitleSlot(w.sourceId(), year, key);
  }

  /**
   * Serialises upserts of the same title in the same source and year until the caller's transaction ends, so two
   * layers bringing the same new paper without a shared identifier (Crossref with a DOI, OAI without) cannot both miss
   * the other's uncommitted row and insert twice.
   */
  private @Nullable TitleSlot lockTitle(Work w) {
    TitleSlot slot = slot(w);
    if (slot != null) {
      mapper.lockTitle(slot.lockKey());
    }
    return slot;
  }

  /**
   * The lowest-id work of the same source and year with the same title key and first author. A record with a DOI only
   * joins a work without one. A record without a DOI joins nothing when the matching works hold more than one DOI:
   * joining either would later ask MERGE_WORKS to merge two DOIs.
   */
  private @Nullable Long byTitle(Work w, @Nullable TitleSlot slot) {
    if (slot == null) {
      return null;
    }
    String word = Arrays.stream(slot.key().split(" ")).max(Comparator.comparingInt(String::length)).orElseThrow();
    String author = FirstAuthor.family(w.authors());
    Long first = null;
    Set<String> dois = new HashSet<>();
    for (WorkMapper.TitleCandidate c : mapper.findByTitleMatch(slot.sourceId(), slot.year(), w.doi() != null, word)) {
      if (w.doi() != null && c.doi() != null || !slot.key().equals(TitleKey.of(c.title()))) {
        continue;
      }
      String other = FirstAuthor.family(c.authors());
      if (author != null && other != null && !author.equals(other)) {
        continue;
      }
      if (first == null) {
        first = c.id();
      }
      if (c.doi() != null) {
        dois.add(c.doi());
      }
    }
    return dois.size() > 1 ? null : first;
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

  /** The abstract the finder reads: the JATS one, else the plain text, else null. */
  public static @Nullable String effectiveAbstract(Work w) {
    return !blank(w.abstractJats()) ? w.abstractJats() : !blank(w.abstractText()) ? w.abstractText() : null;
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
        w.openalexCheckedAt(), metaLayer, abstractJatsSource, accessLayer, w.languageSource(), w.titleLang(),
        w.abstractLang(), w.titleEn(), w.titleEnKind());
  }
}
