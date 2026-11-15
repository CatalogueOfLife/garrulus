package org.catalogueoflife.nametracker.indexer.finder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.remote.RemoteNameFinder;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.task.ClearTasksAtStartup;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** A finder service that answers 503 (it cannot reach gnfinder) defers the task, like an in-process outage. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "nametracker.indexer.workers=4")
@Import({ClearTasksAtStartup.class, FindNamesRemoteOutageIT.RemoteConfig.class})
class FindNamesRemoteOutageIT extends AbstractPostgresIT {

  static final WireMockServer SERVICE = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    SERVICE.start();
    SERVICE.stubFor(post("/v1/find").willReturn(aResponse().withStatus(503).withBody("{\"error\":\"gnfinder is not available\"}")));
  }

  @TestConfiguration
  static class RemoteConfig {
    @Bean
    NameFinder remoteFinder() {
      return new RemoteNameFinder(URI.create(SERVICE.baseUrl()));
    }
  }

  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @Test
  void remoteFinderOutageDefersWithoutAttempt() {
    long work = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, origin, state, text_level) VALUES "
        + "('10.1/remote', 'T', 'About Homo sapiens.', 'LIVE', 'CONTENT_RESOLVED', 'ABSTRACT_ONLY') RETURNING id",
        Long.class);
    Instant before = Instant.now();
    long id = queue.enqueue("FIND_NAMES", "{\"workId\":" + work + "}", TaskPriority.LIVE, "names:remote");

    await().atMost(20, SECONDS).untilAsserted(() -> {
      Map<String, Object> t = jdbc.queryForMap("SELECT state, attempts, run_after FROM task WHERE id = ?", id);
      assertThat(t.get("state")).isEqualTo("QUEUED");
      assertThat((Integer) t.get("attempts")).isZero();
      Instant at = ((Timestamp) t.get("run_after")).toInstant();
      assertThat(Duration.between(before, at)).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
    });
  }
}
