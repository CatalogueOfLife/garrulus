package org.catalogueoflife.nametracker.store.ident;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;

/** An identifier scheme of a work and the rule that turns a raw value into its normalised form. */
public final class IdScheme {

  private static final Pattern DOI_RE = Pattern.compile("^10\\.\\d{4,9}/\\S+$");
  private static final Pattern OPENALEX_RE = Pattern.compile("^(?:https?://openalex\\.org/)?([A-Za-z]\\d+)$");
  private static final Pattern PMCID_RE = Pattern.compile("^(?:PMC)?(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PMID_RE = Pattern.compile("^(?:PMID:?\\s*)?(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern MAGTECH_RE =
      Pattern.compile("^https?://([^/\\s]+)/.*?abstract(\\d+)\\.shtml(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern BLR_RE =
      Pattern.compile("^(?:https?://(?:www\\.)?zenodo\\.org/records?/)?(\\d+)/?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern OAI_RE = Pattern.compile("^oai:\\S+$", Pattern.CASE_INSENSITIVE);

  public static final IdScheme DOI = new IdScheme("DOI", raw -> {
    String d = WorkUpsert.normalizeDoi(raw);
    return d != null && DOI_RE.matcher(d).matches() ? d : null;
  });
  public static final IdScheme OPENALEX = new IdScheme("OPENALEX", raw -> group(OPENALEX_RE, raw.strip(), 1, true));
  public static final IdScheme OAI = new IdScheme("OAI", raw -> {
    String s = raw.strip();
    return OAI_RE.matcher(s).matches() ? s : null;
  });
  public static final IdScheme PMID = new IdScheme("PMID", raw -> group(PMID_RE, raw.strip(), 1, false));
  public static final IdScheme PMCID = new IdScheme("PMCID", raw -> {
    String n = group(PMCID_RE, raw.strip(), 1, false);
    return n == null ? null : "PMC" + n;
  });
  public static final IdScheme BLR = new IdScheme("BLR", raw -> group(BLR_RE, raw.strip(), 1, false));
  public static final IdScheme MAGTECH = new IdScheme("MAGTECH", raw -> {
    Matcher m = MAGTECH_RE.matcher(raw.strip());
    return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) + ":" + m.group(2) : null;
  });

  private static final List<IdScheme> ALL = List.of(DOI, OPENALEX, OAI, PMID, PMCID, BLR, MAGTECH);

  private final String code;
  private final Function<String, @Nullable String> normaliser;

  private IdScheme(String code, Function<String, @Nullable String> normaliser) {
    this.code = code;
    this.normaliser = normaliser;
  }

  private static @Nullable String group(Pattern p, String s, int g, boolean upper) {
    Matcher m = p.matcher(s);
    if (!m.matches()) {
      return null;
    }
    String v = m.group(g);
    return upper ? v.toUpperCase(Locale.ROOT) : v;
  }

  public String code() {
    return code;
  }

  /** The normalised value, or null when the raw value is null, blank or not of this scheme. */
  public @Nullable String normalise(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return normaliser.apply(raw);
  }

  public static IdScheme of(String code) {
    for (IdScheme s : ALL) {
      if (s.code.equals(code)) {
        return s;
      }
    }
    throw new IllegalArgumentException("Unknown identifier scheme " + code);
  }

  /** The identifier of the raw value, or null when it does not normalise. */
  public static @Nullable Identifier id(IdScheme scheme, @Nullable String raw) {
    String v = scheme.normalise(raw);
    return v == null ? null : new Identifier(scheme.code, v);
  }

  @Override
  public String toString() {
    return code;
  }
}
