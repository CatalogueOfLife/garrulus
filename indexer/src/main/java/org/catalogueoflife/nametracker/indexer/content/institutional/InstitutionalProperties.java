package org.catalogueoflife.nametracker.indexer.content.institutional;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration of the institutional route ({@code nametracker.content.institutional.*}). Off by default with an empty
 * allow-list; it may be switched on only after the KU library has confirmed the licences in writing (spec,
 * "Institutional access"). Tokens are blank by default and never logged.
 */
@Component
public class InstitutionalProperties {

  private final boolean enabled;
  private final Set<String> publishers;
  private final @Nullable String grobidUrl;
  private final Duration refetchAfter;
  private final String elsevierBaseUrl;
  private final @Nullable String elsevierApiKey;
  private final @Nullable String elsevierInsttoken;
  private final int elsevierDailyCap;
  private final String wileyBaseUrl;
  private final @Nullable String wileyToken;
  private final int wileyDailyCap;

  public InstitutionalProperties(
      @Value("${nametracker.content.institutional.enabled:false}") boolean enabled,
      @Value("${nametracker.content.institutional.publishers:}") String publishers,
      @Value("${nametracker.content.institutional.grobid-url:}") String grobidUrl,
      @Value("${nametracker.content.institutional.refetch-after:365d}") Duration refetchAfter,
      @Value("${nametracker.content.institutional.elsevier.base-url:https://api.elsevier.com}") String elsevierBaseUrl,
      @Value("${nametracker.content.institutional.elsevier.api-key:}") String elsevierApiKey,
      @Value("${nametracker.content.institutional.elsevier.insttoken:}") String elsevierInsttoken,
      @Value("${nametracker.content.institutional.elsevier.daily-cap:500}") int elsevierDailyCap,
      @Value("${nametracker.content.institutional.wiley.base-url:https://api.wiley.com}") String wileyBaseUrl,
      @Value("${nametracker.content.institutional.wiley.token:}") String wileyToken,
      @Value("${nametracker.content.institutional.wiley.daily-cap:500}") int wileyDailyCap) {
    this.enabled = enabled;
    this.publishers = parse(publishers);
    this.grobidUrl = blankToNull(grobidUrl);
    this.refetchAfter = refetchAfter;
    this.elsevierBaseUrl = trimSlash(elsevierBaseUrl);
    this.elsevierApiKey = blankToNull(elsevierApiKey);
    this.elsevierInsttoken = blankToNull(elsevierInsttoken);
    this.elsevierDailyCap = elsevierDailyCap;
    this.wileyBaseUrl = trimSlash(wileyBaseUrl);
    this.wileyToken = blankToNull(wileyToken);
    this.wileyDailyCap = wileyDailyCap;
  }

  /** The upper-cased names of a comma-separated list, in order, blanks dropped. */
  static Set<String> parse(@Nullable String csv) {
    Set<String> s = new LinkedHashSet<>();
    if (csv == null) return s;
    Arrays.stream(csv.split(",")).map(String::strip).filter(x -> !x.isEmpty())
        .map(x -> x.toUpperCase(Locale.ROOT)).forEach(s::add);
    return s;
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }

  private static String trimSlash(String s) {
    String t = s.strip();
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
  }

  /** The master switch. */
  public boolean enabled() {
    return enabled;
  }

  /** The allow-list in its order, upper case ({@code ELSEVIER}, {@code WILEY}); empty by default. */
  public List<String> publishers() {
    return List.copyOf(publishers);
  }

  /** The local GROBID that turns Wiley's PDFs into TEI; null leaves Wiley out. */
  public @Nullable String grobidUrl() {
    return grobidUrl;
  }

  /** How long a definitive answer for a work keeps the route from asking for it again (except to reprocess). */
  public Duration refetchAfter() {
    return refetchAfter;
  }

  public String elsevierBaseUrl() {
    return elsevierBaseUrl;
  }

  public @Nullable String elsevierApiKey() {
    return elsevierApiKey;
  }

  /** Optional: inside the KU network the IP range authorises, outside it the institutional token does. */
  public @Nullable String elsevierInsttoken() {
    return elsevierInsttoken;
  }

  public String wileyBaseUrl() {
    return wileyBaseUrl;
  }

  public @Nullable String wileyToken() {
    return wileyToken;
  }

  /** The daily cap (fetches a UTC day) of a publisher; 0 for an unknown one. */
  public int dailyCap(String publisher) {
    return switch (publisher) {
      case ElsevierTdm.PUBLISHER -> elsevierDailyCap;
      case WileyTdm.PUBLISHER -> wileyDailyCap;
      default -> 0;
    };
  }
}
