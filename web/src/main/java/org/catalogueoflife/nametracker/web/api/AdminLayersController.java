package org.catalogueoflife.nametracker.web.api;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.store.layer.CoverageRow;
import org.catalogueoflife.nametracker.store.layer.LayerHealth;
import org.catalogueoflife.nametracker.store.layer.LayerHealthMapper;
import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The harvest layers at a glance (admin token or admin session, {@link AdminAccess}): health, contribution, coverage. */
@RestController
@Conditional(AdminAccessConfigured.class)
public class AdminLayersController {

  static final int MAX_DAYS = 90;
  static final int COVERAGE_ROWS = 200;

  /** The daily health of the layers of the last {@code days} days, their 28-day contribution and the worst coverage. */
  public record LayersView(List<LayerHealth> health, Map<String, Map<String, Long>> contribution,
      Map<String, Map<String, Map<String, Long>>> contributionByCountry,
      Map<String, Map<String, Map<String, Long>>> contributionByLanguage, List<CoverageRow> coverage) {}

  private final LayerHealthMapper layers;
  private final AdminAccess access;

  public AdminLayersController(LayerHealthMapper layers, AdminAccess access) {
    this.layers = layers;
    this.access = access;
  }

  @GetMapping("/api/admin/layers")
  public LayersView layers(@RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestParam(defaultValue = "28") int days) {
    access.check(given);
    if (days < 1 || days > MAX_DAYS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 1.." + MAX_DAYS);
    }
    LayerContribution c = LayerContribution.of(layers.latestContribution(), layers.latestContributionLanguage());
    return new LayersView(layers.latest(days), c.total(), c.byCountry(), c.byLanguage(), layers.coverageRows(COVERAGE_ROWS));
  }
}
