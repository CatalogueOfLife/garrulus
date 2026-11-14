package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderClient;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderOnlyFinder;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineNameFinder;
import org.gbif.nameparser.rust.NameParserRust;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds a finder from its name: italics, gnfinder and pipeline.
 *
 * <ul>
 *   <li>gnfinder and pipeline read GNFINDER_URL (default http://localhost:8778).</li>
 *   <li>pipeline needs the COL dictionary file built by {@code eval build-dictionary}: the given path, else the environment
 *       variable FINDER_DICTIONARY. It runs with the defaults of {@link PipelineConfig}.</li>
 * </ul>
 */
public final class FinderFactory {
  static final String DICTIONARY_ENV = "FINDER_DICTIONARY";
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
    return switch (name) {
      case "italics" -> new ItalicsFinder();
      case "gnfinder" -> new GnfinderOnlyFinder(gnfinder(env));
      case "pipeline" -> new PipelineNameFinder(PipelineConfig.defaults(), gnfinder(env), dictionary(dictionary, env),
          new NameParserRust());
      default -> throw new IllegalArgumentException("Unknown finder '" + name + "'. Available finders: " + String.join(", ", available()));
    };
  }

  private static GnfinderClient gnfinder(UnaryOperator<String> env) {
    String url = env.apply(GNFINDER_ENV);
    return new GnfinderClient(URI.create(url == null || url.isBlank() ? "http://localhost:8778" : url));
  }

  private static ColDictionary dictionary(@Nullable Path given, UnaryOperator<String> env) {
    Path file = given;
    if (file == null) {
      String v = env.apply(DICTIONARY_ENV);
      if (v != null && !v.isBlank()) file = Path.of(v);
    }
    if (file == null) {
      throw new IllegalArgumentException("The pipeline finder needs the COL dictionary: pass --dictionary <file> or set "
          + DICTIONARY_ENV + " (build one with: eval build-dictionary --xr <XR zip> --out eval/work)");
    }
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("COL dictionary file " + file + " does not exist (build one with: eval build-dictionary)");
    }
    try {
      return ColDictionaryIO.read(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read COL dictionary " + file + ": " + e.getMessage(), e);
    }
  }
}
