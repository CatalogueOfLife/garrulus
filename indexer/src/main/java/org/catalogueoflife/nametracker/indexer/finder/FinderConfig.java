package org.catalogueoflife.nametracker.indexer.finder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory;
import org.catalogueoflife.nametracker.finder.remote.RemoteNameFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Chooses the {@link NameFinder}: a remote finder service when {@code nametracker.finder.url}
 * ({@code NAMETRACKER_FINDER_URL}) is set; else the in-process pipeline when {@code nametracker.finder.dictionary}
 * ({@code FINDER_DICTIONARY}) is set, with {@code GNFINDER_URL} and the optional {@code FINDER_CONFIG} file of
 * overrides; else none, and {@code FIND_NAMES} tasks wait in the queue (see {@link FindNamesHandler}).
 */
@Configuration
public class FinderConfig {

  /** True when a finder URL or a dictionary is configured. */
  static class Configured implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
      return notBlank(ctx.getEnvironment().getProperty("nametracker.finder.url"))
          || notBlank(ctx.getEnvironment().getProperty("nametracker.finder.dictionary"));
    }
  }

  @Bean
  @Conditional(Configured.class)
  NameFinder nameFinder(@Value("${nametracker.finder.url:}") String url,
      @Value("${nametracker.finder.dictionary:}") String dictionary,
      @Value("${nametracker.finder.gnfinder-url:}") String gnfinderUrl,
      @Value("${nametracker.finder.config:}") String configFile) {
    if (notBlank(url)) return new RemoteNameFinder(URI.create(url.strip()));
    String overrides = null;
    if (notBlank(configFile)) {
      Path f = Path.of(configFile.strip());
      try {
        overrides = Files.readString(f, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read FINDER_CONFIG " + f, e);
      }
    }
    return PipelineFactory.create(Path.of(dictionary.strip()), gnfinderUrl, overrides);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
