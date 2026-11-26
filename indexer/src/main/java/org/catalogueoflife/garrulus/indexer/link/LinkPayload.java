package org.catalogueoflife.garrulus.indexer.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.ident.IdScheme.Entity;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The payload of {@code LINK_WORK} and {@code LINK_SOURCE}: {@code {"workId":n}} / {@code {"sourceId":n}} (one subject),
 * {@code {"ids":[…]}} (at most {@value #MAX_IDS}: the due retries), or {@code {"afterId":n,"batch":b,"schemes":[…]|null}}
 * (a page, continued by the next one). Shared by both handlers; a page of a batched task is processed in one go.
 */
final class LinkPayload {

  static final int MAX_IDS = 5000;

  private final List<Long> ids;
  private final @Nullable Long afterId;
  private final int batch;
  private final @Nullable Set<String> schemes;

  private LinkPayload(List<Long> ids, @Nullable Long afterId, int batch, @Nullable Set<String> schemes) {
    this.ids = ids;
    this.afterId = afterId;
    this.batch = batch;
    this.schemes = schemes;
  }

  static LinkPayload parse(Task task, Entity entity, int defaultBatch) {
    JsonNode p = FinderJson.mapper().readTree(task.payload());
    String single = entity == Entity.WORK ? "workId" : "sourceId";
    Set<String> schemes = null;
    if (p.hasNonNull("schemes")) {
      schemes = new LinkedHashSet<>();
      for (JsonNode s : p.path("schemes")) schemes.add(s.asString());
    }
    if (p.hasNonNull(single)) return new LinkPayload(List.of(p.path(single).asLong()), null, 1, schemes);
    if (p.hasNonNull("ids")) {
      List<Long> ids = new ArrayList<>();
      for (JsonNode i : p.path("ids")) ids.add(i.asLong());
      if (ids.size() > MAX_IDS) throw new IllegalArgumentException("At most " + MAX_IDS + " ids: " + ids.size());
      return new LinkPayload(ids, null, ids.size(), schemes);
    }
    if (p.has("afterId")) {
      int batch = Math.max(1, Math.min(MAX_IDS, p.path("batch").asInt(defaultBatch)));
      return new LinkPayload(List.of(), p.path("afterId").asLong(), batch, schemes);
    }
    throw new IllegalArgumentException("A link task needs " + single + ", ids or afterId: " + task.payload());
  }

  List<Long> ids() {
    return ids;
  }

  boolean paged() {
    return afterId != null;
  }

  long afterId() {
    return afterId;
  }

  int batch() {
    return batch;
  }

  @Nullable Set<String> schemes() {
    return schemes;
  }

  /** The payload of the page after this one, which ended at {@code last}. */
  String next(long last) {
    var m = FinderJson.mapper().createObjectNode();
    m.put("afterId", last);
    m.put("batch", batch);
    if (schemes == null) m.putNull("schemes");
    else {
      var a = m.putArray("schemes");
      schemes.forEach(a::add);
    }
    return m.toString();
  }

  /** Enqueues the next page of a batched task at the priority and run_after of the task, key {@code <prefix><sha12>}. */
  void continueWith(TaskQueue queue, Task task, String type, String keyPrefix, long last) {
    String next = next(last);
    queue.enqueue(type, next, task.priority(), keyPrefix + sha12(next), runAfter(task));
  }

  private static @Nullable Instant runAfter(Task task) {
    return task.runAfter();
  }

  static String sha12(String s) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d).substring(0, 12);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
