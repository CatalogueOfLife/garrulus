package org.catalogueoflife.garrulus.indexer.layer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.catalogueoflife.garrulus.indexer.harvest.OpenAlexHarvestHandler;
import org.catalogueoflife.garrulus.indexer.harvest.WorksPager;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores the records of the harvest layers. It adds the age rule and the origin to {@link WorksPager#store}, which
 * upserts the work and queues its RESOLVE_CONTENT (on create) and MERGE_WORKS (on a duplicate) in one transaction.
 *
 * <ul>
 *   <li>A record published before {@code garrulus.first-year} (2000) is skipped: older literature is not a goal of
 *   Garrulus.</li>
 *   <li>Origin: {@code ONBOARDING} mode gives {@code ONBOARDING}, {@code BACKFILL} gives {@code BACKFILL}; {@code LIVE}
 *   gives {@code LIVE} for a work published within {@value OpenAlexHarvestHandler#LIVE_DAYS} days (UTC) or without
 *   a date, else {@code BACKFILL}. A year alone counts as its last day.</li>
 *   <li>The content of a {@code BACKFILL} work is queued at {@code BACKFILL}, whatever priority the caller asks.</li>
 * </ul>
 */
@Component
public class LayerWriter {

  public enum Stored { CREATED, MERGED, SKIPPED_TOO_OLD }

  private final WorksPager pager;
  private final Clock clock;
  /** Records published before this year are skipped ({@code garrulus.first-year}). */
  private final int firstYear;

  public LayerWriter(WorksPager pager, Clock clock, @Value("${garrulus.first-year:2000}") int firstYear) {
    this.pager = pager;
    this.clock = clock;
    this.firstYear = firstYear;
  }

  public Stored store(HarvestedWork hw, LayerMode mode, TaskPriority contentPriority) {
    Work w = hw.work();
    Integer year = year(w);
    if (year != null && year < firstYear) return Stored.SKIPPED_TOO_OLD;
    String origin = origin(w, mode);
    TaskPriority priority = "BACKFILL".equals(origin) ? TaskPriority.BACKFILL : contentPriority;
    return pager.store(withOrigin(w, origin), hw.layer(), hw.ids(), priority, null, hw.declaredLanguage(), hw.texts(),
        hw.titleLang(), hw.abstractLang())
        ? Stored.CREATED : Stored.MERGED;
  }

  private static @Nullable Integer year(Work w) {
    if (w.year() != null) return w.year().intValue();
    return w.publicationDate() == null ? null : w.publicationDate().getYear();
  }

  String origin(Work w, LayerMode mode) {
    return switch (mode) {
      case ONBOARDING -> "ONBOARDING";
      case BACKFILL -> "BACKFILL";
      case LIVE -> {
        LocalDate latest = w.publicationDate() != null ? w.publicationDate()
            : w.year() != null ? LocalDate.of(w.year(), 12, 31) : null;
        LocalDate oldest = LocalDate.now(clock.withZone(ZoneOffset.UTC))
            .minusDays(OpenAlexHarvestHandler.LIVE_DAYS);
        yield latest != null && latest.isBefore(oldest) ? "BACKFILL" : "LIVE";
      }
    };
  }

  private static Work withOrigin(Work w, String origin) {
    return new Work(w.id(), w.openalexId(), w.doi(), w.sourceId(), w.title(), w.publicationDate(), w.year(),
        w.volume(), w.issue(), w.pages(), w.authors(), w.type(), w.language(), w.oaStatus(), w.license(),
        w.bestOaUrl(), w.abstractText(), w.abstractSource(), origin, w.state(), w.textLevel(), w.codes(),
        w.taxgroups(), w.hasActs(), w.firstSeenAt(), w.modified(), w.abstractJats(), w.pmcid(), w.openalexHasTei(),
        w.openalexCheckedAt(), w.metaLayer(), w.abstractJatsSource(), w.accessLayer());
  }
}
