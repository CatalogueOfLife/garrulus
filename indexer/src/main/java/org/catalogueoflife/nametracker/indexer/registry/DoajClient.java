package org.catalogueoflife.nametracker.indexer.registry;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.discipline.RawClassification;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The DOAJ search API v4 (free, 2 requests a second, spaced by {@link PoliteHttp}): journals by ISSN and by LCC subject.
 */
public class DoajClient {

  public static final String LCC = "DOAJ_LCC";
  public static final int PAGE_SIZE = 100;

  /** One DOAJ journal: {@code issns} print then electronic, {@code lcc} its LCC subjects as raw rows. */
  public record DoajJournal(String id, String title, List<String> issns, @Nullable String publisher,
      @Nullable String country, List<RawClassification> lcc, @Nullable String licenceUrl,
      @Nullable LocalDate discontinued, List<String> replacedBy, @Nullable String homepage, List<String> languages) {
  }

  /** One page of a search and the total hit count. */
  public record DoajPage(List<DoajJournal> journals, int total) {
  }

  private final PoliteHttp http;
  private final String base;

  /** {@code baseUrl}: {@code https://doaj.org/api/v4}. */
  public DoajClient(PoliteHttp http, String baseUrl) {
    this.http = http;
    this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  /** The journal with this print or electronic ISSN; empty when DOAJ does not list it. */
  public Optional<DoajJournal> byIssn(String issn) {
    return search("issn:" + issn, 1, 10).journals().stream().findFirst();
  }

  /** One page (1-based, 100 journals) of the journals with an LCC subject code such as {@code QL1-991}. */
  public DoajPage journalsByLcc(String code, int page) {
    return search("bibjson.subject.code:\"" + code + "\"", page, PAGE_SIZE);
  }

  private DoajPage search(String query, int page, int pageSize) {
    URI uri = URI.create(base + "/search/journals/" + URLEncoder.encode(query, StandardCharsets.UTF_8)
        .replace("+", "%20").replace("%3A", ":") + "?page=" + page + "&pageSize=" + pageSize);
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "application/json"));
    int status = resp.statusCode();
    if (status != 200) {
      throw new HttpProblem(uri, status, status >= 500 || status == 429, "HTTP " + status + " from DOAJ: " + uri, null);
    }
    JsonNode root;
    try {
      root = FinderJson.mapper().readTree(resp.body());
    } catch (RuntimeException e) {
      throw new HttpProblem(uri, status, false, "Unreadable DOAJ response: " + uri, e);
    }
    List<DoajJournal> journals = new ArrayList<>();
    for (JsonNode r : root.path("results")) journals.add(journal(r));
    return new DoajPage(journals, root.path("total").asInt(0));
  }

  static DoajJournal journal(JsonNode r) {
    JsonNode b = r.path("bibjson");
    List<String> issns = new ArrayList<>();
    for (String f : List.of("pissn", "eissn")) {
      String i = Issn.normalise(b.path(f).asString(""));
      if (i != null && !issns.contains(i)) issns.add(i);
    }
    List<RawClassification> lcc = new ArrayList<>();
    for (JsonNode sub : b.path("subject")) {
      String code = text(sub, "code");
      if (code == null || !"LCC".equals(text(sub, "scheme"))) continue;
      if (lcc.stream().noneMatch(c -> c.code().equals(code))) {
        lcc.add(new RawClassification(LCC, code, text(sub, "term"), null, lcc.size()));
      }
    }
    String licence = null;
    for (JsonNode l : b.path("license")) {
      licence = text(l, "url");
      if (licence != null) break;
    }
    List<String> replacedBy = new ArrayList<>();
    for (JsonNode i : b.path("is_replaced_by")) {
      String n = Issn.normalise(i.asString(""));
      if (n != null && !replacedBy.contains(n)) replacedBy.add(n);
    }
    List<String> languages = new ArrayList<>();
    for (JsonNode l : b.path("language")) {
      String code = LanguageCodes.normalise(l.asString(""));
      if (code != null && !languages.contains(code)) languages.add(code);
    }
    String title = text(b, "title");
    return new DoajJournal(r.path("id").asString(""), title == null ? "" : title, issns,
        text(b.path("publisher"), "name"), text(b.path("publisher"), "country"), lcc, licence,
        date(text(b, "discontinued_date")), replacedBy, text(b.path("ref"), "journal"), languages);
  }

  private static @Nullable LocalDate date(@Nullable String s) {
    if (s == null) return null;
    try {
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isString() && !v.asString().isBlank() ? v.asString().strip() : null;
  }
}
