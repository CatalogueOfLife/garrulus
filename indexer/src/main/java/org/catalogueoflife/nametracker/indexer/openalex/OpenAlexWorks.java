package org.catalogueoflife.nametracker.indexer.openalex;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.doc.AbstractConverter;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/** Maps an OpenAlex work object (fetched with {@link #SELECT}) to a {@link WorkRecord}. */
public final class OpenAlexWorks {

  public static final String SELECT = "id,doi,title,publication_date,biblio,authorships,type,language,open_access,"
      + "best_oa_location,primary_location,abstract_inverted_index,ids,has_content";

  private static final String ID_PREFIX = "https://openalex.org/";

  private OpenAlexWorks() {
  }

  public static WorkRecord parse(JsonNode w) {
    LocalDate date = date(text(w, "publication_date"));
    JsonNode biblio = w.path("biblio");
    String first = text(biblio, "first_page");
    String last = text(biblio, "last_page");
    String pages = first == null ? null : (last == null || last.equals(first) ? first : first + "-" + last);

    String abstractText = null;
    JsonNode inv = w.path("abstract_inverted_index");
    if (inv.isObject()) {
      Map<String, List<Integer>> index = FinderJson.mapper().convertValue(inv, new TypeReference<>() { });
      String a = AbstractConverter.fromInvertedIndex(index);
      abstractText = a.isBlank() ? null : a;
    }

    String license = text(w.path("best_oa_location"), "license");
    if (license == null) license = text(w.path("primary_location"), "license");

    return new WorkRecord(
        shortId(text(w, "id")),
        WorkUpsert.normalizeDoi(text(w, "doi")),
        text(w, "title"),
        date,
        date == null ? null : (short) date.getYear(),
        text(biblio, "volume"),
        text(biblio, "issue"),
        pages,
        authors(w.path("authorships")),
        text(w, "type"),
        text(w, "language"),
        text(w.path("open_access"), "oa_status"),
        license == null ? null : license.strip().toLowerCase(Locale.ROOT),
        bestOaUrl(w),
        abstractText,
        null,
        pmcid(text(w.path("ids"), "pmcid")),
        w.path("has_content").path("grobid_xml").asBoolean(false),
        shortId(text(w.path("primary_location").path("source"), "id")));
  }

  private static @Nullable String bestOaUrl(JsonNode w) {
    String u = text(w.path("open_access"), "oa_url");
    if (u == null) u = text(w.path("best_oa_location"), "pdf_url");
    if (u == null) u = text(w.path("best_oa_location"), "landing_page_url");
    return u;
  }

  /** The trailing segment of an OpenAlex id URL, e.g. {@code W123}. */
  static @Nullable String shortId(@Nullable String id) {
    if (id == null) return null;
    return id.startsWith(ID_PREFIX) ? id.substring(ID_PREFIX.length()) : id;
  }

  /** The trailing segment of the PMC article URL; bare digits get the PMC prefix. */
  static @Nullable String pmcid(@Nullable String url) {
    if (url == null) return null;
    String s = url.strip();
    while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
    s = s.substring(s.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
    if (s.isEmpty()) return null;
    if (s.chars().allMatch(Character::isDigit)) return "PMC" + s;
    return s.startsWith("PMC") ? s : null;
  }

  private static String authors(JsonNode authorships) {
    List<Map<String, String>> out = new ArrayList<>();
    for (JsonNode a : authorships) {
      JsonNode author = a.path("author");
      String name = text(author, "display_name");
      if (name == null) name = text(a, "raw_author_name");
      if (name == null) continue;
      String orcid = text(author, "orcid");
      if (orcid != null && orcid.contains("orcid.org/")) orcid = orcid.substring(orcid.indexOf("orcid.org/") + 10);
      Map<String, String> m = new java.util.LinkedHashMap<>();
      m.put("name", name);
      m.put("orcid", orcid);
      out.add(m);
    }
    return FinderJson.mapper().writeValueAsString(out);
  }

  private static @Nullable LocalDate date(@Nullable String s) {
    if (s == null) return null;
    try {
      return LocalDate.parse(s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString().strip();
    return s.isEmpty() ? null : s;
  }
}
