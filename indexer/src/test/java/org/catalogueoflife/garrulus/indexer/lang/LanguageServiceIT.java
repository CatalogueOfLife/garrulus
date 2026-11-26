package org.catalogueoflife.garrulus.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.indexer.harvest.WorksPager;
import org.catalogueoflife.garrulus.indexer.work.MergeWorksHandler;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.lang.LanguageEvidence;
import org.catalogueoflife.garrulus.store.task.Task;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.work.Layer;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class LanguageServiceIT extends AbstractPostgresIT {

  static final String EN_TITLE = "Two new species of cockroaches from southwestern China";
  static final String EN_ABSTRACT = "We describe two new species of cockroaches from southwestern China and "
      + "compare them with their congeners. The new species differ from all known members of the genus in the shape "
      + "of the male genitalia and in the colour of the pronotum. A key to the species of the genus is provided.";
  static final String ES_BODY = "Se describen dos especies nuevas de cucarachas del suroeste de China y se comparan "
      + "con sus congéneres. Las nuevas especies se diferencian de todas las demás del género por la forma de la "
      + "genitalia masculina y por el color del pronoto. Se proporciona una clave de las especies del género.";

  @Autowired LanguageService languages;
  @Autowired WorksPager pager;
  @Autowired WorkMapper works;
  @Autowired MergeWorksHandler merge;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
  }

  private static Work work(String doi, String title, String abs, String language) {
    return new Work(null, null, doi, null, title, null, null, null, null, null, null, "article", language, null, null,
        null, abs, abs == null ? null : "OPENALEX_INVERTED", "LIVE", null, null, null, null, null, null, null, null,
        null, null, null, null, null, null);
  }

  private long store(String doi, String title, String abs, String language, Layer layer, String declared) {
    pager.store(work(doi, title, abs, language), layer, List.of(IdScheme.id(IdScheme.DOI, doi)), TaskPriority.LIVE,
        null, declared, List.of());
    return jdbc.queryForObject("SELECT id FROM work WHERE doi = ?", Long.class, doi);
  }

  private Map<String, Object> decision(long id) {
    return jdbc.queryForMap("SELECT language, language_source FROM work WHERE id = ?", id);
  }

  @Test
  void aDecisionRefreshesTheEnglishTitleAfterTitleLangChangedAlone() {
    long id = store("10.1000/tl", "中国步甲属一新种", null, null, Layer.OAI, "zh");
    jdbc.update("INSERT INTO work_text (work_id, field, lang, kind, text, source, model) VALUES (?, 'TITLE', 'en', "
        + "'MACHINE', 'A new species of ground beetle', 'fake', 'm')", id);
    jdbc.update("UPDATE work SET title_lang = 'en', title_en = NULL, title_en_kind = NULL WHERE id = ?", id);
    languages.decide(id);
    assertThat(jdbc.queryForObject("SELECT title_en FROM work WHERE id = ?", String.class, id)).isNull(); // English

    jdbc.update("UPDATE work SET title_lang = 'zh' WHERE id = ?", id); // a path that changes title_lang alone
    languages.decide(id); // the decision itself (zh, DECLARED) does not change

    assertThat(jdbc.queryForMap("SELECT title_en, title_en_kind FROM work WHERE id = ?", id))
        .containsEntry("title_en", "A new species of ground beetle").containsEntry("title_en_kind", "MACHINE");
  }

  @Test
  void oaiEnglishSpelledOutIsEnglish() {
    long id = store("10.1000/oai", "Some title", null, null, Layer.OAI, "English");
    assertThat(decision(id)).containsEntry("language", "en").containsEntry("language_source", "DECLARED");
    assertThat(jdbc.queryForMap("SELECT lang, raw FROM work_language_evidence WHERE work_id = ? AND kind = 'DECLARED' "
        + "AND source = 'OAI'", id)).containsEntry("lang", "en").containsEntry("raw", "English");
  }

  @Test
  void europePmcChiIsZh() {
    long id = store("10.1000/epmc", "Some title", null, null, Layer.EUROPEPMC, "chi");
    assertThat(decision(id)).containsEntry("language", "zh").containsEntry("language_source", "DECLARED");
    assertThat(jdbc.queryForObject("SELECT source FROM work_language_evidence WHERE work_id = ?", String.class, id))
        .isEqualTo("EUROPEPMC");
  }

  @Test
  void openAlexUzOverruledByDetection() {
    long id = store("10.1000/uz", EN_TITLE, EN_ABSTRACT, "uz", Layer.OPENALEX, null);
    // Uzbek is not a language the detector can tell, and no journal has it: not trusted before detection either
    assertThat(decision(id).get("language")).isNull();
    assertThat(jdbc.queryForObject("SELECT lang FROM work_language_evidence WHERE work_id = ? AND kind = 'OPENALEX'",
        String.class, id)).isEqualTo("uz");

    languages.detectFromWork(works.get(id));
    languages.decide(id);

    assertThat(decision(id)).containsEntry("language", "en").containsEntry("language_source", "DETECTED");
    // the stored confidence is the margin, not Lingua's max-normalised top value
    float stored = jdbc.queryForObject("SELECT confidence FROM work_language_evidence WHERE work_id = ? AND "
        + "source = 'TITLE'", Float.class, id);
    assertThat(stored).isGreaterThanOrEqualTo(0.10f).isLessThan(1.0f);
  }

  @Test
  void anOpenAlexRecordWithoutLanguageDropsItsEvidence() {
    long id = store("10.1000/stale-oa", "Some title", null, "pt", Layer.OPENALEX, null);
    assertThat(decision(id)).containsEntry("language", "pt");
    store("10.1000/stale-oa", "Some title", null, null, Layer.OPENALEX, null);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_language_evidence WHERE work_id = ? AND kind = "
        + "'OPENALEX'", Long.class, id)).isZero();
    assertThat(decision(id)).containsEntry("language_source", "UNDECIDED");
  }

  @Test
  void fulltextDecides() {
    long id = store("10.1000/ft", EN_TITLE, EN_ABSTRACT, null, Layer.OAI, "en");
    assertThat(decision(id)).containsEntry("language", "en");
    Document body = new Document("work:" + id, null, List.of(
        new Block("t", BlockType.TITLE, 0, EN_TITLE, List.of(), List.of()),
        new Block("p1", BlockType.PARAGRAPH, 0, ES_BODY, List.of(), List.of()),
        new Block("p2", BlockType.PARAGRAPH, 0, ES_BODY, List.of(), List.of())), Map.of());
    languages.detectFromDocument(id, body);
    languages.decide(id);
    assertThat(decision(id)).containsEntry("language", "es").containsEntry("language_source", "FULLTEXT");
    assertThat(jdbc.queryForObject("SELECT raw FROM work_language_evidence WHERE work_id = ? AND kind = 'FULLTEXT'",
        String.class, id)).isNull();
  }

  @Test
  void abstractDocumentWithoutParagraphsGivesNoFulltextEvidence() {
    long id = store("10.1000/abs", EN_TITLE, EN_ABSTRACT, null, Layer.OAI, "en");
    languages.detectFromDocument(id, new Document("work:" + id, null,
        List.of(new Block("a", BlockType.ABSTRACT, 0, ES_BODY, List.of(), List.of())), Map.of()));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_language_evidence WHERE kind = 'FULLTEXT'", Long.class))
        .isZero();
  }

  @Test
  void newDeclaredEvidenceRedecides() {
    long id = store("10.1000/redo", "Some title", null, "pt", Layer.OPENALEX, null);
    assertThat(decision(id)).containsEntry("language", "pt").containsEntry("language_source", "OPENALEX");
    languages.evidence(id, LanguageService.declared(id, "OAI", "fre"));
    assertThat(decision(id)).containsEntry("language", "fr").containsEntry("language_source", "DECLARED");
  }

  @Test
  void aSignalThatYieldsNothingRemovesItsOldRow() {
    long id = store("10.1000/stale", EN_TITLE, EN_ABSTRACT, null, Layer.OAI, null);
    languages.detectFromWork(works.get(id));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_language_evidence WHERE work_id = ? AND kind = "
        + "'DETECTED'", Long.class, id)).isEqualTo(2);
    jdbc.update("UPDATE work SET title = 'Carabus auratus Linnaeus', abstract = NULL WHERE id = ?", id);
    languages.detectFromWork(works.get(id));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_language_evidence WHERE work_id = ? AND kind = "
        + "'DETECTED'", Long.class, id)).isZero();
  }

  @Test
  void mergeMovesEvidence() throws Exception {
    long a = store("10.1000/older", "Some title", null, null, Layer.OAI, null);
    long b = store("10.1000/younger", "Other title", null, null, Layer.EUROPEPMC, "es");
    assertThat(decision(a).get("language")).isNull();
    assertThat(decision(b)).containsEntry("language", "es");
    jdbc.update("UPDATE work SET doi = NULL WHERE id = ?", b); // one article under two ids, the DOIs not in conflict
    merge.handle(new Task(1, WorksPager.MERGE_WORKS, "{\"survivor\":" + a + ",\"other\":" + b + "}",
        TaskPriority.LIVE, null, 1, Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null,
        "k", null));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE id = ?", Long.class, b)).isZero();
    assertThat(jdbc.queryForObject("SELECT source FROM work_language_evidence WHERE work_id = ?", String.class, a))
        .isEqualTo("EUROPEPMC");
    assertThat(decision(a)).containsEntry("language", "es").containsEntry("language_source", "DECLARED");
  }

  @Test
  void doajSingleLanguageUsedLast() {
    long source = jdbc.queryForObject("INSERT INTO source (issn_l, title, tier, origin, languages) VALUES "
        + "('1234-5678', 'J', 'CORE', 'MANUAL', '{pt}') RETURNING id", Long.class);
    long id = store("10.1000/j", "Some title", null, null, Layer.OAI, null);
    jdbc.update("UPDATE work SET source_id = ? WHERE id = ?", source, id);
    languages.decide(id);
    assertThat(decision(id)).containsEntry("language", "pt").containsEntry("language_source", "JOURNAL");
    // declared evidence beats the journal
    languages.evidence(id, new LanguageEvidence(id, "DECLARED", "OAI", "es", null, "es"));
    assertThat(decision(id)).containsEntry("language", "es").containsEntry("language_source", "DECLARED");
    // two languages: the journal says nothing
    jdbc.update("DELETE FROM work_language_evidence WHERE work_id = ?", id);
    jdbc.update("UPDATE source SET languages = '{pt,es}' WHERE id = ?", source);
    languages.decide(id);
    assertThat(decision(id)).containsEntry("language_source", "UNDECIDED");
  }
}
