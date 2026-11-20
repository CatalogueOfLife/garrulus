package org.catalogueoflife.nametracker.store.ident;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** ISSN normalisation and validation (ISO 3297, mod-11 check digit). */
public final class Issn {

  /** A label in front of an ISSN: ISSN, ISSN-L, eISSN, e-ISSN, pISSN, p-ISSN, any case, optional colon. */
  private static final Pattern PREFIX = Pattern.compile("^(?:[EP]-?)?ISSN(?:-L)?[\\s\\u00A0:]*");
  private static final Pattern SEPARATORS = Pattern.compile("[-\\s\\u00A0]");
  /** Not glued to a longer token, except to a label (ISSN1175-5326). */
  private static final Pattern IN_TEXT = Pattern.compile(
      "(?:(?<![0-9A-Za-z])|(?<=(?i:ISSN))|(?<=(?i:ISSN-L)))(\\d{4})[- \\u00A0\\t]?(\\d{3}[\\dXx])(?![0-9A-Za-z])");

  private Issn() {}

  /** The ISSN as {@code NNNN-NNNC}, or null unless the input has 8 characters with a valid check digit. */
  public static @Nullable String normalise(@Nullable String raw) {
    if (raw == null) return null;
    String s = raw.strip().toUpperCase(Locale.ROOT).replaceFirst("^[\\s\\u00A0]+", "");
    s = PREFIX.matcher(s).replaceFirst("");
    s = SEPARATORS.matcher(s).replaceAll("");
    if (!s.matches("\\d{7}[\\dX]")) return null;
    int sum = 0;
    for (int i = 0; i < 7; i++) sum += (s.charAt(i) - '0') * (8 - i);
    int check = (11 - sum % 11) % 11;
    char expected = check == 10 ? 'X' : (char) ('0' + check);
    if (s.charAt(7) != expected) return null;
    return s.substring(0, 4) + "-" + s.substring(4);
  }

  /** Every valid ISSN in a free text, in order of first occurrence, deduplicated. */
  public static List<String> all(@Nullable String text) {
    if (text == null) return List.of();
    LinkedHashSet<String> out = new LinkedHashSet<>();
    Matcher m = IN_TEXT.matcher(text);
    int from = 0;
    while (from < text.length() && m.find(from)) {
      String n = normalise(m.group(1) + m.group(2));
      if (n != null) {
        out.add(n);
        from = m.end();
      } else {
        from = m.start() + 1; // "2012 1175 5326": the year must not swallow half of the ISSN
      }
    }
    return new ArrayList<>(out);
  }
}
