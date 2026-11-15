package org.catalogueoflife.nametracker.indexer.content;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Decides which licences allow us to keep full text. Licences are compared in normalised form. */
public class LicencePolicy {

  private static final Pattern CC = Pattern.compile("(?:^|-)cc-(by(?:-(?:nc|nd|sa))*)(?:-\\d.*)?(?:-[a-z]{2,3})?$");
  private static final Pattern CC_URL = Pattern.compile("creativecommons\\.org/licenses/(by(?:-(?:nc|nd|sa))*)(?:/|$)");

  private final Set<String> open;

  public LicencePolicy(Set<String> open) {
    this.open = Set.copyOf(open.stream().map(LicencePolicy::normalise).toList());
  }

  public boolean isOpen(@Nullable String licence) {
    return licence != null && open.contains(normalise(licence));
  }

  /**
   * {@code http://creativecommons.org/licenses/by/4.0/}, {@code CC BY 4.0} and {@code cc-by-4.0} become
   * {@code cc-by}; {@code .../by-nc/...} becomes {@code cc-by-nc}; CC0 and public domain forms become {@code cc0} and
   * {@code public-domain}. Anything else is returned lower-cased and trimmed.
   */
  public static String normalise(String licence) {
    String s = licence.trim().toLowerCase(Locale.ROOT);
    if (s.contains("creativecommons.org/publicdomain/zero") || s.matches("cc[\\s_-]*0.*") || s.contains("cc-zero")) {
      return "cc0";
    }
    if (s.contains("creativecommons.org/publicdomain/mark") || s.matches("public[\\s_-]*domain|pdm|cc[\\s_-]*pdm.*")) {
      return "public-domain";
    }
    Matcher u = CC_URL.matcher(s);
    if (u.find()) return "cc-" + u.group(1);
    String d = s.replaceAll("[\\s_]+", "-");
    Matcher m = CC.matcher(d);
    if (m.find()) return "cc-" + m.group(1);
    return s;
  }
}
