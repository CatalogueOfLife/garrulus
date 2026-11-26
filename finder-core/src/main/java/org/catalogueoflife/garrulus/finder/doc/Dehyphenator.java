package org.catalogueoflife.garrulus.finder.doc;

import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repairs words split across lines by a hyphen, guided by a dictionary. Meant to be applied per block. */
public final class Dehyphenator {
  private static final Pattern BREAK = Pattern.compile("(\\p{L}+)-\\s*\\n\\s*(\\p{Ll}+)");

  private Dehyphenator() {}

  /**
   * "Pterosti-\nchus" becomes "Pterostichus" if {@code isKnownWord} accepts the lower case joined word, otherwise the
   * hyphen is kept as a real one ("north-east") and only the line break is removed.
   */
  public static String join(String text, Predicate<String> isKnownWord) {
    Matcher m = BREAK.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String joined = m.group(1) + m.group(2);
      String rep = isKnownWord.test(joined.toLowerCase()) ? joined : m.group(1) + "-" + m.group(2);
      m.appendReplacement(sb, Matcher.quoteReplacement(rep));
    }
    m.appendTail(sb);
    return sb.toString();
  }
}
