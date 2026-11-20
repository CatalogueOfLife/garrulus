package org.catalogueoflife.nametracker.indexer.harvest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@WireMockTest(httpPort = 18998)
class OpenAlexRecheckHandlerIT extends AbstractPostgresIT {

  private static final String DOI = "10.3897/zookeys.100.1545";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.openalex.base-url", () -> "http://localhost:18998");
    r.add("nametracker.openalex.api-key", () -> "k");
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @Autowired OpenAlexRecheckHandler recheck;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
  }

  private long source() {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin, onboarded_at) "
        + "VALUES ('S199213172', 'ZooKeys', 'CORE', 'MANUAL', now()) RETURNING id", Long.class);
  }

  private long work(long sourceId, String state, String textLevel) {
    return jdbc.queryForObject("INSERT INTO work (doi, source_id, title, abstract, origin, state, text_level, first_seen_at) "
        + "VALUES (?, ?, 'Aus bus', 'An abstract.', 'LIVE', ?, ?, now() - interval '3 days') RETURNING id", Long.class,
        DOI, sourceId, state, textLevel);
  }

  private static String openAlex(boolean tei) {
    return "{\"id\":\"https://openalex.org/W77\",\"doi\":\"https://doi.org/" + DOI + "\",\"title\":\"Aus bus\","
        + "\"publication_date\":\"2011-05-20\",\"primary_location\":{\"source\":{\"id\":\"https://openalex.org/S199213172\"}},"
        + "\"has_content\":{\"grobid_xml\":" + tei + "}}";
  }

  private void run() {
    recheck.handle(new Task(1, "OPENALEX_RECHECK", "{}", TaskPriority.LIVE, null, 1, Instant.now(), null, null, null,
        "openalex-recheck", null));
  }

  private List<Map<String, Object>> contentTasks() {
    return jdbc.queryForList("SELECT payload::text AS payload, dedup_key FROM task WHERE type = 'RESOLVE_CONTENT'");
  }

  /** A work resolved before OpenAlex had TEI is resolved again with all routes (the TEI one is the paid one). */
  @Test
  void teiOnResolvedWorkReresolves() {
    long id = work(source(), "CONTENT_RESOLVED", "ABSTRACT_ONLY");
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI)).willReturn(okJson(openAlex(true))));

    run();

    var tasks = contentTasks();
    assertThat(tasks).hasSize(1);
    assertThat(tasks.getFirst().get("dedup_key")).isEqualTo("content:" + id + ":tei");
    String payload = tasks.getFirst().get("payload").toString().replace(" ", "");
    assertThat(payload).contains("\"workId\":" + id).contains("\"tei\":true").doesNotContain("freeOnly");
    assertThat(jdbc.queryForObject("SELECT openalex_has_tei FROM work WHERE id = ?", Boolean.class, id)).isTrue();
  }

  @Test
  void noTeiTaskWithoutTeiOrWithFullTextOrBeforeTheFirstResolve() {
    long src = source();
    work(src, "CONTENT_RESOLVED", "ABSTRACT_ONLY");
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI)).willReturn(okJson(openAlex(false))));
    run();
    assertThat(contentTasks()).isEmpty(); // OpenAlex has no TEI

    jdbc.update("DELETE FROM work");
    work(src, "INDEXED", "FULLTEXT");
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI)).willReturn(okJson(openAlex(true))));
    run();
    assertThat(contentTasks()).isEmpty(); // the work has its full text already

    jdbc.update("DELETE FROM work");
    work(src, "DISCOVERED", null);
    run();
    assertThat(contentTasks().stream().filter(t -> t.get("dedup_key").toString().endsWith(":tei"))).isEmpty(); // first resolve follows
  }

  @Test
  void aTeiThatWasThereBeforeIsNotRequeued() {
    long id = work(source(), "CONTENT_RESOLVED", "ABSTRACT_ONLY");
    jdbc.update("UPDATE work SET openalex_has_tei = true WHERE id = ?", id);
    stubFor(get(urlPathEqualTo("/works/doi:" + DOI)).willReturn(okJson(openAlex(true))));
    run();
    assertThat(contentTasks()).isEmpty();
  }
}
