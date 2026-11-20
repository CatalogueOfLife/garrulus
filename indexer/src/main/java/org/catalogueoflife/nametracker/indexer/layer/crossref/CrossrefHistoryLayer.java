package org.catalogueoflife.nametracker.indexer.layer.crossref;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
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
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The history of one journal in Crossref, for a route backfill ({@code BACKFILL} mode) or the onboarding pass of a
 * journal that only Crossref has ({@code ONBOARDING}, the last 120 days): the journal articles of
 * every ISSN of the job's source with {@code from-pub-date}/{@code until-pub-date}, read one span at a time.
 *
 * <p>A span is a publication year (position {@code <yyyy>:<offset>}) or, for a year Crossref cannot list in full, a
 * month ({@code <yyyy-mm>:<offset>}). Pages are offset pages sorted by DOI creation, not cursor pages, because a
 * backfill page waits at {@code BACKFILL} priority far longer than a Crossref cursor lives (about 5 minutes); the
 * positions depend on nothing but the job, so a page delivered again enqueues the same continuation. Crossref serves
 * offsets up to {@value CrossrefClient#MAX_OFFSET}: a year whose page there is still full is read again month by month,
 * and a month whose page there is still full loses the rest, which is logged and noted on the run's error (the run
 * still ends DONE).
 */
@Component
public class CrossrefHistoryLayer implements HarvestLayer {

  private static final Logger LOG = LoggerFactory.getLogger(CrossrefHistoryLayer.class);

  private final CrossrefClient crossref;
  private final SourceMapper sources;
  private final HarvestRunMapper runs;
  private final Clock clock;
  private final int rows;
  private final int maxOffset;

  @Autowired
  public CrossrefHistoryLayer(CrossrefClient crossref, SourceMapper sources, HarvestRunMapper runs, Clock clock) {
    this(crossref, sources, runs, clock, CrossrefClient.ROWS, CrossrefClient.MAX_OFFSET);
  }

  /** With other page sizes, for tests. */
  CrossrefHistoryLayer(CrossrefClient crossref, SourceMapper sources, HarvestRunMapper runs, Clock clock, int rows,
      int maxOffset) {
    this.crossref = crossref;
    this.sources = sources;
    this.runs = runs;
    this.clock = clock;
    this.rows = rows;
    this.maxOffset = maxOffset;
  }

  @Override
  public Layer layer() {
    return Layer.CROSSREF;
  }

  /** A publication year ({@code month} null) or one month of it. */
  private record Span(int year, @Nullable Integer month) {

    static Span parse(String s) {
      int dash = s.indexOf('-');
      return dash < 0 ? new Span(Integer.parseInt(s), null)
          : new Span(Integer.parseInt(s.substring(0, dash)), Integer.parseInt(s.substring(dash + 1)));
    }

    LocalDate first() {
      return month == null ? LocalDate.of(year, 1, 1) : LocalDate.of(year, month, 1);
    }

    LocalDate last() {
      return month == null ? LocalDate.of(year, 12, 31) : YearMonth.of(year, month).atEndOfMonth();
    }

    /** The span after this one: the next month of a month span (December goes on with the next year), else the next year. */
    Span next() {
      if (month != null && month < 12) return new Span(year, month + 1);
      return new Span(year + 1, null);
    }

    /** {@code yyyy} or {@code yyyy-mm}. */
    String label() {
      return month == null ? String.valueOf(year) : String.format(Locale.ROOT, "%d-%02d", year, month);
    }

    String at(int offset) {
      return label() + ":" + offset;
    }
  }

  @Override
  public LayerPage page(LayerJob job, @Nullable String position) {
    if (job.mode() == LayerMode.LIVE) {
      throw new IllegalArgumentException("The Crossref history runs in BACKFILL or ONBOARDING mode, not " + job.mode());
    }
    if (job.sourceIds().isEmpty()) throw new IllegalArgumentException("A Crossref history needs a source");
    long sourceId = job.sourceIds().getFirst();
    Source source = sources.get(sourceId);
    if (source == null) throw new IllegalArgumentException("Unknown source " + sourceId);
    List<String> issns = issns(source);
    if (issns.isEmpty()) return new LayerPage(List.of(), null, 0, 0);
    LocalDate until = job.until() != null ? job.until() : LocalDate.now(clock.withZone(ZoneOffset.UTC));
    Span span = new Span(job.from().getYear(), null);
    int offset = 0;
    if (position != null) {
      int colon = position.lastIndexOf(':');
      span = Span.parse(position.substring(0, colon));
      offset = Integer.parseInt(position.substring(colon + 1));
    }
    LocalDate start = max(job.from(), span.first());
    LocalDate end = min(until, span.last());
    if (start.isAfter(end)) return new LayerPage(List.of(), next(span.next(), until), 0, 0);

    CrossrefClient.Page page = crossref.worksAt(filter(start, end, issns), offset, rows);
    List<HarvestedWork> works = new ArrayList<>();
    int skipped = 0;
    for (JsonNode item : page.items()) {
      try {
        WorkRecord rec = CrossrefWorks.parse(item);
        works.add(new HarvestedWork(rec.toWork(sourceId, null), Layer.CROSSREF, CrossrefWorks.identifiers(rec)));
      } catch (RuntimeException e) {
        LOG.warn("Skipping an unreadable Crossref item {}: {}", item.path("DOI").asString(""), e.toString());
        skipped++;
      }
    }
    String next;
    if (page.items().size() < rows) {
      next = next(span.next(), until);
    } else if (offset + rows < maxOffset) {
      next = span.at(offset + rows);
    } else if (span.month() == null) {
      // the year is too large to list: read it again month by month, from the month it starts in
      LOG.info("Crossref history of source {}: {} has more than {} articles; reading it by month", sourceId,
          span.year(), maxOffset);
      next = new Span(span.year(), start.getMonthValue()).at(0);
    } else {
      String lost = "Crossref history of source " + sourceId + ": " + span.label() + " has more than "
          + maxOffset + " articles; the rest of the month is left out";
      LOG.warn(lost);
      runs.noteError(job.runId(), lost);
      next = next(span.next(), until);
    }
    return new LayerPage(works, next, 1, skipped);
  }

  /** The first page of a span, or null when it starts after {@code until}. */
  private static @Nullable String next(Span span, LocalDate until) {
    return span.first().isAfter(until) ? null : span.at(0);
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
