package org.catalogueoflife.nametracker.store.work;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The family name of a work's first author, for the title match. {@code authors} is the work's JSON array of
 * {@code {"name": …}} objects; the name may be "Given Family", "Family, Given" or "Family G.". The family name is the
 * last word that is not an initial (of the part before the comma, if there is one), lower case, without diacritics.
 */
final class FirstAuthor {

  private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
  private static final Pattern ESCAPE = Pattern.compile("\\\\(u[0-9a-fA-F]{4}|.)");
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  private FirstAuthor() {
  }

  static @Nullable String family(@Nullable String authorsJson) {
    if (authorsJson == null) {
      return null;
    }
    Matcher m = NAME.matcher(authorsJson);
    if (!m.find()) {
      return null;
    }
    String name = unescape(m.group(1));
    int comma = name.indexOf(',');
    if (comma > 0) {
      name = name.substring(0, comma);
    }
    String family = null;
    for (String token : name.strip().split("\\s+")) {
      if (!token.isEmpty() && !initial(token)) {
        family = token;
      }
    }
    if (family == null) {
      return null;
    }
    String plain = MARKS.matcher(Normalizer.normalize(family, Normalizer.Form.NFKD)).replaceAll("");
    return plain.toLowerCase(Locale.ROOT);
  }

  /** "J.", "J", "JM", "J.-M." are initials; "Li" is not. */
  private static boolean initial(String token) {
    String letters = token.replace(".", "").replace("-", "");
    return letters.isEmpty() || token.endsWith(".") && letters.length() <= 2
        || letters.length() <= 2 && letters.equals(letters.toUpperCase(Locale.ROOT));
  }

  private static String unescape(String s) {
    if (s.indexOf('\\') < 0) {
      return s;
    }
    Matcher m = ESCAPE.matcher(s);
    StringBuilder sb = new StringBuilder(s.length());
    while (m.find()) {
      String e = m.group(1);
      String r = switch (e.charAt(0)) {
        case 'u' -> e.length() == 5 ? Character.toString(Integer.parseInt(e.substring(1), 16)) : e;
        case 'n', 't', 'r', 'b', 'f' -> " ";
        default -> e;
      };
      m.appendReplacement(sb, Matcher.quoteReplacement(r));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
