package org.catalogueoflife.garrulus.store.ident;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.catalogueoflife.garrulus.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;

/** An identifier scheme of a work or a journal, its label, link templates and the rule that turns a raw value into its normalised form. */
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

  private static final Pattern PLAZI_RE = Pattern.compile("^[0-9A-Fa-f]{32}$");
  private static final Pattern BPMC_RE = Pattern.compile("^(pmc|medline)/(?:PMC)?(\\d+)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern WIKIDATA_RE = Pattern.compile("^Q\\d+$");
  private static final Pattern IPNI_RE = Pattern.compile("^\\d+-\\d$");
  private static final Pattern DOAJ_ID_RE = Pattern.compile("^[0-9a-fA-F]{32}$");
  private static final Pattern HTTP_TEMPLATE = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
  private static final Pattern AUTHORITY_PART = Pattern.compile("^https?://\\{0}", Pattern.CASE_INSENSITIVE);
  private static final Pattern PLAIN_HOST = Pattern.compile("^[A-Za-z0-9.-]+$");
  private static final Set<Entity> WORK_ONLY = EnumSet.of(Entity.WORK);
  private static final Set<Entity> SOURCE_ONLY = EnumSet.of(Entity.SOURCE);

  public static final IdScheme DOI = builtin("DOI", "DOI", WORK_ONLY, raw -> {
    String d = WorkUpsert.normalizeDoi(raw);
    return d != null && DOI_RE.matcher(d).matches() ? d : null;
  }, Map.of(Entity.WORK, "https://doi.org/{v}"), 10, true);
  public static final IdScheme OPENALEX = builtin("OPENALEX", "OpenAlex", EnumSet.of(Entity.WORK, Entity.SOURCE), raw -> group(OPENALEX_RE, raw.strip(), 1, true),
      Map.of(Entity.WORK, "https://openalex.org/works/{v}", Entity.SOURCE, "https://openalex.org/sources/{v}"), 30, true);
  public static final IdScheme OAI = builtin("OAI", "OAI-PMH", WORK_ONLY, raw -> {
    String s = raw.strip();
    return OAI_RE.matcher(s).matches() ? s : null;
  }, Map.of(), 0, false);
  public static final IdScheme PMID = builtin("PMID", "PubMed", WORK_ONLY, raw -> group(PMID_RE, raw.strip(), 1, false),
      Map.of(Entity.WORK, "https://europepmc.org/article/MED/{v}"), 40, true);
  public static final IdScheme PMCID = builtin("PMCID", "PubMed Central", WORK_ONLY, raw -> {
    String n = group(PMCID_RE, raw.strip(), 1, false);
    return n == null ? null : "PMC" + n;
  }, Map.of(Entity.WORK, "https://europepmc.org/articles/{v}"), 50, true);
  public static final IdScheme BLR = builtin("BLR", "BLR (Zenodo)", WORK_ONLY, raw -> group(BLR_RE, raw.strip(), 1, false),
      Map.of(Entity.WORK, "https://zenodo.org/records/{v}"), 70, true);
  public static final IdScheme MAGTECH = builtin("MAGTECH", "Magtech", WORK_ONLY, raw -> {
    Matcher m = MAGTECH_RE.matcher(raw.strip());
    return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) + ":" + m.group(2) : null;
  }, Map.of(Entity.WORK, "https://{0}/EN/abstract/abstract{1}.shtml"), 100, true);


  public static final IdScheme CROSSREF = builtin("CROSSREF", "Crossref", WORK_ONLY, raw -> DOI.normalise(raw),
      Map.of(Entity.WORK, "https://api.crossref.org/works/{v}"), 20, true);
  public static final IdScheme PLAZI = builtin("PLAZI", "Plazi", WORK_ONLY, raw -> {
    String s = raw.strip();
    return PLAZI_RE.matcher(s).matches() ? s.toUpperCase(Locale.ROOT) : null;
  }, Map.of(Entity.WORK, "https://tb.plazi.org/GgServer/summary/{v}"), 80, true);
  public static final IdScheme BIODIVERSITY_PMC = builtin("BIODIVERSITY_PMC", "Biodiversity PMC", WORK_ONLY, raw -> {
    Matcher m = BPMC_RE.matcher(raw.strip());
    if (!m.matches()) {
      return null;
    }
    boolean pmc = m.group(1).equalsIgnoreCase("pmc");
    return (pmc ? "pmc/PMC" : "medline/") + m.group(2);
  }, Map.of(Entity.WORK, "https://biodiversitypmc.sibils.org/{v}"), 60, true);
  public static final IdScheme BHL_PART = builtin("BHL_PART", "BHL part", WORK_ONLY, raw -> digits(raw),
      Map.of(Entity.WORK, "https://www.biodiversitylibrary.org/part/{v}"), 90, true);

  public static final IdScheme ISSN = builtin("ISSN", "ISSN", SOURCE_ONLY, Issn::normalise,
      Map.of(Entity.SOURCE, "https://portal.issn.org/resource/ISSN/{v}"), 10, true);
  public static final IdScheme ISSN_L = builtin("ISSN_L", "ISSN-L", SOURCE_ONLY, Issn::normalise,
      Map.of(Entity.SOURCE, "https://portal.issn.org/resource/ISSN/{v}"), 15, true);
  public static final IdScheme CROSSREF_JOURNAL = builtin("CROSSREF_JOURNAL", "Crossref journal", SOURCE_ONLY, Issn::normalise,
      Map.of(Entity.SOURCE, "https://api.crossref.org/journals/{v}"), 40, true);
  public static final IdScheme WIKIDATA = builtin("WIKIDATA", "Wikidata", SOURCE_ONLY, raw -> {
    String s = raw.strip().toUpperCase(Locale.ROOT);
    return WIKIDATA_RE.matcher(s).matches() ? s : null;
  }, Map.of(Entity.SOURCE, "https://www.wikidata.org/wiki/{v}"), 50, true);
  public static final IdScheme IPNI_PUB = builtin("IPNI_PUB", "IPNI publication", SOURCE_ONLY, raw -> {
    String s = raw.strip();
    return IPNI_RE.matcher(s).matches() ? s : null;
  }, Map.of(Entity.SOURCE, "https://www.ipni.org/p/{v}"), 60, true);
  /** Biodiversity Publishing History id (Wikidata P4569): no per-record page, shown as text. */
  public static final IdScheme BPH = builtin("BPH", "BPH", SOURCE_ONLY, raw -> digits(raw), Map.of(), 70, true);
  public static final IdScheme BHL_TITLE = builtin("BHL_TITLE", "BHL title", SOURCE_ONLY, raw -> digits(raw),
      Map.of(Entity.SOURCE, "https://www.biodiversitylibrary.org/bibliography/{v}"), 80, true);
  public static final IdScheme DOAJ = builtin("DOAJ", "DOAJ", SOURCE_ONLY, raw -> {
    String s = raw.strip();
    if (DOAJ_ID_RE.matcher(s).matches()) {
      return s.toLowerCase(Locale.ROOT);
    }
    return Issn.normalise(s);
  }, Map.of(Entity.SOURCE, "https://doaj.org/toc/{v}"), 90, true);

  private static final List<IdScheme> ALL = List.of(DOI, CROSSREF, OPENALEX, OAI, PMID, PMCID, BIODIVERSITY_PMC, BLR,
      PLAZI, BHL_PART, MAGTECH, ISSN, ISSN_L, CROSSREF_JOURNAL, WIKIDATA, IPNI_PUB, BPH, BHL_TITLE, DOAJ);

  /** The schemes the code knows; the registry adds extras to these. */
  public static List<IdScheme> builtIns() {
    return ALL;
  }

  /** What an identifier belongs to: a work or a source (journal). */
  public enum Entity { WORK, SOURCE }

  private final String code;
  private final String label;
  private final Set<Entity> entities;
  private final Function<String, @Nullable String> normaliser;
  private final Map<Entity, String> urlTemplates;
  private final int sortOrder;
  private final boolean shown;

  private IdScheme(String code, String label, Set<Entity> entities, Function<String, @Nullable String> normaliser,
      Map<Entity, String> urlTemplates, int sortOrder, boolean shown) {
    this.code = code;
    this.label = label;
    this.entities = Set.copyOf(entities);
    this.normaliser = normaliser;
    this.urlTemplates = Map.copyOf(urlTemplates);
    this.sortOrder = sortOrder;
    this.shown = shown;
  }

  private static IdScheme builtin(String code, String label, Set<Entity> entities,
      Function<String, @Nullable String> normaliser, Map<Entity, String> urlTemplates, int sortOrder, boolean shown) {
    return new IdScheme(code, label, entities, normaliser, urlTemplates, sortOrder, shown);
  }

  /**
   * A scheme defined outside this class (a test, or a bean of either app): add it to the {@code IdentifierRegistry} and
   * it is parsed, linked and listed without a migration. Templates use {@code {v}} for the value, or {@code {0}} and
   * {@code {1}} for the parts of a value split at its last colon.
   */
  public static IdScheme define(String code, String label, Set<Entity> entities,
      Function<String, @Nullable String> normaliser, Map<Entity, String> urlTemplates, int sortOrder, boolean shown) {
    for (String t : urlTemplates.values()) {
      if (!HTTP_TEMPLATE.matcher(t).find()) {
        throw new IllegalArgumentException("URL template of " + code + " must start with http:// or https://: " + t);
      }
    }
    return new IdScheme(code, label, entities, normaliser, urlTemplates, sortOrder, shown);
  }

  private static @Nullable String digits(String raw) {
    String s = raw.strip();
    return s.matches("\\d+") ? s : null;
  }

  public String label() {
    return label;
  }

  public Set<Entity> entities() {
    return entities;
  }

  public int sortOrder() {
    return sortOrder;
  }

  public boolean shown() {
    return shown;
  }

  /** The page of a value of this scheme for the entity, or null when there is no such page. */
  public @Nullable String url(Entity e, String value) {
    String t = urlTemplates.get(e);
    if (t == null) {
      return null;
    }
    int i = value.lastIndexOf(':');
    if (t.contains("{0}")) {
      if (i < 0) {
        return null;
      }
      if (AUTHORITY_PART.matcher(t).find() && !PLAIN_HOST.matcher(value.substring(0, i)).matches()) {
        return null;
      }
      return t.replace("{0}", encodePathValue(value.substring(0, i))).replace("{1}", encodePathValue(value.substring(i + 1)));
    }
    return t.replace("{v}", encodePathValue(value));
  }

  /** RFC 3986 path encoding: unreserved, sub-delims, ':', '@' and '/' kept, everything else as UTF-8 escapes. */
  public static String encodePathValue(String v) {
    StringBuilder sb = new StringBuilder();
    for (byte b : v.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if (c < 0x80 && (Character.isLetterOrDigit(c) || "-._~!$&'()*+,;=:@/".indexOf(c) >= 0)) {
        sb.append((char) c);
      } else {
        sb.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
      }
    }
    return sb.toString();
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
