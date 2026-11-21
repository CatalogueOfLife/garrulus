package org.catalogueoflife.nametracker.web.api.public_;

import java.util.List;
import org.catalogueoflife.nametracker.store.names.NameMapper;
import org.catalogueoflife.nametracker.store.names.TaxonYearStats;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.web.api.public_.dto.TaxonStats;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-taxon endpoints for the Literature widget of the COL portal (CORS is configured in {@code CorsConfig}).
 * {@code colId} is a usage id of the current XR; works are those that name the taxon or a descendant.
 */
@RestController
public class TaxonController {

  private final WorkController works;
  private final NameMapper names;
  private final StatsMapper statistics;

  public TaxonController(WorkController works, NameMapper names, StatsMapper statistics) {
    this.statistics = statistics;
    this.works = works;
    this.names = names;
  }

  /** The same search and response as /api/works with taxon=colId; mainSubjectOnly defaults to true here. */
  @GetMapping("/api/taxa/{colId}/works")
  public WorkPage works(@PathVariable String colId,
      @RequestParam(defaultValue = "true") boolean mainSubjectOnly,
      @RequestParam(required = false) Integer yearFrom,
      @RequestParam(required = false) Integer yearTo,
      @RequestParam(name = "mentionedIn", required = false) List<String> mentionedIn,
      @RequestParam(name = "language", required = false) List<String> language,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    WorkController.checkPaging(limit, offset);
    WorkFilter f = WorkFilter.builder().taxon(colId).yearFrom(yearFrom).yearTo(yearTo).mainSubjectOnly(mainSubjectOnly)
        .mentionedIn(MentionedInParam.parse(mentionedIn)).languages(LanguageParam.parse(language)).build();
    return works.page(f, limit, offset, List.of());
  }

  @GetMapping("/api/taxa/{colId}/stats")
  public TaxonStats stats(@PathVariable String colId) {
    List<TaxonYearStats> rows = names.taxonStats(colId);
    return new TaxonStats(colId, rows.stream().mapToLong(TaxonYearStats::works).sum(),
        rows.stream().mapToLong(TaxonYearStats::mainSubjectWorks).sum(),
        rows.stream().map(r -> new TaxonStats.Year(r.year(), r.works(), r.mainSubjectWorks())).toList(),
        statistics.taxonLanguages(colId));
  }
}
