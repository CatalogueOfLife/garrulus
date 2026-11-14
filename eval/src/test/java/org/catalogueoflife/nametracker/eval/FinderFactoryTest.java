package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinderFactoryTest {
  private static final String TSV = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
      + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n";

  private static Path dictionary(Path dir, String key) throws IOException {
    ColDictionary d = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(TSV), key, CommonWords.of(Set.of()));
    Path file = dir.resolve("dictionary-" + key + ".bin");
    ColDictionaryIO.write(d, file);
    return file;
  }

  @Test
  void knowsAllFinders() {
    assertThat(FinderFactory.available()).containsExactly("gnfinder", "italics", "pipeline");
    assertThat(FinderFactory.create("italics").info().name()).isEqualTo("italics");
    assertThatThrownBy(() -> FinderFactory.create("nope")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gnfinder, italics, pipeline");
  }

  @Test
  void pipelineWithoutDictionaryFailsClearly() {
    assertThatThrownBy(() -> FinderFactory.create("pipeline", null, k -> null)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--dictionary").hasMessageContaining("FINDER_DICTIONARY").hasMessageContaining("build-dictionary");
  }

  @Test
  void pipelineWithMissingDictionaryFileFailsClearly(@TempDir Path dir) {
    Path missing = dir.resolve("dictionary-0.bin");
    assertThatThrownBy(() -> FinderFactory.create("pipeline", missing, k -> null)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing.toString()).hasMessageContaining("does not exist");
  }

  @Test
  void pipelineUsesTheGivenDictionaryElseTheEnvironment(@TempDir Path dir) throws IOException {
    Path fromEnv = dictionary(dir, "env1");
    Path given = dictionary(dir, "arg2");
    Map<String, String> env = Map.of("FINDER_DICTIONARY", fromEnv.toString(), "GNFINDER_URL", "http://localhost:1");
    NameFinder f = FinderFactory.create("pipeline", null, env::get);
    assertThat(f.info().name()).isEqualTo("pipeline");
    assertThat(f.info().dictionaryKey()).isEqualTo("env1");
    assertThat(f.info().configHash()).isEqualTo(PipelineConfig.defaults().configHash());
    assertThat(FinderFactory.create("pipeline", given, env::get).info().dictionaryKey()).isEqualTo("arg2");
  }
}
