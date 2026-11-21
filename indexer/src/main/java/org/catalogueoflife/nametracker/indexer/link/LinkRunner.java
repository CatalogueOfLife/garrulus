package org.catalogueoflife.nametracker.indexer.link;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.IdScheme.Entity;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.IdentifierRegistry;
import org.catalogueoflife.nametracker.store.ident.LinkAttempt;
import org.catalogueoflife.nametracker.store.ident.LinkAttemptMapper;
import org.catalogueoflife.nametracker.store.ident.LinkSubject;
import org.catalogueoflife.nametracker.store.ident.LinkTargetMapper;
import org.catalogueoflife.nametracker.store.ident.SourceIdentifierMapper;
import org.catalogueoflife.nametracker.store.ident.SubjectIdentifier;
import org.catalogueoflife.nametracker.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The work of {@code LINK_WORK} and {@code LINK_SOURCE} on a list of ids: loads the targets, runs the enabled lookups
 * in order, stores what they find and records every try in {@code link_attempt}. A miss is tried again after
 * {@value #FIRST_RETRY_DAYS} days, a second miss after {@value #SECOND_RETRY_DAYS} days, a third never; a hit never.
 * Found identifiers are stored with {@code first_layer} (journals: {@code origin}) {@code LINK:<lookup>}; a work
 * identifier that belongs to another work enqueues {@code MERGE_WORKS} for the pair. A lookup's chunk is stored in one
 * transaction with its attempts, and the identifiers are read again before the next lookup runs.
 *
 * <p>A lookup whose service is down or whose daily cap is spent is blocked for the rest of the run: its remaining due
 * targets are put off to the exception's {@code retryAt} without counting a try ({@link LinkAttemptMapper#defer}), and
 * the next lookup runs. The daily retry sweep ({@link LinkScheduler}) picks the put-off targets up again. Only when no
 * lookup could ask anything (every one with due targets was blocked) does the run rethrow the earliest blocking
 * exception, so the task is deferred as a whole (a paged run then continues later instead of putting off page after
 * page); the chunks stored so far stay, a repeat skips them by their attempts.
 */
@Component
public class LinkRunner {

  /**
   * The lookup name of the marker row a work gets when it went through every enabled lookup once and none applied to
   * it: it counts as seen, so the sweep of never-tried works ({@link LinkScheduler}) does not queue it again.
   */
  public static final String NONE_APPLIED = "none";
  static final int FIRST_RETRY_DAYS = 30;
  static final int SECOND_RETRY_DAYS = 180;
  private static final Set<String> COLUMN_SCHEMES = Set.of("ISSN", "ISSN_L", "OPENALEX");
  private static final Logger LOG = LoggerFactory.getLogger(LinkRunner.class);

  private final ObjectProvider<IdentifierLookup> lookups;
  private final LinkAttemptMapper attempts;
  private final LinkTargetMapper targets;
  private final IdentifierRegistry registry;
  private final WorkIdentifierMapper workIds;
  private final SourceIdentifierMapper sourceIds;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final HarvestOutage outage;
  private final Clock clock;

  public LinkRunner(ObjectProvider<IdentifierLookup> lookups, LinkAttemptMapper attempts, LinkTargetMapper targets,
      IdentifierRegistry registry, WorkIdentifierMapper workIds, SourceIdentifierMapper sourceIds, TaskQueue queue,
      TransactionTemplate tx, HarvestOutage outage, Clock clock) {
    this.lookups = lookups;
    this.attempts = attempts;
    this.targets = targets;
    this.registry = registry;
    this.workIds = workIds;
    this.sourceIds = sourceIds;
    this.queue = queue;
    this.tx = tx;
    this.outage = outage;
    this.clock = clock;
  }

  /** The subjects of the entity after {@code afterId}, one page: the listed works, or the sources that have not ceased. */
  List<Long> page(Entity entity, long afterId, int limit) {
    List<LinkSubject> rows = entity == Entity.WORK ? targets.worksAfter(afterId, limit)
        : targets.sourcesAfter(afterId, limit);
    return rows.stream().map(LinkSubject::id).toList();
  }

  /**
   * Runs the lookups over the subjects. With {@code schemes} only the lookups that produce one of them run. A subject
   * that is not listed (a work not INDEXED, a ceased source) is skipped.
   */
  public void run(Entity entity, List<Long> ids, @Nullable Set<String> schemes, TaskPriority priority) {
    if (ids.isEmpty()) return;
    List<IdentifierLookup> active = lookups.orderedStream()
        .filter(l -> l.appliesTo() == entity && l.enabled())
        .filter(l -> schemes == null || l.schemes().stream().anyMatch(schemes::contains))
        .sorted(Comparator.comparingInt(IdentifierLookup::order).thenComparing(IdentifierLookup::name)).toList();
    if (active.isEmpty()) return;
    char key = key(entity);
    List<LinkTarget> current = load(entity, ids);
    if (schemes == null) {
      // a subject that is no longer eligible (a work not listed any more, a ceased or deleted source) is not retried
      Set<Long> eligible = current.stream().map(LinkTarget::id).collect(java.util.stream.Collectors.toSet());
      attempts.clearNextTryOf(key, ids.stream().filter(i -> !eligible.contains(i)).toList());
    }
    if (current.isEmpty()) return;
    Map<Long, Map<String, LinkAttempt>> tried = new HashMap<>();
    for (LinkAttempt a : attempts.forSubjects(key, current.stream().map(LinkTarget::id).toList())) {
      tried.computeIfAbsent(a.subjectId(), k -> new HashMap<>()).put(a.lookup(), a);
    }
    Instant now = clock.instant();
    boolean asked = false;
    RuntimeException blocked = null;
    for (IdentifierLookup lookup : active) {
      List<LinkTarget> due = new ArrayList<>();
      for (LinkTarget t : current) {
        LinkAttempt a = tried.getOrDefault(t.id(), Map.of()).get(lookup.name());
        if (!due(a, now)) continue;
        if (lookup.applies(t)) due.add(t);
        else if (a != null) attempts.clearNextTry(key, t.id(), lookup.name()); // nothing left to look for: no more retries
      }
      boolean wrote = false;
      int size = Math.max(1, lookup.batchSize());
      for (int i = 0; i < due.size(); i += size) {
        List<LinkTarget> chunk = due.subList(i, Math.min(due.size(), i + size));
        Map<Long, List<Identifier>> found;
        try {
          found = ask(lookup, chunk);
        } catch (BudgetExhaustedException | ServiceOutageException e) {
          Instant retryAt = retryAt(e);
          List<Long> rest = due.subList(i, due.size()).stream().map(LinkTarget::id).toList();
          attempts.defer(key, rest, lookup.name(), retryAt);
          LOG.info("Link lookup {} is blocked until {} ({}): {} targets put off", lookup.name(), retryAt,
              e.getMessage(), rest.size());
          if (blocked == null || retryAt.isBefore(retryAt(blocked))) blocked = e;
          break;
        }
        asked = true;
        wrote |= store(entity, lookup, chunk, found, tried, priority, now);
      }
      if (wrote) current = load(entity, current.stream().map(LinkTarget::id).toList()); // the next lookup sees them
    }
    if (entity == Entity.WORK && schemes == null) {
      // a work no lookup applied to has no row: mark it seen, so the sweep of never-tried works leaves it
      attempts.markSeen(key, current.stream().map(LinkTarget::id).toList(), NONE_APPLIED);
    }
    if (blocked != null && !asked) throw blocked; // nothing could be asked: the task waits as a whole
  }

  private static Instant retryAt(RuntimeException e) {
    return e instanceof BudgetExhaustedException b ? b.retryAt() : ((ServiceOutageException) e).retryAt();
  }

  private static boolean due(@Nullable LinkAttempt a, Instant now) {
    return a == null || (a.nextTry() != null && !a.nextTry().isAfter(now));
  }

  private Map<Long, List<Identifier>> ask(IdentifierLookup lookup, List<LinkTarget> chunk) {
    try {
      Map<Long, List<Identifier>> found = lookup.lookup(chunk);
      return found == null ? Map.of() : found;
    } catch (BudgetExhaustedException | ServiceOutageException e) {
      throw e;
    } catch (IOException e) {
      throw new ServiceOutageException(lookup.name(), outage.retryAt(), e);
    } catch (RuntimeException e) {
      if (HarvestOutage.retryable(e) != null) {
        LOG.warn("Link lookup {} is down: {}", lookup.name(), e.getMessage());
        throw new ServiceOutageException(lookup.name(), outage.retryAt(), e);
      }
      throw e;
    }
  }

  /** Stores what the chunk found and records the attempts; returns whether an identifier was written. */
  private boolean store(Entity entity, IdentifierLookup lookup, List<LinkTarget> chunk,
      Map<Long, List<Identifier>> found, Map<Long, Map<String, LinkAttempt>> tried, TaskPriority priority,
      Instant now) {
    Set<String> produced = Set.copyOf(lookup.schemes());
    String layer = "LINK:" + lookup.name();
    boolean[] wrote = {false};
    tx.executeWithoutResult(status -> {
      for (LinkTarget t : chunk) {
        List<Identifier> ids = valid(found.get(t.id()), produced, entity);
        LinkAttempt prev = tried.getOrDefault(t.id(), Map.of()).get(lookup.name());
        if (ids.isEmpty()) {
          int n = prev == null ? 1 : prev.attempts() + 1;
          Instant next = n == 1 ? now.plus(Duration.ofDays(FIRST_RETRY_DAYS))
              : n == 2 ? now.plus(Duration.ofDays(SECOND_RETRY_DAYS)) : null;
          attempts.record(key(entity), t.id(), lookup.name(), false, next);
        } else {
          if (entity == Entity.WORK) wrote[0] |= storeWork(t.id(), ids, layer, priority);
          else wrote[0] |= storeSource(t.id(), ids, layer);
          attempts.record(key(entity), t.id(), lookup.name(), true, null);
        }
      }
    });
    return wrote[0];
  }

  private boolean storeWork(long workId, List<Identifier> ids, String layer, TaskPriority priority) {
    for (Long other : workIds.findWorkIds(ids)) {
      if (other != workId) WorksPager.enqueueMerge(queue, workId, other, priority);
    }
    return workIds.insertAll(workId, ids, layer) > 0;
  }

  private boolean storeSource(long sourceId, List<Identifier> ids, String origin) {
    List<Identifier> mine = new ArrayList<>();
    for (Identifier id : ids) {
      Long holder = sourceIds.findSource(id.scheme(), id.value());
      if (holder != null && holder != sourceId) {
        LOG.warn("Source {}: {} {} belongs to source {} already, not stored", sourceId, id.scheme(), id.value(), holder);
      } else {
        mine.add(id);
      }
    }
    return sourceIds.insertAll(sourceId, mine, origin) > 0;
  }

  /** The found identifiers of a scheme the lookup produces and the entity has, normalised, without duplicates. */
  private List<Identifier> valid(@Nullable List<Identifier> raw, Set<String> produced, Entity entity) {
    if (raw == null) return List.of();
    Set<Identifier> out = new LinkedHashSet<>();
    for (Identifier id : raw) {
      IdScheme s = registry.find(id.scheme());
      // a journal's ISSN, ISSN-L and OpenAlex id are its columns, never stored as lookup identifiers
      boolean column = entity == Entity.SOURCE && COLUMN_SCHEMES.contains(id.scheme());
      if (column || s == null || !produced.contains(s.code()) || !s.entities().contains(entity)) {
        LOG.warn("Dropping identifier {} {}: not a scheme of this lookup and entity", id.scheme(), id.value());
        continue;
      }
      String v = s.normalise(id.value());
      if (v != null) out.add(new Identifier(s.code(), v));
    }
    return List.copyOf(out);
  }

  List<LinkTarget> load(Entity entity, List<Long> ids) {
    List<LinkSubject> rows = entity == Entity.WORK ? targets.works(ids) : targets.sources(ids);
    if (rows.isEmpty()) return List.of();
    List<Long> subjectIds = rows.stream().map(LinkSubject::id).toList();
    Map<Long, Map<String, List<String>>> stored = new HashMap<>();
    for (SubjectIdentifier i : entity == Entity.WORK ? targets.workIdentifiers(subjectIds)
        : targets.sourceIdentifiers(subjectIds)) {
      stored.computeIfAbsent(i.subjectId(), k -> new LinkedHashMap<>()).computeIfAbsent(i.scheme(), k -> new ArrayList<>())
          .add(i.value());
    }
    Set<Long> plazi = Set.copyOf(targets.plaziOrBlrSources(rows.stream()
        .map(LinkSubject::sourceId).filter(java.util.Objects::nonNull).distinct().toList()));
    List<LinkTarget> out = new ArrayList<>(rows.size());
    for (LinkSubject r : rows) {
      Map<String, List<String>> known = new LinkedHashMap<>();
      stored.getOrDefault(r.id(), Map.of()).forEach((k, v) -> known.put(k, List.copyOf(v)));
      List<String> issns = new ArrayList<>();
      if (r.issnL() != null) issns.add(r.issnL());
      if (r.issns() != null) r.issns().stream().filter(i -> !issns.contains(i)).forEach(issns::add);
      if (entity == Entity.WORK) {
        if (r.doi() != null) known.putIfAbsent(IdScheme.DOI.code(), List.of(r.doi()));
      } else {
        // a journal's own columns are not mirrored into source_identifier: they join its identifiers here
        if (!issns.isEmpty()) known.put(IdScheme.ISSN.code(), List.copyOf(issns));
        if (r.issnL() != null) known.put(IdScheme.ISSN_L.code(), List.of(r.issnL()));
        if (r.openalexId() != null) known.put(IdScheme.OPENALEX.code(), List.of(r.openalexId()));
      }
      out.add(new LinkTarget(entity, r.id(), r.doi(), List.copyOf(issns), known, r.hasActs(), r.sourceId(),
          r.sourceId() != null && plazi.contains(r.sourceId())));
    }
    return out;
  }

  static char key(Entity entity) {
    return entity == Entity.WORK ? 'W' : 'S';
  }
}
