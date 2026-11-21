package org.catalogueoflife.nametracker.web.api.public_;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.jspecify.annotations.Nullable;

/**
 * The repeatable {@code language} parameter of work searches: each value is normalised to ISO 639-1 ({@code chi}
 * becomes {@code zh}), 400 for one that is no language; {@code none} selects the works without a decision.
 */
public final class LanguageParam {

  /** The value that selects works without a decision (the facet shows them as an empty value). */
  public static final String NONE = "none";

  private LanguageParam() {}

  public static List<String> parse(@Nullable Collection<String> values) {
    if (values == null) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    for (String v : values) {
      if (v != null && v.strip().equalsIgnoreCase(NONE)) {
        out.add("");
        continue;
      }
      String code = v == null ? null : LanguageCodes.normalise(v);
      if (code == null) {
        throw WorkController.bad("unknown language " + v);
      }
      out.add(code);
    }
    return List.copyOf(out);
  }
}
