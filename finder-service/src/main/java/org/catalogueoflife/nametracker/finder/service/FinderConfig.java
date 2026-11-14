package org.catalogueoflife.nametracker.finder.service;

import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderClient;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineNameFinder;
import org.gbif.nameparser.rust.NameParserRust;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Assembles the pipeline finder. Environment: FINDER_DICTIONARY (required), GNFINDER_URL, FINDER_CONFIG (optional file
 * with a JSON object of overrides, see {@link PipelineConfig}). Any problem fails startup with a message that says what to fix.
 */
@Configuration
public class FinderConfig {
  static final String NO_DICTIONARY = "FINDER_DICTIONARY must point to a dictionary built by `eval build-dictionary`";

  @Bean
  NameFinder nameFinder(@Value("${finder.dictionary:}") String dictionary,
                        @Value("${finder.gnfinder-url:http://localhost:8778}") String gnfinderUrl,
                        @Value("${finder.config:}") String configFile) throws IOException {
    if (dictionary == null || dictionary.isBlank()) throw new IllegalStateException(NO_DICTIONARY);
    Path dict = Path.of(dictionary);
    if (!Files.isRegularFile(dict)) {
      throw new IllegalStateException("FINDER_DICTIONARY " + dict + " does not exist. " + NO_DICTIONARY);
    }
    ColDictionary d = ColDictionaryIO.read(dict); // an old format fails here with the rebuild hint

    PipelineConfig cfg = PipelineConfig.defaults();
    if (configFile != null && !configFile.isBlank()) {
      Path f = Path.of(configFile);
      if (!Files.isRegularFile(f)) throw new IllegalStateException("FINDER_CONFIG " + f + " does not exist");
      cfg = cfg.withOverrides(Files.readString(f, StandardCharsets.UTF_8));
    }
    String url = gnfinderUrl == null || gnfinderUrl.isBlank() ? "http://localhost:8778" : gnfinderUrl;
    return new PipelineNameFinder(cfg, new GnfinderClient(URI.create(url)), d, new NameParserRust());
  }
}
