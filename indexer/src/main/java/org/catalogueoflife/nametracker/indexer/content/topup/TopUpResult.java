package org.catalogueoflife.nametracker.indexer.content.topup;

import java.util.List;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.jspecify.annotations.Nullable;

/**
 * What a top-up found. {@code abstractText} and {@code abstractJats} (either or both) are the work's abstract, stored
 * only when the work has none, under {@code abstractSource} (an {@code abstract_source} value) and in
 * {@code abstractLang}. {@code parallels} become {@code work_text} rows and {@code declaredLanguage} DECLARED language
 * evidence of the service.
 */
public record TopUpResult(@Nullable String abstractText, @Nullable String abstractJats, @Nullable String abstractLang,
    String abstractSource, List<TextIn> parallels, @Nullable String declaredLanguage) {

  public TopUpResult {
    parallels = parallels == null ? List.of() : List.copyOf(parallels);
  }

  public boolean hasAbstract() {
    return notBlank(abstractText) || notBlank(abstractJats);
  }

  /** Something worth keeping. */
  public boolean found() {
    return hasAbstract() || !parallels.isEmpty() || notBlank(declaredLanguage);
  }

  private static boolean notBlank(@Nullable String s) {
    return s != null && !s.isBlank();
  }
}
