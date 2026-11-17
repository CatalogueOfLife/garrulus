package org.catalogueoflife.nametracker.indexer.finder;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.catalogueoflife.nametracker.finder.api.Act;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.ClassificationContext;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderUnavailableException;
import org.catalogueoflife.nametracker.finder.remote.FinderUnavailableException;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.index.ContextRow;
import org.catalogueoflife.nametracker.store.index.FinderRun;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.index.WorkFacts;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code FIND_NAMES}, payload {@code {"workId":n}}. Loads the document of the work, runs the {@link NameFinder} and, in
 * one transaction, replaces the work's mentions, contexts and (through the cascade of
 * {@link IndexMapper#deleteWorkIndex}) its work names, then marks the work NAMES_FOUND. A listed work (INDEXED) keeps
 * its state and its live index instead: the result replaces the work's pending index, which {@code MATCH_NAMES} swaps
 * in, so the work never drops out of the listings. "Already named" means named into the pending index when there is
 * one. After the commit it deletes the spool file and enqueues {@code MATCH_NAMES} at this task's priority and
 * run_after. A work without text skips the
 * finder. A context's verbatim is stored capped at 200 code points. An outage of the finder defers the
 * task by 30 minutes without using an attempt. Without a configured finder this handler is not enabled and its tasks
 * wait in the queue.
 */
@Component
public class FindNamesHandler implements TaskHandler {

  public static final String TYPE = "FIND_NAMES";
  public static final String NEXT_TYPE = "MATCH_NAMES";
  static final Duration OUTAGE_DELAY = Duration.ofMinutes(30);
  static final int INSERT_CHUNK = 1000;
  static final int SNIPPET_MARGIN = 80;
  static final int SNIPPET_MAX = 200;
  private static final Logger LOG = LoggerFactory.getLogger(FindNamesHandler.class);
  /** States in which a work is listed by the public API: a re-index of such a work goes to the pending index. */
  private static final Set<String> LISTED = Set.of("INDEXED", "NOTIFIED");

  private final NameFinder finder;
  private final DocumentLoader loader;
  private final Spool spool;
  private final WorkMapper works;
  private final ContentMapper contents;
  private final IndexMapper index;
  private final TaskQueue queue;
  private final TransactionTemplate tx;
  private final Clock clock;

  public FindNamesHandler(ObjectProvider<NameFinder> finder, DocumentLoader loader, Spool spool, WorkMapper works,
      ContentMapper contents, IndexMapper index, TaskQueue queue, TransactionTemplate tx, Clock clock) {
    this.finder = finder.getIfAvailable();
    this.loader = loader;
    this.spool = spool;
    this.works = works;
    this.contents = contents;
    this.index = index;
    this.queue = queue;
    this.tx = tx;
    this.clock = clock;
    if (this.finder == null) {
      LOG.warn("No name finder configured (set NAMETRACKER_FINDER_URL or FINDER_DICTIONARY): "
          + "FIND_NAMES tasks wait in the queue");
    }
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public boolean enabled() {
    return finder != null;
  }

  @Override
  public void handle(Task task) throws Exception {
    long workId = FinderJson.mapper().readTree(task.payload()).path("workId").asLong();
    Work work = works.get(workId);
    if (work == null) {
      LOG.warn("FIND_NAMES for unknown work {}", workId);
      return;
    }
    // A repeat (redelivery, or a second task for the same text) is a no-op that only makes sure MATCH_NAMES is queued.
    // Never fall back to the abstract when the full text that was already named is merely gone from the spool.
    String chosen = DocumentLoader.chosenSha(work, work.textLevel(), contents.listByWork(workId));
    Named named = named(workId);
    if (named != null && chosen.equals(named.sha())) {
      finish(task, workId, named.run(), chosen, chosen);
      return;
    }

    DocumentLoader.Loaded loaded = loader.load(work);
    Document doc = loaded.doc();
    Map<String, String> diagnostics = new LinkedHashMap<>(loaded.diagnostics());

    // a document without blocks has no text: no finder call, no mentions
    FinderResult result = doc.blocks().isEmpty() ? null : guarded(() -> finder.find(doc, FinderOptions.defaults()));
    FinderInfo info = result == null ? guarded(finder::info) : result.info();
    if (result != null) diagnostics.putAll(result.diagnostics());

    long[] runId = new long[1];
    String[] namedSha = new String[1];
    tx.executeWithoutResult(status -> {
      index.lockWork(workId); // finder runs on one work are serial; re-check what the winner did
      Named current = named(workId);
      if (current != null && (current.sha().equals(chosen) || current.sha().equals(loaded.docSha()))) {
        runId[0] = current.run();
        namedSha[0] = current.sha();
        return;
      }
      runId[0] = index.finderRunId(new FinderRun(null, info.name(), info.version(), info.configHash(),
          info.dictionaryKey()));
      WorkFacts facts = index.workFacts(workId);
      if (facts != null && LISTED.contains(facts.state())) {
        // a re-index: the live index stays until MATCH_NAMES swaps the pending one in
        index.deletePendingIndex(workId);
        if (result != null) store(workId, doc, result, diagnostics, true);
        index.setPending(workId, runId[0], loaded.docSha(), FinderJson.mapper().writeValueAsString(diagnostics));
      } else {
        index.deleteWorkIndex(workId);
        if (result != null) store(workId, doc, result, diagnostics, false);
        index.setNamesFound(workId, runId[0], loaded.docSha());
        index.setDiagnostics(workId, FinderJson.mapper().writeValueAsString(diagnostics));
      }
      namedSha[0] = loaded.docSha();
    });
    finish(task, workId, runId[0], namedSha[0], loaded.docSha());
  }

  /** The document sha and finder run the work was last named with. */
  private record Named(String sha, long run) {}

  /** What the work was last named with: its pending index if it has one, else its live index; null if never named. */
  private @Nullable Named named(long workId) {
    Long pendingRun = index.pendingFinderRun(workId);
    if (pendingRun != null) return new Named(index.pendingDocSha(workId), pendingRun);
    String sha = index.namesDocSha(workId);
    Long run = index.currentFinderRun(workId);
    return sha == null || run == null ? null : new Named(sha, run);
  }

  /**
   * Queues MATCH_NAMES first, so that a crash after the spool is gone cannot lose it, then drops the spool files. The
   * follow-up takes the place of this task in the queue (its priority and run_after), so it runs before the peers
   * queued after this one instead of behind the whole backlog.
   */
  private void finish(Task task, long workId, long runId, String namedSha, String loadedSha) {
    queue.enqueue(NEXT_TYPE, "{\"workId\":" + workId + "}", task.priority(),
        "match:" + workId + ":" + runId + ":" + namedSha.substring(0, 12), task.runAfter());
    spool.delete(workId, namedSha);
    spool.delete(workId, loadedSha);
  }

  /** Runs a finder call; an unavailable finder defers the task by 30 minutes without using an attempt. */
  private <T> T guarded(Supplier<T> call) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      for (Throwable t = e; t != null; t = t.getCause()) {
        String service = t instanceof GnfinderUnavailableException ? "gnfinder"
            : t instanceof FinderUnavailableException ? "finder" : null;
        if (service != null) throw new ServiceOutageException(service, clock.instant().plus(OUTAGE_DELAY), e);
        if (t.getCause() == t) break;
      }
      throw e;
    }
  }

  /** Inserts contexts and mentions into the live index, or with {@code pending} into the pending index. */
  private void store(long workId, Document doc, FinderResult result, Map<String, String> diagnostics,
      boolean pending) {
    Map<String, Block> blocks = new HashMap<>();
    doc.blocks().forEach(b -> blocks.put(b.id(), b));

    Map<String, Long> contextIds = new HashMap<>();
    for (ClassificationContext c : result.contexts()) {
      ContextRow row = new ContextRow(null, workId, c.id(), cap(c.verbatim(), SNIPPET_MAX),
          FinderJson.mapper().writeValueAsString(c.ranks()), c.scope().name(), c.blockId(), c.untilBlockId());
      long id = pending ? index.insertPendingContext(row) : index.insertContext(row);
      contextIds.put(c.id(), id);
    }
    Map<String, String> actTypes = new HashMap<>();
    for (Act a : result.acts()) actTypes.putIfAbsent(a.mentionId(), a.type().name());

    // only mentions that point at an exact span of their block are stored
    List<Mention> valid = new ArrayList<>();
    for (Mention m : result.mentions()) {
      Block b = blocks.get(m.blockId());
      if (b != null && m.start() >= 0 && m.end() > m.start() && m.end() <= CodePoints.length(b.text())
          && CodePoints.slice(b.text(), m.start(), m.end()).equals(m.verbatim()) && !m.name().isBlank()) {
        valid.add(m);
      }
    }
    if (valid.size() < result.mentions().size()) {
      diagnostics.put("droppedMentions", Integer.toString(result.mentions().size() - valid.size()));
      LOG.warn("Work {}: dropped {} mentions that do not point at their text", workId,
          result.mentions().size() - valid.size());
    }

    // name strings in name order, so that concurrent works lock their rows in the same order
    Map<String, Long> nameIds = new TreeMap<>();
    Map<String, Mention> firstByName = new HashMap<>();
    valid.forEach(m -> firstByName.putIfAbsent(m.name(), m));
    for (String name : new TreeMap<>(firstByName).keySet()) {
      Mention m = firstByName.get(name);
      nameIds.put(name, index.nameStringId(name, m.kind().name(), m.rank() == null ? null : m.rank().name()));
    }

    List<MentionRow> rows = new ArrayList<>(valid.size());
    for (Mention m : valid) {
      Block b = blocks.get(m.blockId());
      rows.add(new MentionRow(null, workId, nameIds.get(m.name()), b.id(), b.type().name(), m.start(), m.end(),
          m.verbatim(), m.authorship(), m.qualifier(), (float) m.confidence(), actTypes.get(m.id()),
          m.contextId() == null ? null : contextIds.get(m.contextId()), snippet(b.text(), m.start(), m.end()),
          FinderJson.mapper().writeValueAsString(m.evidence())));
    }
    for (int i = 0; i < rows.size(); i += INSERT_CHUNK) {
      List<MentionRow> chunk = rows.subList(i, Math.min(rows.size(), i + INSERT_CHUNK));
      if (pending) {
        index.insertPendingMentions(chunk);
      } else {
        index.insertMentions(chunk);
      }
    }
  }

  /**
   * At most {@code max} code points of the text: a context's verbatim may be a whole heading block, and of a closed
   * full text only snippets may be kept.
   */
  static String cap(String text, int max) {
    return CodePoints.length(text) <= max ? text : CodePoints.slice(text, 0, max);
  }

  /**
   * The text 80 code points either side of the span, cut at the block's edges, with a word cut by the margin dropped
   * at either end; at most 200 code points.
   */
  static String snippet(String text, int start, int end) {
    int[] cps = text.codePoints().toArray();
    int margin = Math.max(0, Math.min(SNIPPET_MARGIN, (SNIPPET_MAX - (end - start)) / 2));
    int from = Math.max(0, start - margin);
    int to = Math.min(cps.length, end + margin);
    if (from > 0 && !Character.isWhitespace(cps[from - 1])) {
      while (from < start && !Character.isWhitespace(cps[from])) from++;
    }
    while (from < start && Character.isWhitespace(cps[from])) from++;
    if (to < cps.length && !Character.isWhitespace(cps[to])) {
      while (to > end && !Character.isWhitespace(cps[to - 1])) to--;
    }
    while (to > end && Character.isWhitespace(cps[to - 1])) to--;
    return new String(cps, from, Math.min(to - from, SNIPPET_MAX));
  }
}
