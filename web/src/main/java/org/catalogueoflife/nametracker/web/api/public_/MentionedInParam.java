package org.catalogueoflife.nametracker.web.api.public_;

import java.util.Collection;
import java.util.List;
import org.catalogueoflife.nametracker.store.search.MentionedIn;
import org.jspecify.annotations.Nullable;

/** The repeatable {@code mentionedIn} parameter of searches and public feeds: validated here, 400 for another value. */
public final class MentionedInParam {

  private MentionedInParam() {}

  public static List<String> parse(@Nullable Collection<String> values) {
    try {
      return MentionedIn.parse(values);
    } catch (IllegalArgumentException e) {
      throw WorkController.bad(e.getMessage());
    }
  }
}
