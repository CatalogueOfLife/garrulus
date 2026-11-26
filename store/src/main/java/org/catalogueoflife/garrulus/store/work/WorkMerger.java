package org.catalogueoflife.garrulus.store.work;

import java.util.Set;
import java.util.function.Function;

/** Pure per-field merge of an incoming harvest record into the stored work, by layer precedence. */
public final class WorkMerger {

  /** The merge result; {@code changed} is false when the merged work equals the stored one. */
  public record Merged(Work work, boolean changed) {}

  private static final Set<String> NAMED = Set.of("NAMES_FOUND", "MATCHED", "INDEXED", "NOTIFIED");
  private static final int WORST = Integer.MAX_VALUE;

  private WorkMerger() {}

  public static Merged merge(Work s, Work in, Layer layer) {
    Layer metaOwner = s.metaLayer() == null ? null : Layer.valueOf(s.metaLayer());
    Layer accessOwner = s.accessLayer() == null ? null : Layer.valueOf(s.accessLayer());
    boolean biblio = metaOwner == null || layer.biblioRank() <= metaOwner.biblioRank();
    boolean access = accessOwner == null || layer.accessRank() <= accessOwner.accessRank();
    boolean hasBiblio = hasBiblio(in);
    boolean hasAccess = hasAccess(in);
    boolean frozen = s.state() != null && NAMED.contains(s.state());

    String title = frozen ? s.title() : pick(s.title(), in.title(), biblio);

    // abstract: JATS and plain text are ranked separately; a better kind replaces, an equal kind only from the
    // abstract's own source (so two layers of the same kind with overlapping windows do not flip it every night)
    boolean takeJats = notBlank(in.abstractJats()) && (blank(s.abstractJats())
        || better(jatsRank(in.abstractJatsSource()), jatsRank(s.abstractJatsSource()), in.abstractJatsSource(),
            s.abstractJatsSource()));
    String jats = takeJats ? in.abstractJats() : s.abstractJats();
    String jatsSource = takeJats ? in.abstractJatsSource() : s.abstractJatsSource();
    boolean takeText = notBlank(in.abstractText()) && (blank(s.abstractText())
        || better(textRank(in.abstractSource()), textRank(s.abstractSource()), in.abstractSource(),
            s.abstractSource()));
    String text = takeText ? in.abstractText() : s.abstractText();
    String textSource = takeText ? in.abstractSource() : s.abstractSource();

    Work m = new Work(
        s.id(),
        fill(s.openalexId(), in.openalexId()),
        fill(s.doi(), in.doi()),
        fill(s.sourceId(), in.sourceId()),
        title,
        pick(s.publicationDate(), in.publicationDate(), biblio),
        pick(s.year(), in.year(), biblio),
        pick(s.volume(), in.volume(), biblio),
        pick(s.issue(), in.issue(), biblio),
        pick(s.pages(), in.pages(), biblio),
        pick(s.authors(), in.authors(), biblio),
        pick(s.type(), in.type(), biblio),
        s.language(), // decided from evidence (LanguageService), never merged from a layer's raw value
        pick(s.oaStatus(), in.oaStatus(), access),
        pick(s.license(), in.license(), access),
        pick(s.bestOaUrl(), in.bestOaUrl(), access),
        text,
        textSource,
        s.origin(),
        s.state(),
        s.textLevel(),
        s.codes(),
        s.taxgroups(),
        s.hasActs(),
        s.firstSeenAt(),
        s.modified(),
        jats,
        fill(s.pmcid(), in.pmcid()),
        in.openalexHasTei() != null ? in.openalexHasTei() : s.openalexHasTei(),
        fill(s.openalexCheckedAt(), in.openalexCheckedAt()),
        (biblio && hasBiblio) ? layer.name() : s.metaLayer(),
        jatsSource,
        (access && hasAccess) ? layer.name() : s.accessLayer(),
        s.languageSource(), s.titleLang(), s.abstractLang(), s.titleEn(), s.titleEnKind());
    return new Merged(m, !m.equals(s));
  }

  /** The record carries a bibliographic value: only then may its layer claim the bibliographic fields. */
  static boolean hasBiblio(Work w) {
    return w.title() != null || w.authors() != null || w.volume() != null || w.issue() != null || w.pages() != null
        || w.publicationDate() != null || w.year() != null || w.type() != null;
  }

  /** The record carries an access value: only then may its layer claim the access fields. */
  static boolean hasAccess(Work w) {
    return w.oaStatus() != null || w.license() != null || w.bestOaUrl() != null;
  }

  /** The incoming value wins when its layer wins and it is not null; otherwise fill a null. */
  private static <T> T pick(T stored, T incoming, boolean wins) {
    if (incoming == null) {
      return stored;
    }
    return wins || stored == null ? incoming : stored;
  }

  private static <T> T fill(T stored, T incoming) {
    return stored != null ? stored : incoming;
  }

  /** A lower rank wins; an equal rank only when it comes from the stored abstract's own source. */
  private static boolean better(int inRank, int storedRank, String inSource, String storedSource) {
    return inRank < storedRank || inRank == storedRank && java.util.Objects.equals(inSource, storedSource);
  }

  /** CROSSREF 0; every other JATS kind, also an unlabelled one, 1. */
  private static int jatsRank(String source) {
    return "CROSSREF".equals(source) ? 0 : 1;
  }

  /** Plain text: JATS-derived 0 or 1 (CROSSREF_JATS first), other plain 2, inverted index 3, unlabelled 2. */
  private static int textRank(String source) {
    if (source == null) {
      return 2;
    }
    if (source.equals("CROSSREF_JATS")) {
      return 0;
    }
    if (source.contains("JATS")) {
      return 1;
    }
    return source.equals("OPENALEX_INVERTED") ? 3 : 2;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static boolean notBlank(String s) {
    return !blank(s);
  }
}
