package org.catalogueoflife.nametracker.web.digest;

import java.util.regex.Pattern;

/** Typography of scientific names where the rank is not known: decided by the shape of the name. */
final class SciNames {

  private static final Pattern BINOMIAL_OR_TRINOMIAL = Pattern.compile(
      "^[A-Z][a-z-]+(?: \\([A-Z][a-z-]+\\))? [a-z-]+(?: (?:(?:subsp|ssp|var|subvar|f|forma|morph|ab)\\. )?[a-z-]+)?$");

  private SciNames() {}

  /** True for binomials and trinomials (genus and below, italic); false for uninomials and everything else. */
  static boolean italicByShape(String name) {
    return name != null && BINOMIAL_OR_TRINOMIAL.matcher(name.strip()).matches();
  }
}
