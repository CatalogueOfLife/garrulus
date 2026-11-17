package org.catalogueoflife.nametracker.indexer.finder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory;
import org.catalogueoflife.nametracker.finder.remote.RemoteNameFinder;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Chooses the {@link NameFinder}: a remote finder service when {@code nametracker.finder.url}
 * ({@code NAMETRACKER_FINDER_URL}) is set; else the in-process pipeline (reloadable per XR release when {@code dictionary-dir} is set) when {@code nametracker.finder.dictionary}
 * ({@code FINDER_DICTIONARY}) is set, with {@code GNFINDER_URL} and the optional {@code FINDER_CONFIG} file of
 * overrides; else none, and {@code FIND_NAMES} tasks wait in the queue (see {@link FindNamesHandler}).
 */
@Configuration
public class FinderConfig {

  private static final Logger LOG = LoggerFactory.getLogger(FinderConfig.class);

  /** True when a finder URL, a dictionary or a dictionary directory is configured. */
  static class Configured implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
      var env = ctx.getEnvironment();
      return notBlank(env.getProperty("nametracker.finder.url")) || notBlank(env.getProperty("nametracker.finder.dictionary"))
          || notBlank(env.getProperty("nametracker.finder.dictionary-dir"));
    }
  }

  /**
   * With {@code dictionary-dir}: the in-process pipeline wrapped in a {@link ReloadableNameFinder}, on
   * {@code dictionary-<currentKey>.bin} if present, else {@code FINDER_DICTIONARY}, else the newest
   * {@code dictionary-*.bin} of the directory; none of them (WARN, no finder until a restart after a
   * {@code BUILD_DICTIONARY}) gives no bean. A remote finder is never wrapped.
   */
  @Bean
  @Conditional(Configured.class)
  @Nullable NameFinder nameFinder(@Value("${nametracker.finder.url:}") String url,
      @Value("${nametracker.finder.dictionary:}") String dictionary,
      @Value("${nametracker.finder.dictionary-dir:}") String dictionaryDir,
      @Value("${nametracker.finder.gnfinder-url:}") String gnfinderUrl,
      @Value("${nametracker.finder.config:}") String configFile, ObjectProvider<IndexMapper> index) {
    if (notBlank(url)) return new RemoteNameFinder(URI.create(url.strip()));
    if (!notBlank(dictionaryDir)) return pipeline(Path.of(dictionary.strip()), gnfinderUrl, configFile);
    Path dir = Path.of(dictionaryDir.strip());
    Path file = null;
    Integer key = currentKey(index);
    if (key != null && Files.isRegularFile(BuildDictionaryHandler.file(dir, key))) {
      file = BuildDictionaryHandler.file(dir, key);
    } else if (notBlank(dictionary)) {
      file = Path.of(dictionary.strip());
    } else {
      file = newest(dir);
    }
    if (file == null) {
      LOG.warn("No dictionary in {} and FINDER_DICTIONARY is not set: no name finder until BUILD_DICTIONARY has built one "
          + "and the indexer is restarted", dir);
      return null;
    }
    LOG.info("Name finder on dictionary {}", file);
    return new ReloadableNameFinder(pipeline(file, gnfinderUrl, configFile));
  }

  /** The in-process pipeline on a dictionary file, with the optional file of overrides. */
  static NameFinder pipeline(Path dictionary, String gnfinderUrl, String configFile) {
    String overrides = null;
    if (notBlank(configFile)) {
      Path f = Path.of(configFile.strip());
      try {
        overrides = Files.readString(f, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read FINDER_CONFIG " + f, e);
      }
    }
    return PipelineFactory.create(dictionary, gnfinderUrl, overrides);
  }

  private static @Nullable Integer currentKey(ObjectProvider<IndexMapper> index) {
    try {
      IndexMapper m = index.getIfAvailable();
      XrRelease r = m == null ? null : m.currentRelease();
      return r == null ? null : r.key();
    } catch (RuntimeException e) {
      LOG.warn("Cannot read the current XR release for the dictionary choice: {}", e.toString());
      return null;
    }
  }

  private static @Nullable Path newest(Path dir) {
    if (!Files.isDirectory(dir)) return null;
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.getFileName().toString().matches("dictionary-\\d+\\.bin"))
          .max(Comparator.comparingInt(p -> Integer.parseInt(
              p.getFileName().toString().replaceAll("\\D", ""))))
          .orElse(null);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list " + dir, e);
    }
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
