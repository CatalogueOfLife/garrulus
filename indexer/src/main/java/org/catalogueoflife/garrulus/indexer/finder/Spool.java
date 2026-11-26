package org.catalogueoflife.garrulus.indexer.finder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A transient store for the document of full text that may not be kept (a licence that is not open). It holds the
 * document between {@code RESOLVE_CONTENT} and {@code FIND_NAMES}; the finder handler deletes the file once the names
 * are stored, and files older than the retention (14 days) are purged daily. Files are {@code <workId>-<sha>.json.gz} in one
 * flat directory, named by the hex SHA-256 of the uncompressed document JSON.
 */
@Component
public class Spool {

  private static final Logger LOG = LoggerFactory.getLogger(Spool.class);
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{8,128}");

  private final Path dir;

  @Value("${garrulus.content.spool-max-age:14d}")
  private Duration maxAge = Duration.ofDays(14);

  private final Clock clock;

  public Spool(@Value("${garrulus.content.spool-dir:./data/spool}") String dir, Clock clock) {
    this.dir = Path.of(dir);
    this.clock = clock;
  }

  /** Writes the document JSON, replacing a file of the same name. */
  public void put(long workId, String sha, byte[] docJson) {
    Path target = file(workId, sha);
    try {
      Files.createDirectories(dir);
      Path tmp = Files.createTempFile(dir, workId + "-" + sha + "-", ".tmp");
      try (var out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
        out.write(docJson);
      }
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write spool file " + target, e);
    }
  }

  public Optional<byte[]> get(long workId, String sha) {
    Path f = file(workId, sha);
    if (!Files.isRegularFile(f)) return Optional.empty();
    try (var in = new GZIPInputStream(Files.newInputStream(f))) {
      return Optional.of(in.readAllBytes());
    } catch (java.nio.file.NoSuchFileException e) {
      return Optional.empty();
    } catch (IOException e) {
      LOG.warn("Unreadable spool file {}: {}", f, e.toString());
      return Optional.empty();
    }
  }

  public void delete(long workId, String sha) {
    try {
      Files.deleteIfExists(file(workId, sha));
    } catch (IOException e) {
      LOG.warn("Cannot delete spool file for {}: {}", sha, e.toString());
    }
  }

  /** Deletes the files last written longer ago than {@code age}; returns how many. */
  public int purgeOlderThan(Duration age) {
    if (!Files.isDirectory(dir)) return 0;
    Instant cutoff = clock.instant().minus(age);
    int n = 0;
    try (Stream<Path> s = Files.list(dir)) {
      for (Path f : (Iterable<Path>) s::iterator) {
        try {
          if (Files.isRegularFile(f) && Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)) {
            Files.delete(f);
            n++;
          }
        } catch (IOException e) {
          LOG.warn("Cannot purge spool file {}: {}", f, e.toString());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list spool " + dir, e);
    }
    return n;
  }

  @Scheduled(cron = "${garrulus.content.spool-purge-cron:0 30 5 * * *}", zone = "UTC")
  void purgeScheduled() {
    int n = purgeOlderThan(maxAge);
    if (n > 0) LOG.info("Purged {} spool files older than {}", n, maxAge);
  }

  private Path file(long workId, String sha) {
    if (!SHA.matcher(sha).matches()) throw new IllegalArgumentException("Not a hex sha: " + sha);
    return dir.resolve(workId + "-" + sha + ".json.gz");
  }
}
