package org.catalogueoflife.nametracker.indexer.content.topup;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.indexer.link.LinkHttp;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * SciELO ArticleMeta for the DOIs of its collections ({@code nametracker.content.topup.scielo.collections}, a list of
 * {@code <doi prefix>=<collection>}, start {@code 10.1590/=scl}): the journal's article identifiers of the window from
 * 30 days before to a year after the publication date (up to 3 pages of 100) give the article's PID by DOI, then
 * {@code /api/v1/article/?code=<pid>&collection=<c>} gives the abstracts ({@code v83}) and titles ({@code v12}) by
 * language, stored as PARALLEL texts, and the article's language ({@code v40}), which is DECLARED evidence of SCIELO.
 * The abstract in the article's language is the work's abstract when it has none. Asked once per work even when the
 * work has an abstract.
 */
@Component
@Order(30)
public class ScieloAbstract implements AbstractTopUp {

  public static final String SERVICE = "SCIELO";
  static final int PAGES = 3;
  static final int PAGE_SIZE = 100;
  private static final Pattern LABEL = Pattern.compile("^(?:ABSTRACT|RESUMO|RESUMEN|RÉSUMÉ|RESUME)\\b[:.\\s]*");

  private final PoliteHttp http;
  private final String base;
  private final Map<String, String> collections = new LinkedHashMap<>();

  @Autowired
  public ScieloAbstract(PoliteHttp http,
      @Value("${nametracker.content.topup.scielo.base-url:https://articlemeta.scielo.org}") String baseUrl,
      @Value("${nametracker.content.topup.scielo.collections:10.1590/=scl}") String collections) {
    this.http = http;
    this.base = LinkHttp.trimSlash(baseUrl);
    for (String e : collections.split(",")) {
      int i = e.indexOf('=');
      if (i > 0 && i < e.length() - 1) {
        this.collections.put(e.substring(0, i).strip().toLowerCase(Locale.ROOT), e.substring(i + 1).strip());
      }
    }
  }

  @Override
  public String service() {
    return SERVICE;
  }

  @Override
  public boolean enabled() {
    return !collections.isEmpty();
  }

  @Override
  public boolean always() {
    return true;
  }

  private @Nullable String collection(Work w) {
    if (w.doi() == null) return null;
    String d = w.doi().toLowerCase(Locale.ROOT);
    for (Map.Entry<String, String> e : collections.entrySet()) {
      String prefix = e.getKey().endsWith("/") ? e.getKey() : e.getKey() + "/";
      if (d.startsWith(prefix)) return e.getValue();
    }
    return null;
  }

  private static Set<String> issns(@Nullable Source s) {
    Set<String> out = new LinkedHashSet<>();
    if (s != null) {
      if (s.issns() != null) out.addAll(s.issns());
      if (s.issnL() != null) out.add(s.issnL());
    }
    return out;
  }

  private static @Nullable LocalDate published(Work w) {
    if (w.publicationDate() != null) return w.publicationDate();
    return w.year() == null ? null : LocalDate.of(w.year(), 1, 1);
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return collection(w) != null && !issns(s).isEmpty() && published(w) != null;
  }

  @Override
  public Optional<TopUpResult> fetch(Work w, @Nullable Source s) {
    String collection = collection(w);
    LocalDate pub = published(w);
    if (collection == null || pub == null) return Optional.empty();
    String pid = pid(w.doi(), collection, issns(s), pub.minusDays(30), pub.plusDays(365));
    if (pid == null) return Optional.empty();
    URI uri = URI.create(base + "/api/v1/article/?code=" + LinkHttp.enc(pid) + "&collection=" + LinkHttp.enc(collection));
    JsonNode art = LinkHttp.json(http, uri, "application/json", "SciELO ArticleMeta").path("article");
    return Optional.ofNullable(result(art));
  }

  /** The PID of the article with the DOI among the window's identifiers of the journal's ISSNs, or null. */
  private @Nullable String pid(String doi, String collection, Set<String> issns, LocalDate from, LocalDate until) {
    for (String issn : issns) {
      for (int page = 0; page < PAGES; page++) {
        URI uri = URI.create(base + "/api/v1/article/identifiers/?collection=" + LinkHttp.enc(collection) + "&issn="
            + LinkHttp.enc(issn) + "&from=" + from + "&until=" + until + "&limit=" + PAGE_SIZE + "&offset="
            + page * PAGE_SIZE);
        JsonNode root = LinkHttp.json(http, uri, "application/json", "SciELO ArticleMeta");
        for (JsonNode o : root.path("objects")) {
          if (doi.equalsIgnoreCase(o.path("doi").asString("").strip())) {
            String code = o.path("code").asString("").strip();
            if (!code.isEmpty()) return code;
          }
        }
        int total = root.path("meta").path("total").asInt(0);
        if ((page + 1) * PAGE_SIZE >= total) break;
      }
    }
    return null;
  }

  /** The abstracts, titles and language of an ArticleMeta {@code article} object; null when it has none of them. */
  static @Nullable TopUpResult result(JsonNode art) {
    String declared = art.path("v40").path(0).path("_").asString("").strip();
    Map<String, String> abstracts = new LinkedHashMap<>();
    for (JsonNode a : art.path("v83")) {
      String text = LABEL.matcher(a.path("a").asString("").strip()).replaceFirst("").strip();
      String lang = a.path("l").asString("").strip();
      if (!text.isEmpty() && !lang.isEmpty()) abstracts.putIfAbsent(lang, text);
    }
    List<TextIn> parallels = new ArrayList<>();
    for (Map.Entry<String, String> e : abstracts.entrySet()) {
      parallels.add(new TextIn("ABSTRACT", e.getKey(), "PARALLEL", e.getValue()));
    }
    for (JsonNode t : art.path("v12")) {
      String text = t.path("_").asString("").strip();
      String lang = t.path("l").asString("").strip();
      if (!text.isEmpty() && !lang.isEmpty()) parallels.add(new TextIn("TITLE", lang, "PARALLEL", text));
    }
    String lang = !declared.isEmpty() && abstracts.containsKey(declared) ? declared
        : abstracts.isEmpty() ? null : abstracts.keySet().iterator().next();
    String text = lang == null ? null : abstracts.get(lang);
    TopUpResult r = new TopUpResult(text, null, lang, "SCIELO", parallels, declared.isEmpty() ? null : declared);
    return r.found() ? r : null;
  }
}
