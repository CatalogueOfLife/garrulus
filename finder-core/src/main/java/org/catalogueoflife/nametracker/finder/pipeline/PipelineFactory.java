package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderClient;
import org.gbif.nameparser.rust.NameParserRust;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the default {@link PipelineNameFinder} from a dictionary file, a gnfinder URL and optional config overrides. The
 * one place that assembles it, used by the eval CLI and the finder service alike.
 */
public final class PipelineFactory {
  /** Where the native gnfinder of a development machine listens ({@code gnfinder -p 8778}). */
  public static final String DEFAULT_GNFINDER_URL = "http://localhost:8778";
  static final String BUILD_HINT = "build one with: eval build-dictionary --xr <XR zip> --out eval/work";

  private PipelineFactory() {}

  /**
   * @param dictionary a dictionary file written by {@code eval build-dictionary}
   * @param gnfinderUrl base URL of gnfinder, {@link #DEFAULT_GNFINDER_URL} if null or blank
   * @param configOverrides a JSON object merged into the defaults (see {@link PipelineConfig}), none if null or blank
   * @throws IllegalArgumentException if the dictionary file is missing, the URL or the overrides are invalid
   * @throws UncheckedIOException if the dictionary cannot be read, e.g. an old format (the message says to rebuild it)
   */
  public static PipelineNameFinder create(Path dictionary, @Nullable String gnfinderUrl, @Nullable String configOverrides) {
    return create(readDictionary(dictionary), gnfinder(gnfinderUrl), config(configOverrides));
  }

  /** The pipeline from parts that are already loaded, e.g. one dictionary shared by several finders. */
  public static PipelineNameFinder create(ColDictionary dictionary, GnfinderClient gnfinder, PipelineConfig cfg) {
    return new PipelineNameFinder(cfg, gnfinder, dictionary, new NameParserRust());
  }

  /** The defaults with {@code overrides} merged in; the defaults alone if it is null or blank. */
  public static PipelineConfig config(@Nullable String overrides) {
    return overrides == null || overrides.isBlank() ? PipelineConfig.defaults() : PipelineConfig.load(overrides);
  }

  /** A client for gnfinder at {@code url}, {@link #DEFAULT_GNFINDER_URL} if null or blank. */
  public static GnfinderClient gnfinder(@Nullable String url) {
    String u = url == null || url.isBlank() ? DEFAULT_GNFINDER_URL : url.strip();
    URI uri;
    try {
      uri = URI.create(u);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid gnfinder URL '" + u + "': " + e.getMessage(), e);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("The gnfinder URL needs an http(s) scheme and a host, e.g. " + DEFAULT_GNFINDER_URL + ", not '" + u + "'");
    }
    return new GnfinderClient(uri);
  }

  /** @throws IllegalArgumentException if the file does not exist */
  public static ColDictionary readDictionary(Path file) {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("COL dictionary file " + file + " does not exist (" + BUILD_HINT + ")");
    }
    try {
      return ColDictionaryIO.read(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read COL dictionary " + file + ": " + e.getMessage(), e);
    }
  }
}
