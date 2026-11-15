package org.catalogueoflife.nametracker.indexer.source;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@WireMockTest(httpPort = 18999)
class SeedSourcesHandlerIT extends AbstractPostgresIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("nametracker.openalex.base-url", () -> "http://localhost:18999");
    r.add("nametracker.http.min-interval.localhost", () -> "0s");
  }

  @Autowired SeedSourcesHandler seed;
  @Autowired AddSourceHandler add;
  @Autowired SourceMapper sources;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM api_usage");
    jdbc.update("DELETE FROM source");
  }

  private static void stubRegistry() {
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.id:T14399"))
        .willReturn(okJson(SourceSeederTest.group("t", "S901", "50"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_topic.subfield.id:1105"))
        .willReturn(okJson(SourceSeederTest.group("b", "S901", "60", "S902", "80"))));
    stubFor(get(urlPathEqualTo("/works")).withQueryParam("filter", containing("primary_location.source.id:S"))
        .willReturn(okJson(SourceSeederTest.group("n", "S901", "100", "S902", "100"))));
    stubFor(get(urlPathEqualTo("/sources/S901"))
        .willReturn(okJson(SourceSeederTest.source("S901", "Taxon Journal", ""))));
    stubFor(get(urlPathEqualTo("/sources/S902"))
        .willReturn(okJson(SourceSeederTest.source("S902", "Broad Journal", ""))));
  }

  private static Task task(String type, String payload) {
    return new Task(1, type, payload, TaskPriority.LIVE, null, 1, null, null, null, null, null, null);
  }

  private long onboardTasks() {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'ONBOARD_SOURCE'", Long.class);
  }

  @Test
  void seedTwiceKeepsAdminTierAndEnqueuesOnboardingOnce(WireMockRuntimeInfo w) throws Exception {
    stubRegistry();
    seed.handle(task("SEED_SOURCES", "{}"));

    Source core = sources.findByOpenalexId("S901");
    Source broad = sources.findByOpenalexId("S902");
    assertThat(core.tier()).isEqualTo("CORE");
    assertThat(broad.tier()).isEqualTo("BROAD");
    assertThat(core.origin()).isEqualTo("SEED_TOPIC");
    assertThat(core.tierSetBy()).isEqualTo("SEED");
    assertThat(onboardTasks()).isEqualTo(2);

    // an admin excludes the broad journal
    sources.setTier(broad.id(), "EXCLUDED", false, "ADMIN");
    seed.handle(task("SEED_SOURCES", "{}"));

    assertThat(sources.findByOpenalexId("S902").tier()).isEqualTo("EXCLUDED");
    assertThat(sources.findByOpenalexId("S902").tierSetBy()).isEqualTo("ADMIN");
    assertThat(sources.findByOpenalexId("S902").active()).isFalse();
    assertThat(sources.findByOpenalexId("S901").tier()).isEqualTo("CORE");
    // the queued onboarding is not duplicated by the second run
    assertThat(onboardTasks()).isEqualTo(2);
    List<String> payloads = jdbc.queryForList(
        "SELECT payload::text FROM task WHERE type = 'ONBOARD_SOURCE' ORDER BY id", String.class);
    assertThat(payloads).hasSize(2).allMatch(p -> p.replace(" ", "").matches("\\{\"sourceId\":\\d+}"));
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'ONBOARD_SOURCE' LIMIT 1", Integer.class))
        .isEqualTo(TaskPriority.BACKFILL.code());
  }

  @Test
  void addSourceSetsAdminTierAndOnboards(WireMockRuntimeInfo w) throws Exception {
    stubFor(get(urlPathEqualTo("/sources/S903"))
        .willReturn(okJson(SourceSeederTest.source("S903", "Added Journal", ""))));

    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S903\",\"tier\":\"CORE\"}"));
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S903\",\"tier\":\"CORE\"}"));

    Source s = sources.findByOpenalexId("S903");
    assertThat(s.origin()).isEqualTo("MANUAL");
    assertThat(s.tier()).isEqualTo("CORE");
    assertThat(s.tierSetBy()).isEqualTo("ADMIN");
    assertThat(s.active()).isTrue();
    assertThat(onboardTasks()).isEqualTo(1);

    stubFor(get(urlPathEqualTo("/sources/S904"))
        .willReturn(okJson(SourceSeederTest.source("S904", "Candidate", ""))));
    add.handle(task("ADD_SOURCE", "{\"openalexId\":\"S904\",\"tier\":\"CANDIDATE\"}"));
    assertThat(sources.findByOpenalexId("S904").tier()).isEqualTo("CANDIDATE");
    assertThat(onboardTasks()).isEqualTo(1);
  }
}
