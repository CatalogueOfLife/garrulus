package org.catalogueoflife.nametracker.store.work;

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
    boolean hasAccess = in.oaStatus() != null || in.license() != null || in.bestOaUrl() != null;
    boolean frozen = s.state() != null && NAMED.contains(s.state());

    String title = frozen ? s.title() : pick(s.title(), in.title(), biblio);

    // abstract: JATS and plain text are ranked separately
    boolean takeJats = notBlank(in.abstractJats())
        && (blank(s.abstractJats()) || jatsRank(in.abstractJatsSource()) <= jatsRank(s.abstractJatsSource()));
    String jats = takeJats ? in.abstractJats() : s.abstractJats();
    String jatsSource = takeJats ? in.abstractJatsSource() : s.abstractJatsSource();
    boolean takeText = notBlank(in.abstractText())
        && (blank(s.abstractText()) || textRank(in.abstractSource()) <= textRank(s.abstractSource()));
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
        fill(s.language(), in.language()),
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
        s.openalexHasTei(),
        s.openalexCheckedAt(),
        biblio ? layer.name() : s.metaLayer(),
        jatsSource,
        (access && hasAccess) ? layer.name() : s.accessLayer());
    return new Merged(m, !m.equals(s));
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
