package org.catalogueoflife.nametracker.store.ident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SourceIdentifierMapperIT extends AbstractPostgresIT {

  @Autowired SourceIdentifierMapper identifiers;
  @Autowired LinkAttemptMapper attempts;
  @Autowired JdbcTemplate jdbc;

  private long source(String title) {
    return jdbc.queryForObject("INSERT INTO source (title, origin) VALUES (?, 'MANUAL') RETURNING id", Long.class,
        title + System.nanoTime());
  }

  @Test
  void insertAllIsIdempotentAndASchemeValueBelongsToOneSource() {
    long a = source("a");
    long b = source("b");
    List<Identifier> ids = List.of(new Identifier("WIKIDATA", "Q1"), new Identifier("DOAJ", "d1"));
    assertThat(identifiers.insertAll(a, ids, "WIKIDATA")).isEqualTo(2);
    assertThat(identifiers.insertAll(a, ids, "WIKIDATA")).isZero();
    assertThat(identifiers.insertAll(b, List.of(new Identifier("WIKIDATA", "Q1")), "WIKIDATA")).isZero();
    assertThat(identifiers.list(a)).containsExactlyInAnyOrderElementsOf(ids);
    assertThat(identifiers.findSource("WIKIDATA", "Q1")).isEqualTo(a);
    assertThat(identifiers.findSource("WIKIDATA", "none")).isNull();
  }

  @Test
  void moveAllMovesLookupRowsAndDropsDuplicates() {
    long from = source("from");
    long to = source("to");
    identifiers.insertAll(from, List.of(new Identifier("BPH", "b1"), new Identifier("DOAJ", "d9")), "T");
    identifiers.insertAll(to, List.of(new Identifier("BHL_TITLE", "t1")), "T");
    assertThat(identifiers.moveAll(from, to)).isEqualTo(2);
    assertThat(identifiers.list(to)).extracting(Identifier::scheme).containsExactly("BHL_TITLE", "BPH", "DOAJ");
    assertThat(identifiers.list(from)).isEmpty();
  }

  @Test
  void deletingASourceDropsItsIdentifiers() {
    long a = source("gone");
    identifiers.insertAll(a, List.of(new Identifier("IPNI_PUB", "p1")), "T");
    jdbc.update("DELETE FROM source WHERE id = ?", a);
    assertThat(identifiers.findSource("IPNI_PUB", "p1")).isNull();
  }

  @Test
  void linkAttemptCountsTriesAndFindsDueRows() {
    long s = source("att");
    assertThat(attempts.get('S', s, "wikidata")).isNull();
    Instant retry = Instant.now().minus(Duration.ofMinutes(1));
    attempts.record('S', s, "wikidata", false, retry);
    attempts.record('S', s, "wikidata", false, retry);
    LinkAttempt a = attempts.get('S', s, "wikidata");
    assertThat(a.attempts()).isEqualTo(2);
    assertThat(a.found()).isFalse();
    assertThat(a.entity()).isEqualTo('S');
    assertThat(a.nextTry()).isNotNull();
    assertThat(attempts.due('S', Instant.now(), 10)).extracting(LinkAttempt::subjectId).contains(s);
    assertThat(attempts.due('W', Instant.now(), 10)).extracting(LinkAttempt::subjectId).doesNotContain(s);
    assertThat(attempts.dueIds('S', Instant.now(), 0, 10, List.of("wikidata"))).contains(s);
    attempts.record('S', s, "wikidata", true, null);
    a = attempts.get('S', s, "wikidata");
    assertThat(a.attempts()).isEqualTo(3);
    assertThat(a.found()).isTrue();
    assertThat(a.nextTry()).isNull();
    assertThat(attempts.dueIds('S', Instant.now(), 0, 10, List.of("wikidata"))).doesNotContain(s);
  }

  @Test
  void deleteOrphansRemovesRowsOfDeletedSubjectsOnly() {
    long s = source("orph");
    long w = jdbc.queryForObject("INSERT INTO work (doi, origin) VALUES (?, 'LIVE') RETURNING id", Long.class,
        "10.21/orph" + System.nanoTime());
    attempts.record('S', s, "x", false, null);
    attempts.record('W', w, "x", false, null);
    attempts.record('S', Long.MAX_VALUE - 1, "x", false, null);
    attempts.record('W', Long.MAX_VALUE - 1, "x", false, null);
    assertThat(attempts.deleteOrphans('S')).isEqualTo(1);
    assertThat(attempts.deleteOrphans('W')).isEqualTo(1);
    assertThat(attempts.get('S', s, "x")).isNotNull();
    assertThat(attempts.get('W', w, "x")).isNotNull();
  }
}
