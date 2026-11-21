package org.catalogueoflife.nametracker.indexer.content.topup;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
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
 * {@code <doi prefix>=<collection>}, start {@code 10.1590/=scl}). SciELO registers an article's PID (for example
 * {@code S1984-46702025000100400}) as an {@code alternative-id} of its Crossref record, so the PID is read from Crossref's
 * {@code /works/{doi}} (ArticleMeta's identifiers listing has no DOI and filters by processing date). Then
 * {@code /api/v1/article/?code=<pid>&collection=<c>} gives the abstracts ({@code v83}) and titles ({@code v12}) by
 * language, stored as PARALLEL texts, and the article's language ({@code v40}), which is DECLARED evidence of SCIELO.
 * The article is taken only when its own DOI (top-level {@code doi}, else {@code v237}) equals the work's. The abstract
 * in the article's language is the work's abstract when it has none. Asked once per work even when the work has an
 * abstract. A Crossref 404, no PID-shaped alternative-id, another DOI or an article without abstracts is a miss; 5xx, 429
 * and IO errors of either service are outages.
 */
@Component
@Order(30)
public class ScieloAbstract implements AbstractTopUp {

  public static final String SERVICE = "SCIELO";
  private static final Pattern PID = Pattern.compile("^S\\d{4}-\\d{3}[\\dX]\\d{13}$", Pattern.CASE_INSENSITIVE);
  private static final Pattern LABEL = Pattern.compile("^(?:ABSTRACT|RESUMO|RESUMEN|RÉSUMÉ|RESUME)\\b[:.\\s]*");

  private final PoliteHttp http;
  private final CrossrefClient crossref;
  private final String base;
  private final Map<String, String> collections = new LinkedHashMap<>();

  @Autowired
  public ScieloAbstract(PoliteHttp http, CrossrefClient crossref,
      @Value("${nametracker.content.topup.scielo.base-url:https://articlemeta.scielo.org}") String baseUrl,
      @Value("${nametracker.content.topup.scielo.collections:10.1590/=scl}") String collections) {
    this.http = http;
    this.crossref = crossref;
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

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return collection(w) != null;
  }

  @Override
  public Optional<TopUpResult> fetch(Work w, @Nullable Source s) {
    String collection = collection(w);
    if (collection == null) return Optional.empty();
    String pid = pid(w.doi());
    if (pid == null) return Optional.empty();
    URI uri = URI.create(base + "/api/v1/article/?code=" + LinkHttp.enc(pid) + "&collection=" + LinkHttp.enc(collection));
    JsonNode root = LinkHttp.json(http, uri, "application/json", "SciELO ArticleMeta");
    String doi = root.path("doi").asString("").strip();
    if (doi.isEmpty()) doi = root.path("article").path("v237").path(0).path("_").asString("").strip();
    if (!w.doi().strip().equalsIgnoreCase(doi)) return Optional.empty();
    return Optional.ofNullable(result(root.path("article")));
  }

  /** The SciELO PID among the {@code alternative-id}s of the DOI's Crossref record, or null. */
  private @Nullable String pid(String doi) {
    JsonNode rec = crossref.work(doi.strip()).orElse(null);
    if (rec == null) return null;
    for (JsonNode id : rec.path("alternative-id")) {
      String v = id.asString("").strip();
      if (PID.matcher(v).matches()) return v;
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
