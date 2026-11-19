package org.catalogueoflife.nametracker.indexer.layer.blr;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefWorks;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerPage;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.ident.WorkSightingMapper;
import org.catalogueoflife.nametracker.store.layer.LayerUnmatchedMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Plazi Biodiversity Literature Repository layer: pages of the Zenodo community's newest records, no route (scope
 * {@code all}). The article DOIs are the {@code isPartOf} DOIs of treatments and figures and the DOI of article
 * records; the BLR id is the article's Zenodo record id. For each distinct DOI:
 * <ul>
 *   <li>a work with that DOI exists: it only gets the {@code BLR} identifier and a BLR sighting; when another work
 *   holds that BLR id already, {@code MERGE_WORKS} joins the two;</li>
 *   <li>else one Crossref lookup: a journal of an onboarded source stores a {@link Layer#BLR} work with the {@code DOI}
 *   and {@code BLR} ids, any other journal is noted in {@code layer_unmatched}.</li>
 * </ul>
 * Records without an article DOI (Zenodo's own 10.5281 DOIs do not count) are ignored. The position is the page
 * number, with {@code @<day>} once a large window (a catch-up after a long outage) was cut at a day, see {@link #next}.
 */
@Component
public class BlrLayer implements HarvestLayer {

  static final String COMMUNITY = "biosyslit";
  /** Records of one window read before it is split by day (Zenodo pages no further than 10,000). */
  static final int SPLIT_AT = 9000;
  private static final Logger LOG = LoggerFactory.getLogger(BlrLayer.class);
  private static final String ZENODO_PREFIX = "10.5281/";

  private final ZenodoClient zenodo;
  private final CrossrefClient crossref;
  private final SourceMapper sources;
  private final WorkMapper works;
  private final WorkIdentifierMapper identifiers;
  private final WorkSightingMapper sightings;
  private final LayerUnmatchedMapper unmatched;
  private final TaskQueue queue;

  public BlrLayer(PoliteHttp http, CrossrefClient crossref, SourceMapper sources, WorkMapper works,
      WorkIdentifierMapper identifiers, WorkSightingMapper sightings, LayerUnmatchedMapper unmatched, TaskQueue queue,
      @Value("${nametracker.zenodo.base-url:https://zenodo.org}") String baseUrl) {
    this.zenodo = new ZenodoClient(http, baseUrl);
    this.crossref = crossref;
    this.sources = sources;
    this.works = works;
    this.identifiers = identifiers;
    this.sightings = sightings;
    this.unmatched = unmatched;
    this.queue = queue;
  }

  @Override
  public Layer layer() {
    return Layer.BLR;
  }

  @Override
  public LayerPage page(LayerJob job, @Nullable String position) {
    int at = position == null ? -1 : position.indexOf('@');
    int pageNo = position == null ? 1 : Integer.parseInt(at < 0 ? position : position.substring(0, at));
    LocalDate top = at < 0 ? job.until() : LocalDate.parse(position.substring(at + 1));
    ZenodoClient.ZenodoPage page = zenodo.records(COMMUNITY, job.from().atStartOfDay().toInstant(ZoneOffset.UTC),
        top == null ? null : top.atStartOfDay().toInstant(ZoneOffset.UTC), pageNo);
    Map<String, String> dois = articleDois(page.records());
    int skipped = page.records().size() - countRecordsWithDoi(page.records());
    List<Source> harvestable = null;
    List<HarvestedWork> out = new ArrayList<>();
    int calls = 1;
    for (Map.Entry<String, String> e : dois.entrySet()) {
      String doi = e.getKey();
      Identifier blr = IdScheme.id(IdScheme.BLR, e.getValue());
      List<Long> known = identifiers.findWorkIds(List.of(new Identifier(IdScheme.DOI.code(), doi)));
      if (!known.isEmpty()) {
        long workId = known.getFirst();
        if (blr != null) {
          identifiers.insertAll(workId, List.of(blr), Layer.BLR.name());
          // a BLR id another work holds already: the two rows are one article (no identifier is dropped)
          for (long other : identifiers.findWorkIds(List.of(blr))) {
            if (other != workId) WorksPager.enqueueMerge(queue, workId, other, priority(job.mode()));
          }
        }
        Work w = works.get(workId);
        sightings.sight(workId, Layer.BLR.name(), w == null ? null : w.sourceId());
        continue;
      }
      calls++;
      Optional<JsonNode> item = crossref.work(doi);
      if (item.isEmpty()) {
        skipped++;
        continue;
      }
      if (harvestable == null) harvestable = sources.listHarvestable();
      List<String> issns = CrossrefWorks.issns(item.get());
      Source source = match(harvestable, issns);
      if (source == null) {
        JsonNode ct = item.get().path("container-title");
        unmatched.record(Layer.BLR.name(), doi, issns.isEmpty() ? null : issns.getFirst(),
            ct.isArray() && !ct.isEmpty() ? ct.get(0).asString(null) : null);
        continue;
      }
      List<Identifier> ids = new ArrayList<>();
      ids.add(new Identifier(IdScheme.DOI.code(), doi));
      if (blr != null) ids.add(blr);
      out.add(new HarvestedWork(CrossrefWorks.parse(item.get()).toWork(source.id(), null), Layer.BLR, ids));
    }
    return new LayerPage(out, next(job, page, pageNo, top), calls, skipped);
  }

  /**
   * The next position: the next page of the window, or, once {@value #SPLIT_AT} records of it have been read (Zenodo
   * pages no further than 10,000), page 1 of the window cut at the day of the oldest record read (that day is read
   * again; the upsert is idempotent). A window whose top day alone holds more is cut a day lower and the rest of that
   * day is given up (WARN). Null after the last page.
   */
  private @Nullable String next(LayerJob job, ZenodoClient.ZenodoPage page, int pageNo, @Nullable LocalDate top) {
    if (!page.hasNext()) return null;
    String suffix = top == null ? "" : "@" + top;
    if ((long) pageNo * ZenodoClient.PAGE_SIZE < SPLIT_AT) return (pageNo + 1) + suffix;
    LocalDate oldest = null;
    for (JsonNode r : page.records()) {
      String c = r.path("created").asString("");
      if (c.length() < 10) continue;
      LocalDate d = LocalDate.parse(c.substring(0, 10));
      if (oldest == null || d.isBefore(oldest)) oldest = d;
    }
    if (oldest == null) return (pageNo + 1) + suffix;
    LocalDate cut = oldest;
    if (top != null && !cut.isBefore(top)) {
      cut = top.minusDays(1);
      LOG.warn("BLR: more than {} records created on {}; the rest of that day is skipped", SPLIT_AT, top);
    }
    return cut.isBefore(job.from()) ? null : "1@" + cut;
  }

  private static TaskPriority priority(LayerMode mode) {
    return switch (mode) {
      case LIVE -> TaskPriority.LIVE;
      case ONBOARDING -> TaskPriority.REPROCESS;
      case BACKFILL -> TaskPriority.BACKFILL;
    };
  }

  private static @Nullable Source match(List<Source> harvestable, List<String> issns) {
    for (Source s : harvestable) {
      for (String i : issns) {
        if (i.equalsIgnoreCase(s.issnL()) || (s.issns() != null && s.issns().stream().anyMatch(i::equalsIgnoreCase))) {
          return s;
        }
      }
    }
    return null;
  }

  /** Distinct article DOI to the Zenodo record id of the article (null when no record names it), in page order. */
  static Map<String, String> articleDois(List<JsonNode> records) {
    Map<String, String> out = new LinkedHashMap<>();
    for (JsonNode r : records) {
      JsonNode m = r.path("metadata");
      String doi = null;
      String blr = null;
      if ("article".equals(m.path("resource_type").path("subtype").asString(""))) {
        doi = articleDoi(m.path("doi").asString(null));
        blr = r.path("id").asString(null);
      } else {
        for (JsonNode ri : m.path("related_identifiers")) {
          if (!"isPartOf".equals(ri.path("relation").asString(""))) continue;
          String scheme = ri.path("scheme").asString("");
          String id = ri.path("identifier").asString("");
          if (doi == null && "doi".equals(scheme)) doi = articleDoi(id);
          else if (blr == null && "url".equals(scheme) && id.contains("zenodo.org/record")) blr = id;
        }
      }
      if (doi == null) continue;
      String key = IdScheme.DOI.normalise(doi);
      if (key == null) continue;
      String have = out.get(key);
      if (!out.containsKey(key) || have == null) out.put(key, IdScheme.BLR.normalise(blr));
    }
    return out;
  }

  private static int countRecordsWithDoi(List<JsonNode> records) {
    int n = 0;
    for (JsonNode r : records) {
      if (!articleDois(List.of(r)).isEmpty()) n++;
    }
    return n;
  }

  /** The DOI unless it is Zenodo's own (a record without a journal DOI). */
  private static @Nullable String articleDoi(@Nullable String doi) {
    if (doi == null || doi.isBlank()) return null;
    return doi.strip().toLowerCase().startsWith(ZENODO_PREFIX) ? null : doi.strip();
  }
}
