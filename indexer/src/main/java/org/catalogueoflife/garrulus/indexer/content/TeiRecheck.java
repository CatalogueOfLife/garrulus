package org.catalogueoflife.garrulus.indexer.content;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.catalogueoflife.garrulus.indexer.content.route.OpenAlexTeiRoute;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.store.usage.ApiUsageMapper;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.catalogueoflife.garrulus.store.work.WorkUpsert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Queues the re-resolve of a resolved work whose {@code openalex_has_tei} just turned true
 * ({@link WorkUpsert.UpsertResult#teiAppeared()}), key {@code content:<id>:tei}, payload {@code "tei":true}. It does so
 * only when the TEI route would apply ({@link OpenAlexTeiRoute#applies}: CORE source, not BACKFILL, OpenAlex id, key,
 * switch on), at REPROCESS priority so fresh LIVE works get the day's OpenAlex budget first, and at most
 * {@code garrulus.content.tei-recheck-per-day} (200) a UTC day, counted in {@code api_usage} (service
 * {@code TEI_RECHECK}). Over the cap a re-resolve is not dropped: it is queued for the first later day with room (00:10
 * UTC), so the nightly load is flat. Call it in the transaction of the upsert.
 */
@Component
public class TeiRecheck {

  static final String USAGE = "TEI_RECHECK";
  static final int MAX_DAYS_AHEAD = 60;

  private final OpenAlexTeiRoute route;
  private final WorkMapper works;
  private final SourceMapper sources;
  private final ApiUsageMapper usage;
  private final TaskQueue queue;
  private final Clock clock;
  private final int perDay;

  public TeiRecheck(OpenAlexTeiRoute route, WorkMapper works, SourceMapper sources, ApiUsageMapper usage,
      TaskQueue queue, Clock clock, @Value("${garrulus.content.tei-recheck-per-day:200}") int perDay) {
    this.route = route;
    this.works = works;
    this.sources = sources;
    this.usage = usage;
    this.queue = queue;
    this.clock = clock;
    this.perDay = perDay;
  }

  public void enqueue(WorkUpsert.UpsertResult r) {
    if (!r.teiAppeared()) return;
    Work w = works.get(r.id());
    if (w == null) return;
    Source s = w.sourceId() == null ? null : sources.get(w.sourceId());
    if (!route.applies(w, s)) return;
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    for (int d = 0; d <= MAX_DAYS_AHEAD; d++) {
      LocalDate day = today.plusDays(d);
      if (usage.reserve(USAGE, day, 1) <= perDay) {
        Instant at = d == 0 ? null : day.atTime(LocalTime.of(0, 10)).toInstant(ZoneOffset.UTC);
        queue.enqueue("RESOLVE_CONTENT", "{\"workId\":" + r.id() + ",\"tei\":true}", TaskPriority.REPROCESS,
            "content:" + r.id() + ":tei", at);
        return;
      }
      usage.reserve(USAGE, day, -1);
    }
  }
}
