package org.catalogueoflife.nametracker.indexer.finder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** BUILD_DICTIONARY against a WireMock XR zip. */
@Import(BuildDictionaryIT.StubConfig.class)
class BuildDictionaryIT extends AbstractPostgresIT {

  static final WireMockServer ZIP = new WireMockServer(WireMockConfiguration.options().dynamicPort());
  static final Path DIR;

  static {
    ZIP.start();
    try {
      DIR = Files.createTempDirectory("garrulus-dict");
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.finder.dictionary-dir", DIR::toString);
    r.add("nametracker.finder.xr-zip-url", () -> ZIP.baseUrl() + "/xr.zip");
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    @Primary
    ReloadableNameFinder reloadable() {
      return new ReloadableNameFinder(new NameFinder() {
        @Override
        public FinderInfo info() {
          return new FinderInfo("stub", "1", "cfg", "0");
        }

        @Override
        public FinderResult find(Document doc, FinderOptions opts) {
          throw new UnsupportedOperationException();
        }
      });
    }
  }

  @Autowired BuildDictionaryHandler handler;
  @Autowired ReloadableNameFinder finder;
  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;
  @Autowired HarvestOutage outage;

  @BeforeEach
  void reset() throws IOException {
    ZIP.resetAll();
    jdbc.update("DELETE FROM xr_release");
    index.addRelease(200, "xr200", true);
    finder.swap(new ReloadableNameFinderTestStub().finder());
    try (var s = Files.list(DIR)) {
      for (Path p : s.toList()) Files.delete(p);
    }
  }

  /** Resets the delegate to a stub with dictionary key "0". */
  static class ReloadableNameFinderTestStub {
    NameFinder finder() {
      return new NameFinder() {
        @Override
        public FinderInfo info() {
          return new FinderInfo("stub", "1", "cfg", "0");
        }

        @Override
        public FinderResult find(Document doc, FinderOptions opts) {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  static byte[] zip(int release) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(bos)) {
      z.putNextEntry(new ZipEntry("NameUsage.tsv"));
      z.write(("col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
          + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n").getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
      z.putNextEntry(new ZipEntry("metadata.yaml"));
      z.write(("title: x\nkey: " + release + "\nversion: v\n").getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
    }
    return bos.toByteArray();
  }

  private static Task task(int key) {
    return new Task(1, "BUILD_DICTIONARY", "{\"key\":" + key + "}", TaskPriority.REPROCESS, null, 1, Instant.now(),
        null, null, null, null, null);
  }

  @Test
  void buildsAndSwaps() throws Exception {
    ZIP.stubFor(get(urlEqualTo("/xr.zip")).willReturn(aResponse().withBody(zip(200))));
    handler.handle(task(200));
    assertThat(DIR.resolve("dictionary-200.bin")).isRegularFile();
    assertThat(finder.info().dictionaryKey()).isEqualTo("200");
    try (var s = Files.list(DIR)) {
      assertThat(s.map(p -> p.getFileName().toString())).containsExactly("dictionary-200.bin");
    }
  }

  @Test
  void existingFileSwapsWithoutDownload() throws Exception {
    ZIP.stubFor(get(urlEqualTo("/xr.zip")).willReturn(aResponse().withBody(zip(200))));
    handler.handle(task(200));
    finder.swap(new ReloadableNameFinderTestStub().finder());
    ZIP.resetRequests();
    handler.handle(task(200));
    assertThat(ZIP.getAllServeEvents()).isEmpty();
    assertThat(finder.info().dictionaryKey()).isEqualTo("200");
  }

  @Test
  void wrongReleaseKeepsCurrent() throws Exception {
    ZIP.stubFor(get(urlEqualTo("/xr.zip")).willReturn(aResponse().withBody(zip(150))));
    handler.handle(task(200));
    assertThat(DIR.resolve("dictionary-200.bin")).doesNotExist();
    assertThat(finder.info().dictionaryKey()).isEqualTo("0");
  }

  @Test
  void notCurrentBuildsButDoesNotSwap() throws Exception {
    ZIP.stubFor(get(urlEqualTo("/xr.zip")).willReturn(aResponse().withBody(zip(200))));
    jdbc.update("UPDATE xr_release SET current = false");
    handler.handle(task(200));
    assertThat(DIR.resolve("dictionary-200.bin")).isRegularFile();
    assertThat(finder.info().dictionaryKey()).isEqualTo("0");
  }

  @Test
  void serverErrorIsAnOutageAndClientErrorIsDone() throws Exception {
    ZIP.stubFor(get(urlEqualTo("/xr.zip")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> handler.handle(task(200))).isInstanceOf(ServiceOutageException.class);
    ZIP.stubFor(get(urlEqualTo("/xr.zip")).willReturn(aResponse().withStatus(404)));
    handler.handle(task(200));
    assertThat(DIR.resolve("dictionary-200.bin")).doesNotExist();
  }

  @Test
  void noDirIsNoop() throws Exception {
    ObjectProvider<NameFinder> provider = new ObjectProvider<>() {
      @Override
      public NameFinder getObject() {
        return finder;
      }

      @Override
      public NameFinder getIfAvailable() {
        return finder;
      }
    };
    var h = new BuildDictionaryHandler(provider, index, outage, "", ZIP.baseUrl() + "/xr.zip", "", "");
    h.handle(task(200));
    assertThat(ZIP.getAllServeEvents()).isEmpty();
    assertThat(finder.info().dictionaryKey()).isEqualTo("0");
  }
}
