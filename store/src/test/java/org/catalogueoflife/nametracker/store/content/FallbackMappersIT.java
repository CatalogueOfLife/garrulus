package org.catalogueoflife.nametracker.store.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FallbackMappersIT extends AbstractPostgresIT {

  @Autowired TextLookupMapper lookups;
  @Autowired InstitutionalFetchMapper fetches;
  @Autowired SourceMapper sources;
  @Autowired JdbcTemplate jdbc;

  private long work() {
    return jdbc.queryForObject("INSERT INTO work (doi, origin) VALUES (?, 'LIVE') RETURNING id", Long.class,
        "10.21/fb" + System.nanoTime());
  }

  @Test
  void textLookupTriedSince() {
    long w = work();
    Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
    assertThat(lookups.triedSince(w, "SPRINGER", hourAgo)).isFalse();
    lookups.record(w, "SPRINGER", false);
    assertThat(lookups.triedSince(w, "SPRINGER", hourAgo)).isTrue();
    assertThat(lookups.triedSince(w, "SPRINGER", Instant.now().plus(Duration.ofHours(1)))).isFalse();
    assertThat(lookups.triedSince(w, "SCIELO", hourAgo)).isFalse();
    lookups.record(w, "SPRINGER", true); // replaces
    assertThat(jdbc.queryForObject("SELECT found FROM text_lookup WHERE work_id = ?", Boolean.class, w)).isTrue();
  }

  @Test
  void institutionalFetchIsAuditedAndSurvivesItsWork() {
    long w = work();
    long id = fetches.insert(w, "10.1/x", "elsevier", "ELS", 200, 1234, "OK");
    assertThat(id).isPositive();
    fetches.insert(null, "10.1/y", "elsevier", "ELS", 404, 0, "NOT_FOUND");
    fetches.insert(null, "10.1/z", "wiley", "TDM", 200, 5, "OK");
    Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
    assertThat(fetches.countSince("elsevier", hourAgo)).isEqualTo(2);
    assertThat(fetches.countSince("elsevier", Instant.now().plus(Duration.ofHours(1)))).isZero();
    jdbc.update("DELETE FROM work WHERE id = ?", w);
    assertThat(jdbc.queryForObject("SELECT work_id FROM institutional_fetch WHERE id = ?", Long.class, id)).isNull();
  }

  @Test
  void newContentRoutesAndAbstractSourcesAreAllowed() {
    long w = work();
    jdbc.update("INSERT INTO content (work_id, route, kind, retained) VALUES (?, 'OAI_JATS', 'JATS', true)", w);
    jdbc.update("INSERT INTO content (work_id, route, kind, retained) VALUES (?, 'INSTITUTIONAL', 'JATS', false)", w);
    for (String s : new String[] {"SPRINGER", "SCIELO", "CINII", "INSTITUTIONAL"}) {
      jdbc.update("UPDATE work SET abstract_source = ? WHERE id = ?", s, w);
    }
  }

  @Test
  void sourceLanguagesRoundTrip() {
    long s = jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('lang', 'MANUAL') RETURNING id",
        Long.class);
    assertThat(sources.languages(s)).isEmpty();
    assertThat(sources.setLanguages(s, java.util.List.of("pt", "en"))).isEqualTo(1);
    assertThat(sources.setLanguages(s, java.util.List.of("pt", "en"))).isZero();
    assertThat(sources.languages(s)).containsExactly("pt", "en");
  }
}
