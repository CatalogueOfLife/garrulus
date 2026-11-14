package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderOnlyFinder;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineFactory;
import org.catalogueoflife.nametracker.finder.remote.RemoteNameFinder;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds a finder from its name: italics, gnfinder and pipeline.
 *
 * <ul>
 *   <li>gnfinder and pipeline read GNFINDER_URL (default http://localhost:8778).</li>
 *   <li>{@code remote:<url>} calls a finder service (finder-service, or any implementation of finder-api.yaml), e.g.
 *       {@code remote:http://localhost:8095}.</li>
 *   <li>pipeline needs the COL dictionary file built by {@code eval build-dictionary}: the given path, else the environment
 *       variable FINDER_DICTIONARY. It runs with the defaults of {@link PipelineConfig} and is assembled by
 *       {@link PipelineFactory}, as in the finder service.</li>
 * </ul>
 */
public final class FinderFactory {
  static final String DICTIONARY_ENV = "FINDER_DICTIONARY";
  static final String REMOTE_PREFIX = "remote:";
  static final String GNFINDER_ENV = "GNFINDER_URL";
  private static final List<String> NAMES = List.of("gnfinder", "italics", "pipeline");

  private FinderFactory() {}

  public static List<String> available() {
    return NAMES;
  }

  /** A finder; pipeline takes its dictionary from FINDER_DICTIONARY. */
  public static NameFinder create(String name) {
    return create(name, null);
  }

  /** @param dictionary the dictionary file for pipeline, null for FINDER_DICTIONARY; ignored by the other finders */
  public static NameFinder create(String name, @Nullable Path dictionary) {
    return create(name, dictionary, System::getenv);
  }

  static NameFinder create(String name, @Nullable Path dictionary, UnaryOperator<String> env) {
    if (name.startsWith(REMOTE_PREFIX)) return remote(name.substring(REMOTE_PREFIX.length()));
    return switch (name) {
      case "italics" -> new ItalicsFinder();
      case "gnfinder" -> new GnfinderOnlyFinder(PipelineFactory.gnfinder(env.apply(GNFINDER_ENV)));
      case "pipeline" -> PipelineFactory.create(dictionaryFile(dictionary, env), env.apply(GNFINDER_ENV), null);
      default -> throw new IllegalArgumentException("Unknown finder '" + name + "'. Available finders: " + String.join(", ", available())
          + ", " + REMOTE_PREFIX + "<url>");
    };
  }

  private static NameFinder remote(String url) {
    URI u;
    try {
      u = URI.create(url.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid finder URL '" + url + "' in " + REMOTE_PREFIX + "<url>: " + e.getMessage(), e);
    }
    if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https")) || u.getHost() == null) {
      throw new IllegalArgumentException("The finder " + REMOTE_PREFIX + "<url> needs an http(s) URL, e.g. " + REMOTE_PREFIX + "http://localhost:8095, not '" + url + "'");
    }
    return new RemoteNameFinder(u);
  }

  /** The dictionary file: the given one, else FINDER_DICTIONARY. */
  static Path dictionaryFile(@Nullable Path given, UnaryOperator<String> env) {
    Path file = given;
    if (file == null) {
      String v = env.apply(DICTIONARY_ENV);
      if (v != null && !v.isBlank()) file = Path.of(v);
    }
    if (file == null) {
      throw new IllegalArgumentException("The pipeline finder needs the COL dictionary: pass --dictionary <file> or set "
          + DICTIONARY_ENV + " (build one with: eval build-dictionary --xr <XR zip> --out eval/work)");
    }
    return file;
  }
}
