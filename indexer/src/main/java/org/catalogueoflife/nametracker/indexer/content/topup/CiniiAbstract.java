package org.catalogueoflife.nametracker.indexer.content.topup;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.link.DoiBatching;
import org.catalogueoflife.nametracker.indexer.link.LinkHttp;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The abstract of a Japanese journal's article from CiNii Research ({@code /opensearch/articles?q=<doi>&format=json
 * &appid=…}), for sources of country {@code JP}. It needs a free application id ({@code CINII_APPID}; blank switches it
 * off). An abstract is taken only when the record's {@code abstractLicenseFlag} is {@code allow}; {@code disallow} (or
 * no flag) is a miss. The id travels as a query parameter and is redacted wherever the URL is logged.
 */
@Component
@Order(40)
public class CiniiAbstract implements AbstractTopUp {

  public static final String SERVICE = "CINII";
  private static final Logger LOG = LoggerFactory.getLogger(CiniiAbstract.class);

  private final PoliteHttp http;
  private final String base;
  private final @Nullable String appId;

  @Autowired
  public CiniiAbstract(PoliteHttp http,
      @Value("${nametracker.content.topup.cinii.base-url:https://cir.nii.ac.jp}") String baseUrl,
      @Value("${nametracker.content.topup.cinii.app-id:}") String appId) {
    this.http = http;
    this.base = LinkHttp.trimSlash(baseUrl);
    this.appId = appId == null || appId.isBlank() ? null : appId.strip();
  }

  @Override
  public String service() {
    return SERVICE;
  }

  @Override
  public boolean enabled() {
    return appId != null;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return s != null && "JP".equalsIgnoreCase(s.country()) && w.doi() != null && DoiBatching.quotable(w.doi());
  }

  @Override
  public Optional<TopUpResult> fetch(Work w, @Nullable Source s) {
    URI uri = URI.create(base + "/opensearch/articles?q=" + LinkHttp.enc(w.doi()) + "&format=json&appid="
        + LinkHttp.enc(appId));
    JsonNode root;
    try {
      root = LinkHttp.json(http, uri, "application/json", "CiNii Research");
    } catch (HttpProblem p) {
      if (p.status() == 401 || p.status() == 403) {
        LOG.warn("CiNii Research refused the application id ({})", p.status());
        throw new TopUpSkipped("CiNii application id refused");
      }
      throw p;
    }
    Identifier want = IdScheme.id(IdScheme.DOI, w.doi());
    if (want == null) return Optional.empty();
    for (JsonNode item : root.path("items")) {
      if (!hasDoi(item, want.value())) continue;
      String flag = flag(item);
      String abs = text(item.path("description"));
      if (!"allow".equalsIgnoreCase(flag) || abs.isEmpty()) continue;
      return Optional.of(new TopUpResult(abs, null, null, "CINII", List.of(), null));
    }
    return Optional.empty();
  }

  /** True when the item's own DOI fields ({@code prism:doi}, {@code doi}, a DOI-typed {@code dc:identifier}) equal the DOI. */
  static boolean hasDoi(JsonNode item, String doi) {
    List<String> raw = new java.util.ArrayList<>();
    for (String k : new String[] {"prism:doi", "doi"}) raw.add(item.path(k).asString(""));
    JsonNode ids = item.path("dc:identifier");
    for (JsonNode i : ids.isArray() ? ids : java.util.List.<JsonNode>of(ids)) {
      if (i.isObject() && i.path("@type").asString("").toLowerCase(Locale.ROOT).contains("doi")) {
        raw.add(i.path("@value").asString(""));
      }
    }
    for (String r : raw) {
      Identifier id = IdScheme.id(IdScheme.DOI, r);
      if (id != null && id.value().equals(doi)) return true;
    }
    return false;
  }

  private static String flag(JsonNode item) {
    for (String k : new String[] {"abstractLicenseFlag", "cir:abstractLicenseFlag"}) {
      String v = item.path(k).asString("").strip();
      if (!v.isEmpty()) return v;
    }
    return "";
  }

  private static String text(JsonNode n) {
    if (n.isArray()) return n.size() == 0 ? "" : text(n.get(0));
    if (n.isObject()) return n.path("@value").asString("").strip();
    return n.asString("").strip();
  }
}
