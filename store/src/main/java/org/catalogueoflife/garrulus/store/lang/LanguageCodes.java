package org.catalogueoflife.garrulus.store.lang;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One form for language codes: ISO 639-1 where one exists, else ISO 639-3. Accepts 639-1, 639-2/T, 639-2/B, English
 * names and BCP 47 tags (region and script dropped), in any case. {@code und}, {@code mul}, {@code zxx}, {@code mis}
 * and anything unknown give null.
 */
public final class LanguageCodes {

  private static final Set<String> UNDETERMINED = Set.of("und", "mul", "zxx", "mis");

  /** ISO 639-2/B codes that differ from the 639-2/T code, and the retired 639-1 codes. */
  private static final Map<String, String> ALIASES = Map.ofEntries(
      Map.entry("alb", "sq"), Map.entry("arm", "hy"), Map.entry("baq", "eu"), Map.entry("bur", "my"),
      Map.entry("chi", "zh"), Map.entry("cze", "cs"), Map.entry("dut", "nl"), Map.entry("fre", "fr"),
      Map.entry("geo", "ka"), Map.entry("ger", "de"), Map.entry("gre", "el"), Map.entry("ice", "is"),
      Map.entry("mac", "mk"), Map.entry("mao", "mi"), Map.entry("may", "ms"), Map.entry("per", "fa"),
      Map.entry("rum", "ro"), Map.entry("slo", "sk"), Map.entry("tib", "bo"), Map.entry("wel", "cy"),
      Map.entry("iw", "he"), Map.entry("in", "id"), Map.entry("ji", "yi"), Map.entry("jw", "jv"));

  private static final Set<String> NON_LATIN = Set.of("zh", "ja", "ko", "ru", "uk", "be", "bg", "mk", "sr", "kk", "ky",
      "mn", "ar", "fa", "ur", "he", "el", "th", "hi", "bn", "ta", "te", "ml", "kn", "mr", "ne", "si", "my", "km", "lo",
      "ka", "hy", "am");

  /** Lower-case code or English name to its normalised code. */
  private static final Map<String, String> LOOKUP = new HashMap<>();

  static {
    for (String iso : Locale.getISOLanguages()) {
      Locale l = Locale.of(iso);
      LOOKUP.put(iso, iso);
      LOOKUP.put(l.getISO3Language(), iso);
      LOOKUP.putIfAbsent(l.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT), iso);
    }
    // languages without a 639-1 code: the three-letter ones the JDK knows
    for (Locale l : Locale.getAvailableLocales()) {
      String c = l.getLanguage();
      if (c.length() == 3 && !LOOKUP.containsKey(c)) {
        LOOKUP.put(c, c);
        String name = l.getDisplayLanguage(Locale.ENGLISH).toLowerCase(Locale.ROOT);
        if (!name.equals(c)) LOOKUP.putIfAbsent(name, c);
      }
    }
    LOOKUP.putAll(ALIASES);
    UNDETERMINED.forEach(LOOKUP::remove);
  }

  private LanguageCodes() {}

  public static @Nullable String normalise(@Nullable String raw) {
    if (raw == null) return null;
    String c = raw.strip().toLowerCase(Locale.ROOT);
    if (c.isEmpty() || UNDETERMINED.contains(c)) return null;
    String hit = LOOKUP.get(c);
    if (hit != null) return unify(hit);
    int sep = indexOfSeparator(c);
    if (sep > 0) {
      String head = c.substring(0, sep);
      if (UNDETERMINED.contains(head)) return null;
      String h = LOOKUP.get(head);
      return h == null ? null : unify(h);
    }
    return null;
  }

  /**
   * Norwegian is one language here: Bokmål, Nynorsk and the macrolanguage are {@code no}; the detector reports its
   * Bokmål as {@code no} too.
   */
  private static String unify(String code) {
    return "nb".equals(code) || "nn".equals(code) ? "no" : code;
  }

  private static int indexOfSeparator(String c) {
    for (int i = 0; i < c.length(); i++) {
      char ch = c.charAt(i);
      if (ch == '-' || ch == '_') return i;
    }
    return -1;
  }

  /** False for the languages written in a script other than Latin (the ones a Latin-only name check cannot serve). */
  public static boolean isLatinScript(String code) {
    String c = normalise(code);
    return c == null || !NON_LATIN.contains(c);
  }
}
