package org.catalogueoflife.nametracker.store.ident;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** ISSN normalisation and validation (ISO 3297, mod-11 check digit). */
public final class Issn {

  /** A label in front of an ISSN: ISSN, ISSN-L, eISSN, e-ISSN, pISSN, p-ISSN, any case, optional colon. */
  private static final Pattern PREFIX = Pattern.compile("^(?:[EP]-?)?ISSN(?:-L)?[\\s\\u00A0:]*");
  private static final Pattern SEPARATORS = Pattern.compile("[-\\u2013\\s\\u00A0]");
  /** An ISSN behind a label: spaces, NBSP, a dash or nothing may separate the halves (ISSN 1175 5326, ISSN1175-5326). */
  private static final Pattern LABELLED = Pattern.compile(
      "(?<![A-Za-z])(?i:(?:[ep]-?)?ISSN(?:-L)?)[\\s\\u00A0:]{0,8}(\\d{4})[-\\u2013 \\u00A0\\t]?(\\d{3}[\\dXx])(?![0-9A-Za-z])");
  /** A hyphenated (or en dash) ISSN anywhere, but not inside a longer number, a DOI or a path. */
  private static final Pattern HYPHENATED = Pattern.compile(
      "(?<![0-9A-Za-z/.\\-\\u2013])(\\d{4})[-\\u2013](\\d{3}[\\dXx])(?![0-9A-Za-z]|[-\\u2013]\\d)");
  /** "pp. " or "p." right before: a page range. Looked for in the few characters before a match only. */
  private static final Pattern PAGES = Pattern.compile("(?i)\\bpp?\\.?\\s{0,8}$");
  private static final int PAGES_LOOK_BACK = 16;
  /**
   * Placeholders that pass the check digit but name no journal: {@code 0000-0000} is what forms and OJS fill in for
   * "none". Rejected like an invalid ISSN, so they never match or join two journals.
   */
  private static final Set<String> PLACEHOLDERS = Set.of("0000-0000");

  private Issn() {}

  /**
   * The ISSN as {@code NNNN-NNNC}, or null unless the input has 8 characters with a valid check digit and is not a
   * placeholder ({@code 0000-0000}).
   */
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
    String issn = s.substring(0, 4) + "-" + s.substring(4);
    return PLACEHOLDERS.contains(issn) ? null : issn;
  }

  /**
   * The form an ISSN from a structured field (OpenAlex) is stored in: the normalised ISSN, else the trimmed, upper-cased
   * input (a wrong check digit is kept as the service has it), null for a blank one or a placeholder. Stored ISSNs are upper case so the
   * source lookups compare them as they are and use the indexes.
   */
  public static @Nullable String stored(@Nullable String raw) {
    String n = normalise(raw);
    if (n != null) return n;
    if (raw == null || raw.isBlank()) return null;
    String upper = raw.strip().toUpperCase(Locale.ROOT);
    return PLACEHOLDERS.contains(upper) ? null : upper;
  }

  /**
   * Every valid ISSN in a free text, in order of first occurrence, deduplicated. Only the hyphenated form is accepted
   * anywhere (not after "pp." or in a DOI, not followed by more digits); the forms with a space, NBSP or nothing between
   * the halves only directly after a label (ISSN, ISSN-L, eISSN, pISSN, e-ISSN, p-ISSN). Linear in the text: the
   * page-range look-back reads at most 16 characters before a match.
   */
  public static List<String> all(@Nullable CharSequence text) {
    if (text == null) return List.of();
    TreeMap<Integer, String> found = new TreeMap<>();
    Matcher m = LABELLED.matcher(text);
    while (m.find()) put(found, m.start(1), m.group(1) + m.group(2));
    m = HYPHENATED.matcher(text);
    while (m.find()) {
      Matcher pages = PAGES.matcher(text).region(Math.max(0, m.start() - PAGES_LOOK_BACK), m.start())
          .useTransparentBounds(true).useAnchoringBounds(true);
      if (pages.find()) continue;
      put(found, m.start(1), m.group(1) + m.group(2));
    }
    return new ArrayList<>(new LinkedHashSet<>(found.values()));
  }

  private static void put(TreeMap<Integer, String> found, int at, String raw) {
    String n = normalise(raw);
    if (n != null) found.put(at, n);
  }
}
