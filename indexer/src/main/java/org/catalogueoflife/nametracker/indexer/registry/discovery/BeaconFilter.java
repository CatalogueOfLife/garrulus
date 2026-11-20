package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which Beacon journals are offered as candidates: a topic term in the context name (accent-folded, lower-cased, every
 * term matched at a word start), no excluded term, an answering endpoint and some activity. A zero count of 2025
 * records alone never excludes (Zootaxa reports 0): the last OAI response within 365 days is activity too.
 */
final class BeaconFilter {

  static final List<String> INCLUDE = List.of("zoolog", "taxonom", "entomolog", "botan", "mycolog", "fauna", "flora",
      "natural history", "museum", "herpetolog", "ornitholog", "ichthyolog", "malacolog", "arachnolog", "acarolog",
      "lichenolog", "bryolog", "phycolog", "nematolog", "parasitolog", "biodivers", "systematic", "phytotax", "zootax",
      "historia natural", "histoire naturelle", "faune", "flore", "museu", "museo");
  static final List<String> EXCLUDE = List.of("museolog", "museum studies", "veterinar", "medic", "pharmac");
  static final int MIN_RECORDS_2025 = 5;
  static final Duration RECENT = Duration.ofDays(365);

  private final Pattern include;
  private final Pattern exclude;
  private final Clock clock;

  private BeaconFilter(Pattern include, Pattern exclude, Clock clock) {
    this.include = include;
    this.exclude = exclude;
    this.clock = clock;
  }

  static BeaconFilter defaults(Clock clock) {
    return of(INCLUDE, EXCLUDE, clock);
  }

  static BeaconFilter of(List<String> include, List<String> exclude, Clock clock) {
    return new BeaconFilter(words(include), words(exclude), clock);
  }

  /** A blank list (the property unset) is the built-in list. */
  static List<String> list(String property, List<String> builtIn) {
    if (property == null || property.isBlank()) return builtIn;
    return java.util.Arrays.stream(property.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }

  boolean accepts(BeaconRow row) {
    if (row.contextName() == null || row.unresponsive()) return false;
    String name = fold(row.contextName());
    if (!include.matcher(name).find() || exclude.matcher(name).find()) return false;
    return (row.records2025() != null && row.records2025() >= MIN_RECORDS_2025) || (row.lastOaiResponse() != null
        && row.lastOaiResponse().isAfter(clock.instant().minus(RECENT)));
  }

  private static Pattern words(List<String> terms) {
    if (terms.isEmpty()) return Pattern.compile("(?!)");
    String alt = terms.stream().map(t -> Pattern.quote(fold(t))).reduce((a, b) -> a + "|" + b).orElseThrow();
    return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alt + ")");
  }

  static String fold(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
  }
}
