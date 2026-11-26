package org.catalogueoflife.garrulus.indexer.registry;

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
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.registry.IssnLinkMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** REFRESH_ISSNL against a WireMock zip built in the test (the real 27 MB file is never downloaded). */
class IssnlRefreshHandlerIT extends AbstractPostgresIT {

  static final WireMockServer ISSN = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    ISSN.start();
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("garrulus.discovery.issnl.enabled", () -> "true");
    r.add("garrulus.discovery.issnl.url", () -> ISSN.baseUrl() + "/issnltables.zip");
  }

  @AfterAll
  static void stop() {
    ISSN.stop();
  }

  @Autowired IssnlRefreshHandler handler;
  @Autowired IssnLinkMapper links;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM issn_link");
    ISSN.resetAll();
  }

  static byte[] zip(String entry, String body) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(bos)) {
      z.putNextEntry(new ZipEntry(entry));
      z.write(body.getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
    }
    return bos.toByteArray();
  }

  static void serve(byte[] zip) {
    ISSN.stubFor(get(urlEqualTo("/issnltables.zip")).willReturn(aResponse().withStatus(200).withBody(zip)));
  }

  static Task task() {
    return new Task(1L, "REFRESH_ISSNL", "{}", TaskPriority.LIVE, TaskState.RUNNING, 1, Instant.now(), null, null, null, null,
        Instant.now());
  }

  @Test
  void loadsAndUpdatesLinks() throws Exception {
    String head = "ISSN\tISSN-L\n";
    serve(zip("ISSN_ISSN-L_table.20261118.txt", head + "1175-5326\t1175-5326\n1175-5334\t1175-5326\n0002-936X\t0002-936X\n"
        + "junk line\n"));
    handler.handle(task());
    assertThat(links.issnL("1175-5334")).isEqualTo("1175-5326");
    assertThat(links.siblings("1175-5326")).containsExactly("1175-5326", "1175-5334");
    assertThat(links.issnL("0002-936X")).isEqualTo("0002-936X");

    serve(zip("ISSN_ISSN-L_table.20261119.txt", head + "1175-5326\t1175-5326\n1175-5334\t0002-936X\n0002-936X\t0002-936X\n"));
    handler.handle(task());
    assertThat(links.issnL("1175-5334")).isEqualTo("0002-936X");
    assertThat(links.siblings("0002-936X")).containsExactly("0002-936X", "1175-5334");
  }

  @Test
  void zipWithoutTableKeepsLinks() throws Exception {
    serve(zip("ISSN_ISSN-L_table.20261118.txt", "ISSN\tISSN-L\n1175-5326\t1175-5326\n"));
    handler.handle(task());
    serve(zip("README.txt", "nothing here"));
    handler.handle(task());
    assertThat(links.issnL("1175-5326")).isEqualTo("1175-5326");
  }

  @Test
  void downloadFailureIsOutage() {
    ISSN.stubFor(get(urlEqualTo("/issnltables.zip")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> handler.handle(task())).isInstanceOf(ServiceOutageException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM issn_link", Long.class)).isZero();
  }

  @Test
  void truncatedZipFailsAndKeepsLinks() throws Exception {
    serve(zip("ISSN_ISSN-L_table.20261118.txt", "ISSN\tISSN-L\n1175-5326\t1175-5326\n"));
    handler.handle(task());
    StringBuilder big = new StringBuilder("ISSN\tISSN-L\n0002-936X\t0002-936X\n");
    java.util.Random rnd = new java.util.Random(7);
    for (int i = 0; i < 40000; i++) big.append(String.format("%04d-%04d\t%04d-%04d\n", rnd.nextInt(10000),
        rnd.nextInt(10000), rnd.nextInt(10000), rnd.nextInt(10000))); // mostly invalid check digits: filtered, but bulky
    byte[] full = zip("ISSN_ISSN-L_table.20261119.txt", big.toString());
    serve(java.util.Arrays.copyOf(full, full.length * 6 / 10));
    assertThatThrownBy(() -> handler.handle(task())).isInstanceOfAny(ServiceOutageException.class, IOException.class);
    assertThat(links.issnL("0002-936X")).isNull();
    assertThat(links.issnL("1175-5326")).isEqualTo("1175-5326");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM issn_link", Long.class)).isEqualTo(1);
  }

  @Test
  void corruptAndDuplicateRowsAreSkippedOrDeduplicated() throws Exception {
    serve(zip("ISSN_ISSN-L_table.20261118.txt", "ISSN\tISSN-L\n1175-5326\t1175-5326\n1175-5326\t1175-5326\n"
        + "1175-5334\t1175-5326\n1175-5334\t1175-5326\nXXXX\t\n\t\t\n1175-5327\t1175-5326\n"));
    handler.handle(task());
    assertThat(links.siblings("1175-5326")).containsExactly("1175-5326", "1175-5334");
  }
}
