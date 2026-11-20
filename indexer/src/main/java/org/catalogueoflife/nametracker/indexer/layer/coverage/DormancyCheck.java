package org.catalogueoflife.nametracker.indexer.layer.coverage;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.route.SourceRoute;
import org.catalogueoflife.nametracker.store.route.SourceRouteMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Proposes journals that went quiet as dormant; never marks one ceased by itself. For every journal with ACTIVE
 * publication status and works: {@code lastPub} is the latest publication date (a work known by its year only counts
 * from 31 December), {@code usualGap} is 1095 days divided by the works published in the 3 years up to it, and the
 * journal is dormant when {@code day - lastPub} exceeds {@code max(min-days, gap-factor * usualGap)} (365 days and
 * 3 times the gap): a quarterly journal is allowed far more silence than a weekly one.
 *
 * <p>{@code dormant_since} is the day the check first found it dormant and stays while it does; it is cleared when new
 * works arrive, when the journal ceased, and while an admin's dismissal is less than a year old (so the proposal list,
 * the rows with a {@code dormant_since}, simply omits it). {@code dormant_evidence} is
 * {@code {"lastPublished","usualGapDays","brokenRoutes":[{"id","layer","error"}],"doajDiscontinued","successorIssns"}}.
 */
@Component
public class DormancyCheck {

  private static final Logger LOG = LoggerFactory.getLogger(DormancyCheck.class);
  static final int DISMISSED_DAYS = 365;
  static final int WINDOW_DAYS = 1095;

  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final int minDays;
  private final double gapFactor;

  public DormancyCheck(SourceMapper sources, SourceRouteMapper routes,
      @Value("${nametracker.dormancy.min-days:365}") int minDays,
      @Value("${nametracker.dormancy.gap-factor:3}") double gapFactor) {
    this.sources = sources;
    this.routes = routes;
    this.minDays = minDays;
    this.gapFactor = gapFactor;
  }

  /** Judges every journal as of {@code day}; returns the number now proposed. */
  public int run(LocalDate day) {
    sources.clearDormancyOfCeased();
    int dormant = 0;
    for (Map<String, Object> r : sources.dormancyBase()) {
      long id = ((Number) r.get("id")).longValue();
      LocalDate lastPub = date(r.get("last_pub"));
      LocalDate since = date(r.get("dormant_since"));
      Instant dismissed = r.get("dormant_dismissed_at") instanceof java.sql.Timestamp t ? t.toInstant() : null;
      double usualGap = (double) WINDOW_DAYS / Math.max(1, ((Number) r.get("recent")).intValue());
      long quiet = ChronoUnit.DAYS.between(lastPub, day);
      boolean quietEnough = quiet > Math.max(minDays, gapFactor * usualGap);
      boolean dismissedRecently = dismissed != null
          && dismissed.isAfter(day.atStartOfDay().toInstant(ZoneOffset.UTC).minus(DISMISSED_DAYS, ChronoUnit.DAYS));
      if (!quietEnough || dismissedRecently) {
        if (since != null) sources.setDormancy(id, null, null);
        continue;
      }
      dormant++;
      sources.setDormancy(id, since != null ? since : day, evidence(id, lastPub, usualGap, r));
    }
    LOG.info("Dormancy check of {}: {} journals proposed as dormant", day, dormant);
    return dormant;
  }

  private String evidence(long id, LocalDate lastPub, double usualGap, Map<String, Object> row) {
    List<Map<String, Object>> broken = new ArrayList<>();
    for (SourceRoute route : routes.forSource(id)) {
      if (!"BROKEN".equals(route.status())) continue;
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("id", route.id());
      b.put("layer", route.layer());
      b.put("error", route.lastError());
      broken.add(b);
    }
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("lastPublished", lastPub.toString());
    e.put("usualGapDays", Math.round(usualGap));
    e.put("brokenRoutes", broken);
    LocalDate discontinued = date(row.get("doaj_discontinued"));
    e.put("doajDiscontinued", discontinued == null ? null : discontinued.toString());
    e.put("successorIssns", successors(row.get("successor_issns")));
    return FinderJson.mapper().writeValueAsString(e);
  }

  private static List<String> successors(@Nullable Object o) {
    if (o instanceof java.sql.Array a) {
      try {
        return List.of((String[]) a.getArray());
      } catch (java.sql.SQLException ex) {
        throw new IllegalStateException(ex);
      }
    }
    if (o instanceof String[] arr) return List.of(arr);
    return List.of();
  }

  private static @Nullable LocalDate date(@Nullable Object o) {
    if (o == null) return null;
    if (o instanceof Date d) return d.toLocalDate();
    if (o instanceof LocalDate d) return d;
    return LocalDate.parse(o.toString());
  }
}
