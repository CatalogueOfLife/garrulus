package org.catalogueoflife.garrulus.indexer.finder;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.gnfinder.GnfinderException;
import org.catalogueoflife.garrulus.finder.gnfinder.GnfinderUnavailableException;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.task.ClearTasksAtStartup;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "garrulus.indexer.workers=4")
@Import({ClearTasksAtStartup.class, FindNamesOutageIT.DownConfig.class})
class FindNamesOutageIT extends AbstractPostgresIT {

  @TestConfiguration
  static class DownConfig {
    @Bean
    NameFinder downFinder() {
      return new NameFinder() {
        @Override public FinderInfo info() {
          return new FinderInfo("down", "1", "c", null);
        }

        @Override public FinderResult find(Document doc, FinderOptions opts) {
          if (doc.blocks().stream().anyMatch(b -> b.text().contains("Broken"))) {
            throw new GnfinderException("gnfinder at x returned HTTP 500");
          }
          throw new GnfinderUnavailableException("connection refused");
        }
      };
    }
  }

  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @Test
  void gnfinderOutageDefersWithoutAttempt() {
    long work = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, origin, state, text_level) VALUES "
        + "('10.1/outage', 'T', 'About Homo sapiens.', 'LIVE', 'CONTENT_RESOLVED', 'ABSTRACT_ONLY') RETURNING id",
        Long.class);
    Instant before = Instant.now();
    long id = queue.enqueue("FIND_NAMES", "{\"workId\":" + work + "}", TaskPriority.LIVE, "names:outage");

    await().atMost(20, SECONDS).untilAsserted(() -> {
      Map<String, Object> t = jdbc.queryForMap("SELECT state, attempts, run_after FROM task WHERE id = ?", id);
      assertThat(t.get("state")).isEqualTo("QUEUED");
      assertThat((Integer) t.get("attempts")).isZero();
      Instant at = ((Timestamp) t.get("run_after")).toInstant();
      assertThat(Duration.between(before, at)).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
    });
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, work))
        .isEqualTo("CONTENT_RESOLVED");
  }

  @Test
  void gnfinderFailingOnOneDocumentIsAnOrdinaryFailure() {
    long work = jdbc.queryForObject("INSERT INTO work (doi, title, abstract, origin, state, text_level) VALUES "
        + "('10.1/broken', 'Broken', 'About Homo sapiens.', 'LIVE', 'CONTENT_RESOLVED', 'ABSTRACT_ONLY') RETURNING id",
        Long.class);
    long id = queue.enqueue("FIND_NAMES", "{\"workId\":" + work + "}", TaskPriority.LIVE, "names:broken");

    // it uses an attempt and is retried with backoff, so after max-attempts it ends DEAD where it can be seen
    await().atMost(20, SECONDS).untilAsserted(() -> {
      Map<String, Object> t = jdbc.queryForMap("SELECT state, attempts, last_error FROM task WHERE id = ?", id);
      assertThat((Integer) t.get("attempts")).isEqualTo(1);
      assertThat((String) t.get("last_error")).contains("HTTP 500");
    });
  }
}
