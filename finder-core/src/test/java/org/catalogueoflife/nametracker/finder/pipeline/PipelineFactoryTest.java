package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineFactoryTest {
  private static Path dictionary(Path dir) throws Exception {
    String tsv = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
        + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n";
    Path file = dir.resolve("d.bin");
    ColDictionaryIO.write(ColDictionaryBuilder.fromNameUsageTsv(new StringReader(tsv), "k1", CommonWords.of(Set.of())), file);
    return file;
  }

  @Test
  void buildsThePipelineWithDefaultsOrOverrides(@TempDir Path dir) throws Exception {
    Path d = dictionary(dir);
    PipelineNameFinder f = PipelineFactory.create(d, null, null);
    assertThat(f.info().dictionaryKey()).isEqualTo("k1");
    assertThat(f.config()).isEqualTo(PipelineConfig.defaults());
    assertThat(PipelineFactory.create(d, " ", " ").config()).isEqualTo(PipelineConfig.defaults());

    PipelineNameFinder tuned = PipelineFactory.create(d, "http://localhost:1", "{\"judgeThreshold\":0.7}");
    assertThat(tuned.config().judgeThreshold()).isEqualTo(0.7);
    assertThat(tuned.info().configHash()).isNotEqualTo(f.info().configHash());
  }

  @Test
  void aMissingDictionaryNamesTheFileAndHowToBuildIt(@TempDir Path dir) {
    Path missing = dir.resolve("nope.bin");
    assertThatThrownBy(() -> PipelineFactory.create(missing, null, null)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missing.toString()).hasMessageContaining("does not exist").hasMessageContaining("build-dictionary");
  }

  @Test
  void anOldDictionaryFormatIsAnIoProblem(@TempDir Path dir) throws Exception {
    Path old = dir.resolve("old.bin");
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(old))) {
      out.writeInt(0x434F4C44);
      out.writeInt(1);
    }
    assertThatThrownBy(() -> PipelineFactory.readDictionary(old)).isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("format version 1");
  }

  @Test
  void badOverridesAndUrlsAreRejected(@TempDir Path dir) throws Exception {
    Path d = dictionary(dir);
    assertThatThrownBy(() -> PipelineFactory.create(d, null, "{\"nope\":1}")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PipelineFactory.create(d, "localhost:8778", null)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("gnfinder URL");
  }
}
