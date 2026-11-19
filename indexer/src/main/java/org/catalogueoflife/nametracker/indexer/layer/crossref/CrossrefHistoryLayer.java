package org.catalogueoflife.nametracker.indexer.layer.crossref;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefWorks;
import org.catalogueoflife.nametracker.indexer.layer.HarvestLayer;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.indexer.layer.LayerJob;
import org.catalogueoflife.nametracker.indexer.layer.LayerMode;
import org.catalogueoflife.nametracker.indexer.layer.LayerPage;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The history of one journal in Crossref, for a route backfill ({@code BACKFILL} mode only): the journal articles of
 * every ISSN of the job's source with {@code from-pub-date:<from>,until-pub-date:<until>}, read one publication year
 * at a time. Position {@code <year>:<offset>}; pages are offset pages, because a backfill page waits at
 * {@code BACKFILL} priority far longer than a Crossref cursor lives (about 5 minutes). A year beyond the
 * {@value CrossrefClient#MAX_OFFSET} offsets Crossref serves is cut off with a WARN. Works keep the source of the
 * job; no OpenAlex lookup is made.
 */
@Component
public class CrossrefHistoryLayer implements HarvestLayer {

  private static final Logger LOG = LoggerFactory.getLogger(CrossrefHistoryLayer.class);

  private final CrossrefClient crossref;
  private final SourceMapper sources;
  private final Clock clock;

  public CrossrefHistoryLayer(CrossrefClient crossref, SourceMapper sources, Clock clock) {
    this.crossref = crossref;
    this.sources = sources;
    this.clock = clock;
  }

  @Override
  public Layer layer() {
    return Layer.CROSSREF;
  }

  @Override
  public LayerPage page(LayerJob job, @Nullable String position) {
    if (job.mode() != LayerMode.BACKFILL) {
      throw new IllegalArgumentException("The Crossref history runs in BACKFILL mode only, not " + job.mode());
    }
    if (job.sourceIds().isEmpty()) throw new IllegalArgumentException("A Crossref history needs a source");
    long sourceId = job.sourceIds().getFirst();
    Source source = sources.get(sourceId);
    if (source == null) throw new IllegalArgumentException("Unknown source " + sourceId);
    List<String> issns = issns(source);
    if (issns.isEmpty()) return new LayerPage(List.of(), null, 0, 0);
    LocalDate until = job.until() != null ? job.until() : LocalDate.now(clock.withZone(ZoneOffset.UTC));
    int year = job.from().getYear();
    int offset = 0;
    if (position != null) {
      int colon = position.indexOf(':');
      year = Integer.parseInt(position.substring(0, colon));
      offset = Integer.parseInt(position.substring(colon + 1));
    }
    LocalDate start = max(job.from(), LocalDate.of(year, 1, 1));
    LocalDate end = min(until, LocalDate.of(year, 12, 31));
    if (start.isAfter(end)) return new LayerPage(List.of(), null, 0, 0);

    CrossrefClient.Page page = crossref.worksAt(filter(start, end, issns), offset);
    List<HarvestedWork> works = new ArrayList<>();
    int skipped = 0;
    for (JsonNode item : page.items()) {
      try {
        WorkRecord rec = CrossrefWorks.parse(item);
        works.add(new HarvestedWork(rec.toWork(sourceId, null), Layer.CROSSREF, rec.identifiers()));
      } catch (RuntimeException e) {
        LOG.warn("Skipping an unreadable Crossref item {}: {}", item.path("DOI").asString(""), e.toString());
        skipped++;
      }
    }
    return new LayerPage(works, next(page.items().size(), year, offset, until, sourceId), 1, skipped);
  }

  private static @Nullable String next(int items, int year, int offset, LocalDate until, long sourceId) {
    if (items >= CrossrefClient.ROWS) {
      if (offset + CrossrefClient.ROWS < CrossrefClient.MAX_OFFSET) return year + ":" + (offset + CrossrefClient.ROWS);
      LOG.warn("Crossref history of source {}: {} has more than {} articles; the rest of the year is left out",
          sourceId, year, CrossrefClient.MAX_OFFSET);
    }
    return year < until.getYear() ? (year + 1) + ":0" : null;
  }

  /** The filter of one span: publication dates, journal articles, every ISSN (ORed by Crossref). */
  static String filter(LocalDate from, LocalDate until, List<String> issns) {
    StringBuilder sb = new StringBuilder("from-pub-date:").append(from).append(",until-pub-date:").append(until)
        .append(",type:journal-article");
    for (String i : issns) sb.append(",issn:").append(i);
    return sb.toString();
  }

  /** The linking ISSN and the other ISSNs of the source, upper case, each once. */
  public static List<String> issns(Source s) {
    Set<String> out = new LinkedHashSet<>();
    if (s.issnL() != null && !s.issnL().isBlank()) out.add(s.issnL().strip().toUpperCase(Locale.ROOT));
    if (s.issns() != null) {
      for (String i : s.issns()) {
        if (i != null && !i.isBlank()) out.add(i.strip().toUpperCase(Locale.ROOT));
      }
    }
    return List.copyOf(out);
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }
}
