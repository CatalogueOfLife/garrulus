package org.catalogueoflife.garrulus.indexer.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(LinkTestConfig.class)
// the built-in lookups (Task 4) are off: these tests drive the runner with scripted lookups only
@org.springframework.test.context.TestPropertySource(properties = {
    "garrulus.links.crossref.enabled=false",
    "garrulus.links.openalex.enabled=false",
    "garrulus.links.europepmc.enabled=false",
    "garrulus.links.biodiversitypmc.enabled=false",
    "garrulus.links.zenodo-blr.enabled=false",
    "garrulus.links.plazi.enabled=false",
    "garrulus.links.wikidata.enabled=false",
    "garrulus.links.ipni.enabled=false",
    "garrulus.links.crossref-journal.enabled=false"})
class LinkSourceHandlerIT extends AbstractPostgresIT {

  @Autowired LinkSourceHandler handler;
  @Autowired ScriptedLookup sourceLookup;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM link_attempt");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
    sourceLookup.reset();
  }

  private long source(String status, String issnL, String openalex) {
    return jdbc.queryForObject("INSERT INTO source (openalex_id, issn_l, issns, title, tier, origin, active, "
        + "publication_status) VALUES (?, ?, ARRAY[?]::text[], 'J', 'CORE', 'SEED_TOPIC', true, ?) RETURNING id",
        Long.class, openalex, issnL, issnL, status);
  }

  private static Task task(String payload) {
    return new Task(1, LinkSourceHandler.TYPE, payload, TaskPriority.BACKFILL, TaskState.RUNNING, 1, null, null, null,
        null, null, null);
  }

  @Test
  void columnsReachLookupsButAreNotMirrored() {
    long s = source("ACTIVE", "1175-5326", "S171471881");
    sourceLookup.answers.put(s, List.of(new Identifier("WIKIDATA", "Q42"), new Identifier("TESTDB", "9")));

    handler.handle(task("{\"sourceId\":" + s + "}"));

    LinkTarget seen = sourceLookup.asked.get(0).get(0);
    assertThat(seen.id()).isEqualTo(s);
    assertThat(seen.issns()).containsExactly("1175-5326");
    assertThat(seen.ids()).containsEntry("ISSN", List.of("1175-5326")).containsEntry("ISSN_L", List.of("1175-5326"))
        .containsEntry("OPENALEX", List.of("S171471881"));
    // only lookup schemes are stored; ISSN, ISSN_L and OPENALEX stay the source's columns
    assertThat(jdbc.queryForList("SELECT scheme || ':' || value || ':' || origin FROM source_identifier "
        + "WHERE source_id = ? ORDER BY 1", String.class, s))
        .containsExactly("TESTDB:9:LINK:testsrc", "WIKIDATA:Q42:LINK:testsrc");
  }

  @Test
  void ceasedSourceSkipped() {
    long s = source("CEASED", "2118-9773", null);
    handler.handle(task("{\"sourceId\":" + s + "}"));
    handler.handle(task("{\"afterId\":" + (s - 1) + "}")); // the newest source: a page of one
    assertThat(sourceLookup.calls()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM link_attempt", Long.class)).isZero();
  }

  @Test
  void missesAreRetriedAndPagesContinue() {
    long s1 = source("ACTIVE", "1111-1119", null);
    long s2 = source("ACTIVE", "2222-2226", null);
    handler.handle(task("{\"afterId\":" + (s1 - 1) + ",\"batch\":1}")); // other sources may exist before s1
    assertThat(sourceLookup.calls()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT payload FROM task WHERE type = 'LINK_SOURCE'", String.class))
        .containsPattern("\"afterId\": ?" + s1 + "\\b");
    assertThat(jdbc.queryForObject("SELECT next_try > now() + interval '29 days' FROM link_attempt "
        + "WHERE subject_id = ?", Boolean.class, s1)).isTrue();
    assertThat(s2).isGreaterThan(s1);
  }

  @Test
  void columnSchemesAreNeverStoredForASource() {
    long s = source("ACTIVE", "3333-3330", "S5");
    sourceLookup.answers.put(s, List.of(new Identifier("ISSN", "3333-3330"), new Identifier("ISSN_L", "3333-3330"),
        new Identifier("OPENALEX", "S5"), new Identifier("WIKIDATA", "Q7")));
    handler.handle(task("{\"sourceId\":" + s + "}"));
    assertThat(jdbc.queryForList("SELECT scheme FROM source_identifier WHERE source_id = ?", String.class, s))
        .containsExactly("WIKIDATA");
  }
}
