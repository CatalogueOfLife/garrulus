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
    Map<String, String> aliases = Map.ofEntries(
        Map.entry("russia", "RU"),
        Map.entry("iran", "IR"),
        Map.entry("iran, islamic rep.", "IR"),
        Map.entry("turkey", "TR"),
        Map.entry("türkiye", "TR"),
        Map.entry("turkiye", "TR"),
        Map.entry("viet nam", "VN"),
        Map.entry("vietnam", "VN"),
        Map.entry("south korea", "KR"),
        Map.entry("korea, republic of", "KR"),
        Map.entry("korea, rep.", "KR"),
        Map.entry("korea", "KR"),
        Map.entry("czechia", "CZ"),
        Map.entry("czech republic", "CZ"),
        Map.entry("taiwan", "TW"),
        Map.entry("bolivia", "BO"),
        Map.entry("venezuela", "VE"),
        Map.entry("venezuela, rb", "VE"),
        Map.entry("tanzania", "TZ"),
        Map.entry("syria", "SY"),
        Map.entry("syrian arab republic", "SY"),
        Map.entry("laos", "LA"),
        Map.entry("lao pdr", "LA"),
        Map.entry("moldova", "MD"),
        Map.entry("democratic republic of the congo", "CD"),
        Map.entry("congo - kinshasa", "CD"),
        Map.entry("congo, dem. rep.", "CD"),
        Map.entry("dr congo", "CD"),
        Map.entry("republic of the congo", "CG"),
        Map.entry("congo - brazzaville", "CG"),
        Map.entry("congo, rep.", "CG"),
        Map.entry("congo", "CG"),
        Map.entry("myanmar", "MM"),
        Map.entry("burma", "MM"),
        Map.entry("côte d'ivoire", "CI"),
        Map.entry("côte d’ivoire", "CI"),
        Map.entry("cote d'ivoire", "CI"),
        Map.entry("ivory coast", "CI"),
        Map.entry("egypt, arab rep.", "EG"),
        Map.entry("kyrgyz republic", "KG"),
        Map.entry("slovak republic", "SK"),
        Map.entry("gambia, the", "GM"),
        Map.entry("bahamas, the", "BS"),
        Map.entry("hong kong sar, china", "HK"),
        Map.entry("macao sar, china", "MO"),
        Map.entry("cabo verde", "CV"),
        Map.entry("cape verde", "CV"),
        Map.entry("eswatini", "SZ"),
        Map.entry("swaziland", "SZ"),
        Map.entry("north macedonia", "MK"),
        Map.entry("macedonia", "MK"),
        Map.entry("palestine", "PS"),
        Map.entry("west bank and gaza", "PS"),
        Map.entry("micronesia, fed. sts.", "FM"),
        Map.entry("east timor", "TL"),
        Map.entry("timor-leste", "TL"),
        Map.entry("yemen, rep.", "YE"),
        Map.entry("brunei", "BN"),
        Map.entry("brunei darussalam", "BN"),
        Map.entry("united states", "US"),
        Map.entry("usa", "US"),
        Map.entry("uk", "GB"),
        Map.entry("united kingdom", "GB"));
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
