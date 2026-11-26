package org.catalogueoflife.garrulus.indexer.registry;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.catalogueoflife.garrulus.store.ident.Issn;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes an OpenAlex journal into the registry the way every identity write does (seeding and {@code ADD_SOURCE}): one
 * transaction under the {@link IdentityLocks} of its ISSNs and OpenAlex id that finds the source by the OpenAlex id,
 * else adopts an ISSN-only source found through its ISSNs (sets the OpenAlex id, keeps that source's own ISSNs),
 * upserts the row ({@code upsertSeed}), runs the caller's tier write and then folds every duplicate that shares a key
 * ({@link SourceFold#foldDuplicates}). A deadlock defers the calling task shortly ({@link Deadlock}).
 */
@Component
public class SeedUpsert {

  /** The source that holds the journal afterwards, and the row it was found as before the write (null when new). */
  public record Seeded(long sourceId, @Nullable Source before) {}

  private final SourceMapper sources;
  private final SourceIdentity identity;
  private final IdentityLocks locks;
  private final SourceFold fold;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SeedUpsert(SourceMapper sources, SourceIdentity identity, IdentityLocks locks, SourceFold fold,
      TransactionTemplate tx, Clock clock) {
    this.sources = sources;
    this.identity = identity;
    this.locks = locks;
    this.fold = fold;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * @param build the row for the given ISSNs to keep besides OpenAlex's own (those of an adopted source)
   * @param inTx runs inside the transaction after the upsert and before the fold, with the source id and the row found
   *     before (null when new): the caller's tier write, so the fold sees the tier the journal ends with
   */
  public Seeded upsert(String openalexId, Function<List<String>, Source> build,
      BiConsumer<Long, @Nullable Source> inTx) {
    Source plain = build.apply(List.of());
    List<String> issns = new ArrayList<>();
    add(issns, plain.issnL());
    if (plain.issns() != null) plain.issns().forEach(i -> add(issns, i));
    try {
      Seeded seeded = tx.execute(status -> {
        locks.lock(issns, openalexId);
        Source before = sources.findByOpenalexId(openalexId);
        Source row = plain;
        if (before == null && !issns.isEmpty()) {
          Source hit = identity.find(issns, null);
          if (hit != null && hit.openalexId() == null) {
            locks.lock(JournalCompletion.lookupIssns(hit), null); // its own keys, before it is touched
            Source fresh = sources.get(hit.id());
            if (fresh != null && fresh.openalexId() == null) {
              List<String> kept = fresh.issns() == null ? List.of() : fresh.issns();
              sources.updateIdentity(fresh.id(), null, union(issns, kept), null, null, null, openalexId, null);
              before = fresh;
              row = build.apply(kept);
            }
          }
        }
        long id = sources.upsertSeed(row);
        inTx.accept(id, before);
        return new Seeded(fold.foldDuplicates(id), before);
      });
      return seeded;
    } catch (RuntimeException e) {
      if (Deadlock.is(e)) throw Deadlock.defer(e, clock);
      throw e;
    }
  }

  private static void add(List<String> out, @Nullable String raw) {
    String n = Issn.normalise(raw);
    if (n != null && !out.contains(n)) out.add(n);
  }

  private static List<String> union(List<String> first, List<String> more) {
    List<String> u = new ArrayList<>(first);
    for (String i : more) if (!u.contains(i)) u.add(i);
    return u;
  }
}
