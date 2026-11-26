package org.catalogueoflife.garrulus.indexer.layer.europepmc;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.layer.HarvestLayer;
import org.catalogueoflife.garrulus.indexer.layer.HarvestedWork;
import org.catalogueoflife.garrulus.indexer.layer.LayerJob;
import org.catalogueoflife.garrulus.indexer.layer.LayerMode;
import org.catalogueoflife.garrulus.indexer.layer.LayerPage;
import org.catalogueoflife.garrulus.indexer.layer.magtech.MagtechAbstracts;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.lang.LanguageCodes;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.catalogueoflife.garrulus.store.work.TitleText;
import org.catalogueoflife.garrulus.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Europe PMC layer: one route per job, cursor pages of the search
 * {@code (ISSN:"a" OR ISSN:"b") AND FIRST_IDATE:[from TO until]} ({@code FIRST_PDATE}, the publication date, for a
 * {@code BACKFILL} job). The route config is {@code {"issns":["…"]}}. The
 * abstract (inline HTML) is stored as JATS of kind {@code EUROPEPMC}; DOI, PMID and PMCID are the work's identifiers.
 */
@Component
public class EuropePmcLayer implements HarvestLayer {

  private final EuropePmcSearchClient client;
  private final SourceRouteMapper routes;

  public EuropePmcLayer(PoliteHttp http, SourceRouteMapper routes,
      @Value("${garrulus.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String baseUrl) {
    this.client = new EuropePmcSearchClient(http, baseUrl);
    this.routes = routes;
  }

  @Override
  public Layer layer() {
    return Layer.EUROPEPMC;
  }

  @Override
  public LayerPage page(LayerJob job, @Nullable String position) {
    if (job.routeId() == null) throw new IllegalArgumentException("A Europe PMC harvest needs a route");
    SourceRoute route = routes.get(job.routeId());
    if (route == null) throw new IllegalArgumentException("Unknown route " + job.routeId());
    List<String> issns = issns(route);
    if (issns.isEmpty()) throw new IllegalArgumentException("Europe PMC route " + route.id() + " needs issns");
    LocalDate until = job.until() != null ? job.until() : LocalDate.now(ZoneOffset.UTC);
    // a backfill asks for the papers published in the span, the daily harvest for those Europe PMC indexed in it
    String field = job.mode() == LayerMode.BACKFILL ? "FIRST_PDATE" : "FIRST_IDATE";
    String query = EuropePmcSearchClient.issnQuery(issns) + " AND " + field + ":[" + job.from() + " TO " + until + "]";
    EuropePmcSearchClient.EpmcPage page = client.search(query, position);
    List<HarvestedWork> works = new ArrayList<>();
    int skipped = 0;
    for (JsonNode r : page.results()) {
      HarvestedWork hw = toHarvested(r, route.sourceId());
      if (hw == null) skipped++;
      else works.add(hw);
    }
    return new LayerPage(works, page.nextCursor(), 1, skipped);
  }

  /** The ISSNs of a route's config. */
  public static List<String> issns(SourceRoute route) {
    List<String> out = new ArrayList<>();
    for (JsonNode i : FinderJson.mapper().readTree(route.config()).path("issns")) {
      String s = i.asString("").strip();
      if (!s.isEmpty()) out.add(s);
    }
    return out;
  }

  /** Null when the record has no title or no usable identifier. */
  public static @Nullable HarvestedWork toHarvested(JsonNode r, long sourceId) {
    String title = TitleText.clean(r.path("title").asString(""));
    if (title == null || title.isBlank()) return null;
    List<Identifier> ids = new ArrayList<>();
    Identifier doi = IdScheme.id(IdScheme.DOI, text(r, "doi"));
    if (doi != null) ids.add(doi);
    Identifier pmid = IdScheme.id(IdScheme.PMID, text(r, "pmid"));
    if (pmid != null) ids.add(pmid);
    Identifier pmcid = IdScheme.id(IdScheme.PMCID, text(r, "pmcid"));
    if (pmcid != null) ids.add(pmcid);
    if (ids.isEmpty()) return null;
    LocalDate date = date(text(r, "firstPublicationDate"));
    Short year = date != null ? Short.valueOf((short) date.getYear()) : year(text(r, "pubYear"));
    JsonNode ji = r.path("journalInfo");
    String abs = null;
    String abstractHtml = text(r, "abstractText");
    if (abstractHtml != null) {
      abs = MagtechAbstracts.toJats(abstractHtml);
      if (abs.isEmpty()) abs = null;
    }
    Work w = new Work(null, null, doi == null ? null : doi.value(), sourceId, title, date, year, text(ji, "volume"),
        text(ji, "issue"), text(r, "pageInfo"), authors(r), "article", LanguageCodes.normalise(text(r, "language")), null, null, null,
        null, null, null, null, null, null, null, null, null, null, abs, pmcid == null ? null : pmcid.value(), null,
        null, null, abs == null ? null : "EUROPEPMC", null);
    return new HarvestedWork(w, Layer.EUROPEPMC, ids, text(r, "language"), List.of());
  }

  private static String authors(JsonNode r) {
    List<Map<String, String>> out = new ArrayList<>();
    for (JsonNode a : r.path("authorList").path("author")) {
      String name = text(a, "fullName");
      if (name == null) continue;
      Map<String, String> m = new LinkedHashMap<>();
      m.put("name", name);
      String orcid = a.path("authorId").path("type").asString("").equals("ORCID") ? text(a.path("authorId"), "value") : null;
      if (orcid != null) m.put("orcid", orcid);
      out.add(m);
    }
    if (out.isEmpty()) {
      String s = text(r, "authorString");
      if (s != null) {
        for (String n : s.replaceAll("\\.$", "").split(",")) {
          if (!n.isBlank()) out.add(Map.of("name", n.strip()));
        }
      }
    }
    return FinderJson.mapper().writeValueAsString(out);
  }

  private static @Nullable LocalDate date(@Nullable String s) {
    if (s == null) return null;
    try {
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static @Nullable Short year(@Nullable String s) {
    try {
      return s == null ? null : Short.valueOf(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    if (v.isMissingNode() || v.isNull()) return null;
    String s = v.asString("").strip();
    return s.isEmpty() ? null : s;
  }
}
