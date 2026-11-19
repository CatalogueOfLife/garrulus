package org.catalogueoflife.nametracker.indexer.layer.magtech;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerPage;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.TitleText;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Magtech RSS layer: one route per job, one feed per page. The route config is
 * {@code {"site":"https://www.biodiversity-science.net","issn":"1005-0094"}}; the feeds are
 * {@code <site>/EN/rss_dqml_<issn>.xml} (position null) and {@code <site>/EN/rss_first_<issn>.xml} (position
 * {@code first}, optional: a 404 there is an empty feed). An item whose MAGTECH identifier is not stored yet costs one
 * request for its article page (DOI, date, language); known items cost none and are not written again. The page URL is
 * the item's path on the route's site.
 */
@Component
public class MagtechLayer implements HarvestLayer {

  private static final Logger LOG = LoggerFactory.getLogger(MagtechLayer.class);

  static final String FIRST = "first";

  private final MagtechClient client;
  private final SourceRouteMapper routes;
  private final WorkIdentifierMapper identifiers;

  public MagtechLayer(PoliteHttp http, SourceRouteMapper routes, WorkIdentifierMapper identifiers) {
    this.client = new MagtechClient(http);
    this.routes = routes;
    this.identifiers = identifiers;
  }

  @Override
  public Layer layer() {
    return Layer.MAGTECH;
  }

  @Override
  public LayerPage page(LayerJob job, @Nullable String position) {
    if (job.routeId() == null) throw new IllegalArgumentException("A Magtech harvest needs a route");
    SourceRoute route = routes.get(job.routeId());
    if (route == null) throw new IllegalArgumentException("Unknown route " + job.routeId());
    JsonNode cfg = FinderJson.mapper().readTree(route.config());
    String site = text(cfg, "site");
    String issn = text(cfg, "issn");
    if (site == null || issn == null) throw new IllegalArgumentException("Magtech route " + route.id() + " needs site and issn");
    String base = site.endsWith("/") ? site.substring(0, site.length() - 1) : site;
    boolean first = FIRST.equals(position);
    URI feed = URI.create(base + "/EN/" + (first ? "rss_first_" : "rss_dqml_") + issn + ".xml");
    List<MagtechClient.MagtechItem> items;
    try {
      items = client.feed(feed);
    } catch (HttpProblem e) {
      if (first && e.status() == 404) return new LayerPage(List.of(), null, 1, 0);
      throw e;
    }
    int calls = 1;
    int skipped = 0;
    List<HarvestedWork> works = new ArrayList<>();
    for (MagtechClient.MagtechItem item : items) {
      Identifier magtech = IdScheme.id(IdScheme.MAGTECH, item.link().toString());
      if (magtech == null) {
        skipped++;
        continue;
      }
      if (!identifiers.findWorkIds(List.of(magtech)).isEmpty()) continue; // known: no page request
      MagtechClient.ArticleMeta meta = articleMeta(base, item.link());
      calls++;
      works.add(toHarvested(item, meta, magtech, route.sourceId()));
    }
    return new LayerPage(works, first ? null : FIRST, calls, skipped);
  }

  private MagtechClient.ArticleMeta articleMeta(String base, URI link) {
    URI page = URI.create(base + link.getRawPath() + (link.getRawQuery() == null ? "" : "?" + link.getRawQuery()));
    try {
      return client.articleMeta(page);
    } catch (HttpProblem e) {
      if (e.retryable()) throw e;
      LOG.warn("Magtech article page {} unreadable ({}); the item is stored from the feed alone", page, e.getMessage());
      return new MagtechClient.ArticleMeta(null, null, null);
    }
  }

  static HarvestedWork toHarvested(MagtechClient.MagtechItem item, MagtechClient.ArticleMeta meta, Identifier magtech,
      long sourceId) {
    List<Identifier> ids = new ArrayList<>();
    Identifier doi = IdScheme.id(IdScheme.DOI, meta.doi());
    if (doi != null) ids.add(doi);
    ids.add(magtech);
    LocalDate feedDate = pubDate(item.pubDate());
    LocalDate date = feedDate;
    if (meta.date() != null && (feedDate == null || meta.date().isAfter(feedDate))) date = meta.date();
    Short year = date == null ? null : (short) date.getYear();
    String title = TitleText.clean(item.title());
    String authors = authors(item.authors());
    String abs = item.descriptionHtml().isBlank() ? null : MagtechAbstracts.toJats(item.descriptionHtml());
    if (abs != null && abs.isEmpty()) abs = null;
    Work w = new Work(null, null, doi == null ? null : doi.value(), sourceId, title, date, year, null, null, null,
        authors, "article", meta.language(), null, null, null, null, null, null, null, null, null, null, null, null,
        null, abs, null, null, null, null, abs == null ? null : "MAGTECH", null);
    return new HarvestedWork(w, Layer.MAGTECH, ids);
  }

  /** The feed's comma separated author names as the JSON the work stores: {@code [{"name":…},…]}. */
  private static String authors(String list) {
    List<Map<String, String>> out = new ArrayList<>();
    for (String a : list.split("[,;，；]")) {
      String name = a.strip();
      if (!name.isEmpty()) out.add(Map.of("name", name));
    }
    return FinderJson.mapper().writeValueAsString(out);
  }

  /** The feed's {@code 2023-03-20 00:00:00.0}: its date part. */
  private static @Nullable LocalDate pubDate(@Nullable String s) {
    return MagtechClient.date(s);
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString().strip();
    return s.isEmpty() ? null : s;
  }
}
