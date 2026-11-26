package org.catalogueoflife.garrulus.indexer.finder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.pipeline.PipelineFactory;
import org.catalogueoflife.garrulus.finder.remote.RemoteNameFinder;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.XrRelease;
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
 * Chooses the {@link NameFinder}: a remote finder service when {@code garrulus.finder.url}
 * ({@code GARRULUS_FINDER_URL}) is set; else the in-process pipeline when {@code garrulus.finder.dictionary}
 * ({@code FINDER_DICTIONARY}) or {@code garrulus.finder.dictionary-dir} is set, with {@code GNFINDER_URL} and the
 * optional {@code FINDER_CONFIG} file of overrides; else none, and {@code FIND_NAMES} tasks wait in the queue (see
 * {@link FindNamesHandler}).
 */
@Configuration
public class FinderConfig {

  private static final Logger LOG = LoggerFactory.getLogger(FinderConfig.class);

  /** True when a finder URL, a dictionary or a dictionary directory is configured. */
  static class Configured implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
      var env = ctx.getEnvironment();
      return notBlank(env.getProperty("garrulus.finder.url")) || notBlank(env.getProperty("garrulus.finder.dictionary"))
          || notBlank(env.getProperty("garrulus.finder.dictionary-dir"));
    }
  }

  /**
   * With {@code dictionary-dir} the in-process pipeline is always wrapped in a {@link ReloadableNameFinder}, on
   * {@code dictionary-<currentKey>.bin} if present, else {@code FINDER_DICTIONARY}, else the newest
   * {@code dictionary-*.bin} of the directory. With none of them the wrapper is empty (not ready, WARN) until
   * {@code BUILD_DICTIONARY} swaps a dictionary in. A remote finder is never wrapped.
   */
  @Bean
  @Conditional(Configured.class)
  NameFinder nameFinder(@Value("${garrulus.finder.url:}") String url,
      @Value("${garrulus.finder.dictionary:}") String dictionary,
      @Value("${garrulus.finder.dictionary-dir:}") String dictionaryDir,
      @Value("${garrulus.finder.gnfinder-url:}") String gnfinderUrl,
      @Value("${garrulus.finder.config:}") String configFile, ObjectProvider<IndexMapper> index) {
    if (notBlank(url)) return new RemoteNameFinder(URI.create(url.strip()));
    if (!notBlank(dictionaryDir)) return pipeline(Path.of(dictionary.strip()), gnfinderUrl, configFile);
    Path dir = Path.of(dictionaryDir.strip());
    Integer key = currentKey(index);
    Path file;
    if (key != null && Files.isRegularFile(BuildDictionaryHandler.file(dir, key))) {
      file = BuildDictionaryHandler.file(dir, key);
    } else if (notBlank(dictionary)) {
      file = Path.of(dictionary.strip());
    } else {
      file = newest(dir);
    }
    if (file == null) {
      LOG.warn("No dictionary in {} and FINDER_DICTIONARY is not set: no name finder until BUILD_DICTIONARY has built one",
          dir);
      return new ReloadableNameFinder(null);
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

  private static final Pattern DICTIONARY_FILE = Pattern.compile("dictionary-(\\d+)\\.bin");

  private static @Nullable Path newest(Path dir) {
    if (!Files.isDirectory(dir)) return null;
    Path best = null;
    int bestKey = -1;
    try (Stream<Path> s = Files.list(dir)) {
      for (Path p : (Iterable<Path>) s::iterator) {
        Matcher m = DICTIONARY_FILE.matcher(p.getFileName().toString());
        if (m.matches() && Integer.parseInt(m.group(1)) > bestKey) {
          bestKey = Integer.parseInt(m.group(1));
          best = p;
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list " + dir, e);
    }
    return best;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
