package org.catalogueoflife.nametracker.web.api.public_;

import java.util.List;
import org.catalogueoflife.nametracker.store.lang.LanguageYear;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Listed works per work language and year, read from the table {@code REBUILD_STATS} fills (as of the last night). */
@RestController
public class StatsController {

  private final StatsMapper stats;

  public StatsController(StatsMapper stats) {
    this.stats = stats;
  }

  /** {@code language} is {@code ''} for works without a decision. Ordered by year, then works descending. */
  @GetMapping("/api/stats/languages")
  public List<LanguageYear> languages(@RequestParam(required = false) String taxgroup,
      @RequestParam(required = false) String code, @RequestParam(required = false) String country,
      @RequestParam(required = false) Integer yearFrom, @RequestParam(required = false) Integer yearTo) {
    WorkController.requireOneOf("taxgroup", blank(taxgroup), WorkController.TAXGROUPS);
    WorkController.requireOneOf("code", blank(code), WorkController.CODES);
    return stats.languages(blank(taxgroup), blank(code), blank(country), yearFrom, yearTo);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
