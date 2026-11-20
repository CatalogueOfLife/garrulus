package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.NonRetryableTaskException;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * {@code DISCOVER_SOURCES}, payload {@code {"origin":"DOAJ", "PKP_BEACON", "PLAZI", "COL_REFERENCES", "BLR",…position}}: one page of one discovery origin.
 * The continuation goes in at the task's priority under {@code discover:<origin>:<sha12 of the payload>}. The origin's
 * switch {@code nametracker.discovery.<origin, lower case, hyphens>.enabled} (default true) is read on every run: a
 * disabled origin ends DONE with an INFO and no call. An outage defers the task without using an attempt. Nothing is
 * harvested here: a discovered journal is a CANDIDATE until an admin promotes it.
 */
@Component
public class DiscoverSourcesHandler implements TaskHandler {

  public static final String TYPE = "DISCOVER_SOURCES";
  private static final Logger LOG = LoggerFactory.getLogger(DiscoverSourcesHandler.class);

  private final Map<String, DiscoverySource> sources = new HashMap<>();
  private final TaskQueue queue;
  private final HarvestOutage outage;
  private final Environment env;

  public DiscoverSourcesHandler(List<DiscoverySource> all, TaskQueue queue, HarvestOutage outage, Environment env) {
    for (DiscoverySource s : all) sources.put(s.origin(), s);
    this.queue = queue;
    this.outage = outage;
    this.env = env;
  }

  @Override
  public String type() {
    return TYPE;
  }

  /** The property prefix of an origin: {@code nametracker.discovery.pkp-beacon}. */
  public static String property(String origin) {
    return "nametracker.discovery." + origin.toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** The payload of the first page of an origin. */
  public static String start(String origin) {
    return "{\"origin\":\"" + origin + "\"}";
  }

  /** The dedup key of a task: {@code discover:<origin>:<sha12 of the payload>}. */
  public static String key(String origin, String payload) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
      return "discover:" + origin + ":" + HexFormat.of().formatHex(d).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void handle(Task task) throws Exception {
    JsonNode payload = FinderJson.mapper().readTree(task.payload());
    String origin = payload.path("origin").asString("");
    DiscoverySource source = sources.get(origin);
    if (source == null) throw new NonRetryableTaskException("Unknown discovery origin '" + origin + "'");
    if (!env.getProperty(property(origin) + ".enabled", Boolean.class, true)) {
      LOG.info("DISCOVER_SOURCES {}: {}.enabled is off, no call made", origin, property(origin));
      return;
    }
    String next;
    try {
      next = source.page(payload);
    } catch (ServiceOutageException e) {
      throw e;
    } catch (RuntimeException e) {
      if (HarvestOutage.retryable(e) == null) throw e;
      throw new ServiceOutageException(origin, outage.retryAt(), e);
    }
    if (next != null) queue.enqueue(TYPE, next, task.priority(), key(origin, next));
  }
}
