package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** ISO 3166-1 alpha-2 codes from English country names (the PKP Beacon lists names; DOAJ and OpenAlex give codes). */
final class Countries {

  private static final Map<String, String> BY_NAME = new HashMap<>();
  private static final Set<String> CODES = Set.of(Locale.getISOCountries());

  static {
    for (String cc : Locale.getISOCountries()) {
      BY_NAME.put(Locale.of("", cc).getDisplayCountry(Locale.ENGLISH).toLowerCase(Locale.ROOT), cc);
    }
    Map<String, String> aliases = Map.ofEntries(Map.entry("russia", "RU"), Map.entry("iran", "IR"),
        Map.entry("turkey", "TR"), Map.entry("türkiye", "TR"), Map.entry("viet nam", "VN"),
        Map.entry("south korea", "KR"), Map.entry("korea, republic of", "KR"), Map.entry("korea", "KR"),
        Map.entry("czechia", "CZ"), Map.entry("taiwan", "TW"), Map.entry("bolivia", "BO"),
        Map.entry("venezuela", "VE"), Map.entry("tanzania", "TZ"), Map.entry("syria", "SY"), Map.entry("laos", "LA"),
        Map.entry("moldova", "MD"));
    BY_NAME.putAll(aliases);
  }

  private Countries() {}

  /** The code of an English country name (or a code itself), else null. */
  static @Nullable String code(@Nullable String name) {
    if (name == null || name.isBlank() || name.strip().equals("NA")) return null; // NA: missing in the Beacon, not Namibia
    String n = name.strip();
    String byName = BY_NAME.get(n.toLowerCase(Locale.ROOT));
    if (byName != null) return byName;
    String upper = n.toUpperCase(Locale.ROOT);
    return n.length() == 2 && CODES.contains(upper) ? upper : null;
  }
}
