package org.catalogueoflife.nametracker.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Startup behaviour of the real application. Command-line args win over any FINDER_DICTIONARY in the environment. */
class FinderConfigTest {

  private static ConfigurableApplicationContext run(String... args) {
    String[] all = new String[args.length + 2];
    all[0] = "--server.port=0";
    all[1] = "--spring.main.banner-mode=off";
    System.arraycopy(args, 0, all, 2, args.length);
    return new SpringApplicationBuilder(FinderServiceApplication.class).web(WebApplicationType.SERVLET).run(all);
  }

  private static Throwable root(Throwable t) {
    while (t.getCause() != null) t = t.getCause();
    return t;
  }

  @Test
  void aMissingDictionaryFailsStartup() {
    assertThatThrownBy(() -> run("--finder.dictionary="))
        .satisfies(e -> assertThat(root(e)).hasMessage("FINDER_DICTIONARY must point to a dictionary built by `eval build-dictionary`"));
  }

  @Test
  void aDictionaryFileThatDoesNotExistFailsStartup(@TempDir Path dir) {
    assertThatThrownBy(() -> run("--finder.dictionary=" + dir.resolve("nope.bin")))
        .satisfies(e -> assertThat(root(e)).hasMessageContaining("nope.bin"));
  }

  @Test
  void anOldFormatDictionaryFailsStartupWithTheDictionaryMessage(@TempDir Path dir) throws Exception {
    Path old = dir.resolve("old.bin");
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(old))) {
      out.writeInt(0x434F4C44);
      out.writeInt(1);
    }
    assertThatThrownBy(() -> run("--finder.dictionary=" + old))
        .satisfies(e -> assertThat(root(e)).hasMessageContaining("format version 1").hasMessageContaining("build-dictionary"));
  }

  @Test
  void aValidDictionaryStartsTheService(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("d.bin");
    String tsv = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
        + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n";
    ColDictionaryIO.write(ColDictionaryBuilder.fromNameUsageTsv(new java.io.StringReader(tsv), "test-key",
        org.catalogueoflife.nametracker.finder.dict.CommonWords.of(java.util.Set.of())), file);
    try (ConfigurableApplicationContext ctx = run("--finder.dictionary=" + file)) {
      assertThat(ctx.getBean(org.catalogueoflife.nametracker.finder.api.NameFinder.class).info().dictionaryKey()).isEqualTo("test-key");
    }
  }
}
