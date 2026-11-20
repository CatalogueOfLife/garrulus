package org.catalogueoflife.nametracker.indexer.crossref;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.work.TitleText;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Maps a Crossref work item (fetched with {@link CrossrefClient#SELECT}) to a {@link WorkRecord} without OpenAlex ids.
 * {@code openalexHasTei} stays null so a merge never resets what OpenAlex said.
 */
public final class CrossrefWorks {

  private CrossrefWorks() {
  }

  /** The record's identifiers and its Crossref DOI: a work the Crossref layer stores is a Crossref work. */
  public static List<Identifier> identifiers(WorkRecord rec) {
    List<Identifier> ids = new ArrayList<>(rec.identifiers());
    Identifier c = IdScheme.id(IdScheme.CROSSREF, rec.doi());
    if (c != null) ids.add(c);
    return ids;
  }

  public static WorkRecord parse(JsonNode w) {
    String doi = WorkUpsert.normalizeDoi(text(w, "DOI"));
    if (doi == null) throw new IllegalArgumentException("Crossref item without a DOI");
    JsonNode parts = w.path("published").path("date-parts").path(0);
    Short year = parts.path(0).isNumber() ? (short) parts.path(0).asInt() : null;
    LocalDate date = null;
    if (year != null && parts.path(1).isNumber() && parts.path(2).isNumber()) {
      try {
        date = LocalDate.of(year, parts.path(1).asInt(), parts.path(2).asInt());
      } catch (DateTimeException e) {
        date = null;
      }
    }
    return new WorkRecord(null, doi, TitleText.clean(first(w, "title")), date, year, text(w, "volume"), text(w, "issue"),
        text(w, "page"), authors(w.path("author")), "journal-article".equals(text(w, "type")) ? "article" : text(w, "type"),
        null, null, license(w.path("license")), null, null, text(w, "abstract"), null, null, null);
  }

  /**
   * The versions of the title the item carries besides its {@code title}: {@code original-title} (the title in the
   * article's own language when {@code title} is a translation) as an ORIGINAL TITLE without a language (the pager
   * detects it, and keeps it only when the detector is confident and the language differs from the title's).
   */
  public static List<TextIn> texts(JsonNode w) {
    String title = TitleText.clean(first(w, "title"));
    String original = TitleText.clean(first(w, "original-title"));
    if (original == null || original.isBlank() || original.equals(title)) return List.of();
    return List.of(new TextIn("TITLE", null, "ORIGINAL", original, org.catalogueoflife.nametracker.store.work.Layer.CROSSREF));
  }

  /**
   * The latest day the item can have been published: the full {@code published} date, 31 December for a year only,
   * the last day of the month for a year and month. Null without a usable year.
   */
  public static @Nullable LocalDate latestPublished(JsonNode w) {
    JsonNode parts = w.path("published").path("date-parts").path(0);
    if (!parts.path(0).isNumber()) return null;
    int year = parts.path(0).asInt();
    try {
      if (!parts.path(1).isNumber()) return LocalDate.of(year, 12, 31);
      LocalDate first = LocalDate.of(year, parts.path(1).asInt(), 1);
      if (!parts.path(2).isNumber()) return first.withDayOfMonth(first.lengthOfMonth());
      return LocalDate.of(year, parts.path(1).asInt(), parts.path(2).asInt());
    } catch (DateTimeException e) {
      return null;
    }
  }

  /** The ISSNs of the item (print and electronic). */
  public static List<String> issns(JsonNode w) {
    List<String> out = new ArrayList<>();
    for (JsonNode i : w.path("ISSN")) {
      String s = i.asString("").strip().toUpperCase(Locale.ROOT);
      if (!s.isEmpty()) out.add(s);
    }
    return out;
  }

  /** {@code cc-by}, {@code cc-by-sa}, {@code cc0}, {@code public-domain} … from a Creative Commons licence URL. */
  static @Nullable String license(JsonNode licenses) {
    for (JsonNode l : licenses) {
      String u = l.path("URL").asString("").toLowerCase(Locale.ROOT);
      int i = u.indexOf("creativecommons.org/licenses/");
      if (i >= 0) {
        String code = u.substring(i + "creativecommons.org/licenses/".length());
        int slash = code.indexOf('/');
        code = slash < 0 ? code : code.substring(0, slash);
        if (!code.isEmpty()) return "cc-" + code;
      }
      if (u.contains("creativecommons.org/publicdomain/zero")) return "cc0";
      if (u.contains("creativecommons.org/publicdomain/mark")) return "public-domain";
    }
    return null;
  }

  private static String authors(JsonNode authors) {
    List<Map<String, String>> out = new ArrayList<>();
    for (JsonNode a : authors) {
      String given = text(a, "given");
      String family = text(a, "family");
      String name = given == null ? family : family == null ? given : given + " " + family;
      if (name == null) name = text(a, "name");
      if (name == null) continue;
      String orcid = text(a, "ORCID");
      if (orcid != null && orcid.contains("orcid.org/")) orcid = orcid.substring(orcid.indexOf("orcid.org/") + 10);
      Map<String, String> m = new LinkedHashMap<>();
      m.put("name", name);
      m.put("orcid", orcid);
      out.add(m);
    }
    return FinderJson.mapper().writeValueAsString(out);
  }

  private static @Nullable String first(JsonNode n, String field) {
    JsonNode a = n.path(field);
    if (!a.isArray() || a.isEmpty()) return null;
    String s = a.get(0).asString("").strip();
    return s.isEmpty() ? null : s;
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString().strip();
    return s.isEmpty() ? null : s;
  }
}
