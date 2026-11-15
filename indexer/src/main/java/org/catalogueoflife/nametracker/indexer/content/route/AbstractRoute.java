package org.catalogueoflife.nametracker.indexer.content.route;

import java.nio.charset.StandardCharsets;
import org.catalogueoflife.nametracker.finder.doc.AbstractConverter;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.store.work.Work;

/** Route {@code ABSTRACT}: the title and abstract the harvest already holds; no network. Not a {@code ContentRoute}. */
public final class AbstractRoute {

  public static final String ROUTE = "ABSTRACT";

  private AbstractRoute() {
  }

  public static boolean present(Work w) {
    return notBlank(w.title()) || notBlank(w.abstractText()) || notBlank(w.abstractJats());
  }

  public static FetchedContent fetch(Work w) {
    String raw = notBlank(w.abstractJats()) ? w.abstractJats()
        : notBlank(w.abstractText()) ? w.abstractText() : w.title();
    return new FetchedContent(ROUTE, "ABSTRACT", raw.getBytes(StandardCharsets.UTF_8),
        AbstractConverter.fromParts("work:" + w.id(), w.title(), w.abstractText(), w.abstractJats()), w.license(),
        w.doi() == null ? null : "https://doi.org/" + w.doi(), false);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
