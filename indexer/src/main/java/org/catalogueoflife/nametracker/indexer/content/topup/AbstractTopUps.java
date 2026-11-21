package org.catalogueoflife.nametracker.indexer.content.topup;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.lang.LanguageService;
import org.catalogueoflife.nametracker.indexer.lang.ParallelTexts;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.content.TextLookupMapper;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.catalogueoflife.nametracker.store.lang.WorkText;
import org.catalogueoflife.nametracker.store.lang.WorkTextMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkUpsert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The abstract top-up chain of {@code RESOLVE_CONTENT}: Europe PMC, Springer, SciELO, CiNii in order, each asked at most
 * once in {@value #RETRY_DAYS} days per work ({@code text_lookup}). A service is asked while the work has no abstract
 * (the first abstract found is stored by {@link WorkMapper#fillAbstract}, which fills nothing that exists), or always
 * when it is {@link AbstractTopUp#always()} (SciELO's parallel texts and declared language). Only a definite answer is
 * recorded: an outage, a spent cap or a refused key is not, so it cannot block the service for the retry period. An
 * outage defers the task only on a {@code reprocess} run, otherwise it is logged and the next service is asked.
 */
@Component
public class AbstractTopUps {

  static final int RETRY_DAYS = 180;
  /** Parallel texts of a service rank below every harvest layer's. */
  static final int TEXT_RANK = 5;
  static final Duration OUTAGE_DELAY = Duration.ofMinutes(30);
  private static final Logger LOG = LoggerFactory.getLogger(AbstractTopUps.class);

  private final List<AbstractTopUp> topUps;
  private final WorkMapper works;
  private final TextLookupMapper lookups;
  private final WorkTextMapper texts;
  private final ParallelTexts parallelTexts;
  private final LanguageService languages;
  private final TransactionTemplate tx;
  private final Clock clock;

  public AbstractTopUps(List<AbstractTopUp> topUps, WorkMapper works, TextLookupMapper lookups, WorkTextMapper texts,
      ParallelTexts parallelTexts, LanguageService languages, TransactionTemplate tx, Clock clock) {
    this.topUps = List.copyOf(topUps);
    this.works = works;
    this.lookups = lookups;
    this.texts = texts;
    this.parallelTexts = parallelTexts;
    this.languages = languages;
    this.tx = tx;
    this.clock = clock;
  }

  /** Spring: the services are the beans ordered by {@code @Order}, possibly none. */
  @Autowired
  public AbstractTopUps(ObjectProvider<AbstractTopUp> topUps, WorkMapper works, TextLookupMapper lookups,
      WorkTextMapper texts, ParallelTexts parallelTexts, LanguageService languages, TransactionTemplate tx,
      Clock clock) {
    this(topUps.orderedStream().toList(), works, lookups, texts, parallelTexts, languages, tx, clock);
  }

  /**
   * Runs the chain for the work. Returns what was stored and whether a service was down. Throws
   * {@link ServiceOutageException} for an outage on a {@code reprocess} run.
   */
  public Result run(Work work, @Nullable Source source, boolean reprocess) {
    Work cur = work;
    boolean wrote = false;
    boolean outage = false;
    for (AbstractTopUp t : topUps) {
      if (!t.enabled()) continue;
      if (WorkUpsert.effectiveAbstract(cur) != null && !t.always()) continue; // before applies(): it may read the DB
      if (!t.applies(cur, source)) continue;
      if (lookups.triedSince(cur.id(), t.service(), clock.instant().minus(Duration.ofDays(RETRY_DAYS)))) continue;
      Optional<TopUpResult> got;
      try {
        got = t.fetch(cur, source);
      } catch (BudgetExhaustedException | TopUpSkipped e) {
        LOG.info("Top-up {} skipped for work {}: {}", t.service(), cur.id(), e.getMessage());
        continue;
      } catch (IOException | RuntimeException e) {
        if (HarvestOutage.retryable(e) != null || e instanceof ServiceOutageException) {
          if (reprocess) throw new ServiceOutageException(t.service(), clock.instant().plus(OUTAGE_DELAY), e);
          outage = true;
          LOG.warn("Top-up {} is down, skipped for work {}: {}", t.service(), cur.id(), e.toString());
        } else if (e instanceof HttpProblem p && p.status() != 401 && p.status() != 403) {
          LOG.warn("Top-up {} found nothing for work {}: {}", t.service(), cur.id(), e.toString());
          lookups.record(cur.id(), t.service(), false); // a refusal that would repeat
        } else {
          LOG.warn("Top-up {} failed for work {}: {}", t.service(), cur.id(), e.toString());
        }
        continue;
      }
      if (got.isEmpty() || !got.get().found()) {
        lookups.record(cur.id(), t.service(), false);
        continue;
      }
      String service = t.service();
      long id = cur.id();
      TopUpResult found = got.get();
      tx.executeWithoutResult(status -> store(id, service, found));
      wrote = true;
      Work again = works.get(cur.id());
      if (again == null) return new Result(wrote, outage);
      cur = again;
    }
    return new Result(wrote, outage);
  }

  /** {@code changed}: something was stored (read the work again); {@code outage}: a service was down and not asked. */
  public record Result(boolean changed, boolean outage) {
  }

  /** In one transaction: the abstract when the work has none, the parallel texts, the declared language, the lookup. */
  private void store(long workId, String service, TopUpResult r) {
    Work w = works.get(workId);
    if (w == null) return;
    if (r.hasAbstract() && WorkUpsert.effectiveAbstract(w) == null
        && works.fillAbstract(workId, blankToNull(r.abstractText()), blankToNull(r.abstractJats()),
            r.abstractSource()) == 1) {
      String lang = LanguageCodes.normalise(r.abstractLang());
      if (lang != null) works.setTextLangs(workId, null, lang);
      w = works.get(workId);
    }
    List<TextIn> kept = parallelTexts.of(w.title(), WorkUpsert.effectiveAbstract(w), r.parallels());
    for (TextIn t : kept) {
      texts.upsertRanked(new WorkText(workId, t.field(), t.lang(), t.kind(), t.text(), r.abstractSource(), null, null),
          TEXT_RANK);
    }
    if (!kept.isEmpty()) texts.refreshTitleEn(workId);
    if (r.declaredLanguage() != null && LanguageCodes.normalise(r.declaredLanguage()) != null) {
      languages.evidence(workId, LanguageService.declared(workId, r.abstractSource(), r.declaredLanguage()));
    }
    lookups.record(workId, service, true);
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
