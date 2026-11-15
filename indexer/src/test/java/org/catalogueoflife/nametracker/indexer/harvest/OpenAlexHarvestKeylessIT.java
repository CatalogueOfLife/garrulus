package org.catalogueoflife.nametracker.indexer.harvest;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.harvest.HarvestRun;
import org.catalogueoflife.nametracker.store.harvest.HarvestRunMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Without an API key OpenAlex pages by number and stops at 10,000 results; the run says so. */
@WireMockTest(httpPort = 18999)
class OpenAlexHarvestKeylessIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.openalex.base-url", () -> "http://localhost:18999");
    r.add("nametracker.openalex.api-key", () -> "");
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @Autowired OpenAlexHarvestHandler harvest;
  @Autowired TaskQueue queue;
  @Autowired HarvestRunMapper runs;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM harvest_run");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
  }

  @Test
  void cappedBatchIsRecordedOnTheRun() throws Exception {
    jdbc.update("INSERT INTO source (openalex_id, title, tier, origin, onboarded_at) "
        + "VALUES ('S805', 'J', 'CORE', 'MANUAL', now())");
    StringBuilder body = new StringBuilder("{\"meta\":{\"count\":50000},\"results\":[");
    for (int i = 0; i < 200; i++) {
      body.append(i > 0 ? "," : "").append("{\"id\":\"https://openalex.org/W").append(i)
          .append("\",\"doi\":\"https://doi.org/10.1/w").append(i).append("\",\"title\":\"T\"}");
    }
    stubFor(get(urlPathEqualTo("/works")).willReturn(okJson(body.append("]}").toString())));

    // the 50th page is the 10,000th result
    queue.enqueue("HARVEST_OPENALEX", "{\"window\":\"LIVE\",\"page\":50}", TaskPriority.LIVE, null);
    Task t = queue.claim(Set.of("HARVEST_OPENALEX"), "test", 1).getFirst();
    harvest.handle(t);
    queue.complete(t.id(), t.attempts());

    verify(getRequestedFor(urlPathEqualTo("/works")).withQueryParam("sort", equalTo("publication_date:desc")));
    HarvestRun run = runs.recent(1).getFirst();
    assertThat(run.state()).isEqualTo("DONE");
    assertThat(run.error()).isEqualTo("truncated at 10000 results (no API key)");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work", Long.class)).isEqualTo(200);
  }
}
