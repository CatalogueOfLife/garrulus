package org.catalogueoflife.nametracker.indexer.finder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.task.TaskHandler;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code BUILD_DICTIONARY}, payload {@code {"key":k}}, enqueued by the XR flip. Without {@code dictionary-dir}, or with
 * a remote finder, it does nothing. Else, unless {@code dictionary-<k>.bin} is already in the directory, it streams the
 * XR zip from {@code xr-zip-url} (no local copy of the zip), builds the dictionary and, if its release key is k, writes
 * the file (temp file, atomic rename). Another release key means the latest zip is not k yet: WARN and keep the
 * current dictionary. If k is the current release, the in-process finder is then swapped to a pipeline on that file.
 * A failed build (bad zip) is a WARN and keeps the current dictionary; only a failed connection or download is an
 * outage. A fresh deployment without any dictionary enqueues the build for the current release at start. Redelivery is harmless: the file is replaced by an equal one and the swap repeats. A dictionary change is no finder
 * upgrade ({@link FinderRollout} looks at the configHash alone).
 */
@Component
public class BuildDictionaryHandler implements TaskHandler {

  public static final String TYPE = "BUILD_DICTIONARY";
  private static final Logger LOG = LoggerFactory.getLogger(BuildDictionaryHandler.class);
  public static final String FILE_PREFIX = "dictionary-";
  public static final String FILE_SUFFIX = ".bin";

  private final ObjectProvider<NameFinder> finder;
  private final IndexMapper index;
  private final HarvestOutage outage;
  private final TaskQueue queue;
  private final String finderUrl;
  private final String dictionaryDir;
  private final String zipUrl;
  private final String gnfinderUrl;
  private final String configFile;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
      .followRedirects(HttpClient.Redirect.NORMAL).build();

  public BuildDictionaryHandler(ObjectProvider<NameFinder> finder, IndexMapper index, HarvestOutage outage,
      TaskQueue queue, @Value("${nametracker.finder.url:}") String finderUrl,
      @Value("${nametracker.finder.dictionary-dir:}") String dictionaryDir,
      @Value("${nametracker.finder.xr-zip-url:https://download.checklistbank.org/col/xr_latest_coldp.zip}") String zipUrl,
      @Value("${nametracker.finder.gnfinder-url:}") String gnfinderUrl,
      @Value("${nametracker.finder.config:}") String configFile) {
    this.finder = finder;
    this.index = index;
    this.outage = outage;
    this.queue = queue;
    this.finderUrl = finderUrl == null ? "" : finderUrl.strip();
    this.dictionaryDir = dictionaryDir == null ? "" : dictionaryDir.strip();
    this.zipUrl = zipUrl.strip();
    this.gnfinderUrl = gnfinderUrl;
    this.configFile = configFile;
  }

  @Override
  public String type() {
    return TYPE;
  }

  /** Dictionaries are built here: a dictionary-dir is set and the finder is not remote. */
  private boolean active() {
    return !dictionaryDir.isEmpty() && finderUrl.isEmpty();
  }

  /** A fresh deployment (no dictionary yet) builds the one of the current release; the flip's dedup key makes it once. */
  @EventListener(ApplicationReadyEvent.class)
  public void bootstrap() {
    if (!active() || !(finder.getIfAvailable() instanceof ReloadableNameFinder r) || r.ready()) return;
    XrRelease current = index.currentRelease();
    if (current == null) {
      LOG.info("No dictionary yet and no current XR release: BUILD_DICTIONARY follows the first XR switch");
      return;
    }
    queue.enqueue(TYPE, "{\"key\":" + current.key() + "}", TaskPriority.REPROCESS, "build-dictionary:" + current.key());
    LOG.info("No dictionary yet: BUILD_DICTIONARY enqueued for the current XR release {}", current.key());
  }

  /** The dictionary file of release {@code key} in {@code dir}. */
  public static Path file(Path dir, int key) {
    return dir.resolve(FILE_PREFIX + key + FILE_SUFFIX);
  }

  @Override
  public void handle(Task task) throws Exception {
    int key = FinderJson.mapper().readTree(task.payload()).path("key").asInt();
    if (!active()) {
      LOG.info("BUILD_DICTIONARY {}: no dictionary-dir or a remote finder, nothing to do", key);
      return;
    }
    if (!(finder.getIfAvailable() instanceof ReloadableNameFinder reloadable)) {
      LOG.warn("BUILD_DICTIONARY {}: the finder is not reloadable, nothing to do", key);
      return;
    }
    Path dir = Path.of(dictionaryDir);
    Files.createDirectories(dir);
    Path target = file(dir, key);
    if (!Files.isRegularFile(target)) {
      if (!build(key, target)) return;
    }
    XrRelease current = index.currentRelease();
    if (current == null || current.key() != key) {
      LOG.info("Dictionary {} is built but release {} is not current ({}): finder not swapped", target, key,
          current == null ? "none" : current.key());
      return;
    }
    reloadable.swap(FinderConfig.pipeline(target, gnfinderUrl, configFile));
    LOG.info("The finder now uses dictionary {} of XR release {}", target, key);
  }

  /** Builds and writes the dictionary of release {@code key}; false if the latest zip is another release. */
  private boolean build(int key, Path target) throws IOException {
    HttpRequest req = HttpRequest.newBuilder(URI.create(zipUrl)).header("User-Agent", PoliteHttp.userAgent()).GET()
        .build();
    ColDictionary d;
    HttpResponse<InputStream> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while downloading " + zipUrl, e);
    } catch (IOException e) {
      throw new ServiceOutageException("xr-zip", outage.retryAt(), e);
    }
    try (InputStream raw = resp.body()) {
      int status = resp.statusCode();
      if (status >= 500 || status == 429) {
        throw new ServiceOutageException("xr-zip", outage.retryAt(), new IOException("HTTP " + status + " for " + zipUrl));
      }
      if (status != 200) {
        LOG.error("BUILD_DICTIONARY {}: HTTP {} for {}; no dictionary built", key, status, zipUrl);
        return false;
      }
      TransportWatch in = new TransportWatch(raw);
      try {
        d = ColDictionaryBuilder.fromXrZip(in, CommonWords.load(CommonWords.DEFAULT_LANGS));
      } catch (IOException | RuntimeException e) {
        if (in.failure != null) throw new ServiceOutageException("xr-zip", outage.retryAt(), in.failure);
        LOG.warn("Dictionary build for {} failed; keeping the current dictionary: {}", key, e.toString());
        return false;
      }
    }
    if (!String.valueOf(key).equals(d.releaseKey())) {
      LOG.warn("BUILD_DICTIONARY: latest XR zip is {}, not {}; keeping the current dictionary", d.releaseKey(), key);
      return false;
    }
    Path tmp = Files.createTempFile(target.getParent(), FILE_PREFIX + key + "-", ".tmp");
    try {
      ColDictionaryIO.write(d, tmp);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
    LOG.info("Built dictionary {}: {}", target, d.stats());
    return true;
  }

  /** Remembers a read failure of the download, which tells a dropped connection from a bad zip. */
  private static final class TransportWatch extends FilterInputStream {
    volatile @Nullable IOException failure;

    TransportWatch(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      try {
        return super.read();
      } catch (IOException e) {
        failure = e;
        throw e;
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      try {
        return super.read(b, off, len);
      } catch (IOException e) {
        failure = e;
        throw e;
      }
    }
  }
}
