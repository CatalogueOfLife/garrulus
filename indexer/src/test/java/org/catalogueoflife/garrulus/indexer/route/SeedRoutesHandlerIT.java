package org.catalogueoflife.garrulus.indexer.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SeedRoutesHandlerIT extends AbstractPostgresIT {

  @Autowired SeedRoutesHandler handler;
  @Autowired SourceRouteMapper routes;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
  }

  private long source(String title, String issnL, String issns) {
    return jdbc.queryForObject("INSERT INTO source (issn_l, issns, title, tier, origin, active) "
        + "VALUES (?, ?::text[], ?, 'CORE', 'MANUAL', true) RETURNING id", Long.class, issnL, issns, title);
  }

  private static Task task() {
    return new Task(0, SeedRoutesHandler.TYPE, "{}", TaskPriority.LIVE, TaskState.RUNNING, 1, null, null, null, null,
        null, null);
  }

  @Test
  void seedRoutesMatchesByIssnAndIsIdempotent() throws Exception {
    // ZooKeys registered under its electronic ISSN only, Biodiversity Science (Magtech) under its print ISSN
    long zookeys = source("ZooKeys", "1313-2970", "{1313-2970}");
    long biodivSci = source("Biodiversity Science", "1005-0094", "{1005-0094}");
    long unknown = source("Unrelated", "0000-0000", "{0000-0000}");

    handler.handle(task());

    List<SourceRoute> zk = routes.forSource(zookeys);
    assertThat(zk).hasSize(1);
    SourceRoute r = zk.getFirst();
    assertThat(r.layer()).isEqualTo("OAI");
    assertThat(r.status()).isEqualTo("PENDING");
    assertThat(r.origin()).isEqualTo("SEED");
    assertThat(r.config()).contains("zookeys.pensoft.net").contains("\"zookeys\"");
    List<SourceRoute> bs = routes.forSource(biodivSci);
    assertThat(bs).extracting(SourceRoute::layer).containsExactly("MAGTECH");
    assertThat(bs.getFirst().config()).contains("biodiversity-science.net");
    assertThat(routes.forSource(unknown)).isEmpty();
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'VERIFY_ROUTE' ORDER BY id", String.class))
        .containsExactlyInAnyOrder("verify-route:" + r.id(), "verify-route:" + bs.getFirst().id());

    // a second run adds no route and no second live verification
    handler.handle(task());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_route", Long.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'VERIFY_ROUTE'", Long.class)).isEqualTo(2);
  }

  @Test
  void seedFileIsWellFormed() throws Exception {
    List<SeedRoutesHandler.Entry> entries = SeedRoutesHandler.read();
    assertThat(entries).hasSizeGreaterThan(50);
    assertThat(entries).allSatisfy(e -> {
      assertThat(e.issns()).isNotEmpty().allMatch(i -> i.matches("\\d{4}-\\d{3}[\\dX]"));
      assertThat(e.layer()).isIn("OAI", "MAGTECH", "EUROPEPMC");
      assertThat(e.config()).startsWith("{");
    });
    assertThat(entries).filteredOn(e -> e.layer().equals("OAI")).extracting(SeedRoutesHandler.Entry::config)
        .anyMatch(c -> c.contains("\"zookeys\"")).anyMatch(c -> c.contains("mapress.com/zt/oai"))
        .anyMatch(c -> c.contains("\"jats\""));
  }
}
