package org.catalogueoflife.nametracker.store.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LanguageMappersIT extends AbstractPostgresIT {

  @Autowired WorkTextMapper texts;
  @Autowired LanguageEvidenceMapper evidence;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  private long work(String doi, String title, String language) {
    return jdbc.queryForObject("INSERT INTO work (doi, origin, title, language) VALUES (?, 'LIVE', ?, ?) RETURNING id",
        Long.class, doi, title, language);
  }

  private static WorkText title(long id, String lang, String kind, String text) {
    return new WorkText(id, "TITLE", lang, kind, text, "TEST", "MACHINE".equals(kind) ? "model-1" : null, null);
  }

  private String titleEn(long id) {
    return jdbc.queryForObject("SELECT title_en FROM work WHERE id = ?", String.class, id);
  }

  private String titleEnKind(long id) {
    return jdbc.queryForObject("SELECT title_en_kind FROM work WHERE id = ?", String.class, id);
  }

  @Test
  void legacyEvidenceBackfilled() {
    JdbcTemplate j = new JdbcTemplate(dataSource);
    j.execute("CREATE SCHEMA migcheck21");
    // own connections: V20 creates pg_temp functions that would linger in a pooled session and break a later migration
    String url = POSTGRES.getJdbcUrl();
    String user = POSTGRES.getUsername();
    String password = POSTGRES.getPassword();
    try {
      Flyway.configure().dataSource(url, user, password).schemas("migcheck21").target("20").load().migrate();
      j.execute("INSERT INTO migcheck21.work (doi, origin, language) VALUES ('10.21/uz', 'LIVE', 'uz')");
      j.execute("INSERT INTO migcheck21.work (doi, origin) VALUES ('10.21/none', 'LIVE')");
      Flyway.configure().dataSource(url, user, password).schemas("migcheck21").target("21").load().migrate();
      assertThat(j.queryForList("SELECT w.doi || ' ' || e.kind || ' ' || e.source || ' ' || e.lang || ' ' || e.raw "
          + "FROM migcheck21.work_language_evidence e JOIN migcheck21.work w ON w.id = e.work_id", String.class))
          .containsExactly("10.21/uz LEGACY V21 uz uz");
    } finally {
      j.execute("DROP SCHEMA IF EXISTS migcheck21 CASCADE");
    }
  }

  @Test
  void refreshTitleEnPrefersParallel() {
    long id = work("10.21/par", "Новый вид", "ru");
    texts.upsert(title(id, "en", "MACHINE", "New species (machine)"));
    texts.refreshTitleEn(id);
    assertThat(titleEn(id)).isEqualTo("New species (machine)");
    assertThat(titleEnKind(id)).isEqualTo("MACHINE");
    texts.upsert(title(id, "en", "PARALLEL", "New species"));
    texts.refreshTitleEn(id);
    assertThat(titleEn(id)).isEqualTo("New species");
    assertThat(titleEnKind(id)).isEqualTo("PARALLEL");
    texts.deleteMachine(id, "TITLE");
    jdbc.update("DELETE FROM work_text WHERE work_id = ? AND kind = 'PARALLEL'", id);
    texts.upsert(title(id, "en", "MACHINE", "Again machine"));
    texts.refreshTitleEn(id);
    assertThat(titleEnKind(id)).isEqualTo("MACHINE");
    // deleting the parallel with a machine row present falls back to the machine title
    texts.upsert(title(id, "en", "PARALLEL", "P"));
    texts.refreshTitleEn(id);
    jdbc.update("DELETE FROM work_text WHERE work_id = ? AND kind = 'PARALLEL'", id);
    texts.refreshTitleEn(id);
    assertThat(titleEn(id)).isEqualTo("Again machine");
    // no row left: null
    texts.deleteMachine(id, "TITLE");
    texts.refreshTitleEn(id);
    assertThat(titleEn(id)).isNull();
    assertThat(titleEnKind(id)).isNull();
  }

  @Test
  void titleEnNullWhenTitleIsEnglish() {
    long id = work("10.21/en", "A new species", "en");
    texts.upsert(title(id, "en", "PARALLEL", "A new species"));
    texts.refreshTitleEn(id);
    assertThat(titleEn(id)).isNull();
    // title_lang wins over the work language
    long other = work("10.21/en2", "Nueva especie", "en");
    jdbc.update("UPDATE work SET title_lang = 'es' WHERE id = ?", other);
    texts.upsert(title(other, "en", "MACHINE", "New species"));
    texts.refreshTitleEn(other);
    assertThat(titleEn(other)).isEqualTo("New species");
  }

  @Test
  void ftsFindsEnglishTitle() {
    long id = work("10.21/fts", "新种 Carabus 属", "zh");
    assertThat(jdbc.queryForObject("SELECT fts @@ websearch_to_tsquery('simple', 'new') FROM work WHERE id = ?",
        Boolean.class, id)).isFalse();
    texts.upsert(title(id, "en", "MACHINE", "New species of Carabus"));
    texts.refreshTitleEn(id);
    assertThat(jdbc.queryForObject("SELECT fts @@ websearch_to_tsquery('simple', 'species carabus') FROM work "
        + "WHERE id = ?", Boolean.class, id)).isTrue();
  }

  @Test
  void upsertReplacesAndListsInOrder() {
    long id = work("10.21/up", "t", null);
    texts.upsert(title(id, "en", "MACHINE", "one"));
    texts.upsert(title(id, "en", "MACHINE", "two"));
    texts.upsert(new WorkText(id, "ABSTRACT", "de", "ORIGINAL", "abs", "OAI", null, Instant.now()));
    List<WorkText> all = texts.forWork(id);
    assertThat(all).extracting(WorkText::field, WorkText::text).containsExactly(
        org.assertj.core.groups.Tuple.tuple("ABSTRACT", "abs"), org.assertj.core.groups.Tuple.tuple("TITLE", "two"));
    assertThat(all.get(1).model()).isEqualTo("model-1");
    assertThat(all.get(1).created()).isNotNull();
    assertThat(texts.forWorks(List.of(id))).hasSize(2);
    assertThat(texts.forWorks(List.of())).isEmpty();
  }

  @Test
  void moveAllKeepsSurvivorRows() {
    long from = work("10.21/from", "f", null);
    long to = work("10.21/to", "t", null);
    texts.upsert(title(from, "en", "MACHINE", "from-en"));
    texts.upsert(title(from, "de", "PARALLEL", "from-de"));
    texts.upsert(title(to, "en", "MACHINE", "to-en"));
    assertThat(texts.moveAll(from, to)).isEqualTo(1);
    assertThat(texts.forWork(to)).extracting(WorkText::text).containsExactlyInAnyOrder("to-en", "from-de");
    evidence.upsert(new LanguageEvidence(from, "DETECTED", "lingua", "de", 0.9f, null));
    evidence.upsert(new LanguageEvidence(from, "DECLARED", "OAI", "de", null, "ger"));
    evidence.upsert(new LanguageEvidence(to, "DETECTED", "lingua", "en", 0.8f, null));
    assertThat(evidence.moveAll(from, to)).isEqualTo(1);
    assertThat(evidence.forWork(to)).extracting(LanguageEvidence::kind, LanguageEvidence::lang)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("DECLARED", "de"),
            org.assertj.core.groups.Tuple.tuple("DETECTED", "en"));
  }

  @Test
  void evidenceUpsertAndDecision() {
    long id = work("10.21/ev", "t", null);
    evidence.upsert(new LanguageEvidence(id, "DETECTED", "lingua", "fr", 0.5f, null));
    evidence.upsert(new LanguageEvidence(id, "DETECTED", "lingua", "de", 0.7f, "raw"));
    LanguageEvidence e = evidence.forWork(id).get(0);
    assertThat(e.lang()).isEqualTo("de");
    assertThat(e.confidence()).isEqualTo(0.7f);
    assertThat(e.raw()).isEqualTo("raw");
    evidence.upsert(new LanguageEvidence(id, "OPENALEX", "oa", null, null, null));
    assertThat(evidence.forWork(id)).hasSize(2);
    assertThat(evidence.setDecision(id, "de", "DETECTED")).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT language, language_source, language_decided_at IS NOT NULL AS decided "
        + "FROM work WHERE id = ?", id)).containsEntry("language", "de").containsEntry("language_source", "DETECTED")
        .containsEntry("decided", true);
    evidence.setDecision(id, null, "UNDECIDED");
    assertThat(jdbc.queryForObject("SELECT language FROM work WHERE id = ?", String.class, id)).isNull();
  }
}
