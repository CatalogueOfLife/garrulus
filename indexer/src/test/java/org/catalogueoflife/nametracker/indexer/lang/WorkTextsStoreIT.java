package org.catalogueoflife.nametracker.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.harvest.WorksPager;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Parallel and original titles and abstracts of the layers: what the pager stores and sets. */
class WorkTextsStoreIT extends AbstractPostgresIT {

  static final String EN = "Two new species of cockroaches from southwestern China";
  static final String ES = "Dos especies nuevas de cucarachas del suroeste de China";
  static final String ZH = "中国西南地区蜚蠊科两新属";

  @Autowired WorksPager pager;
  @Autowired WorkMapper works;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
  }

  private static Work work(String doi, String title, String abs) {
    return new Work(null, null, doi, null, title, null, null, null, null, null, null, "article", null, null, null,
        null, abs, abs == null ? null : "OAI", "LIVE", null, null, null, null, null, null, null, null, null, null,
        null, null, null, null);
  }

  private long store(String doi, String title, String abs, Layer layer, List<TextIn> texts, String titleLang,
      String abstractLang) {
    pager.store(work(doi, title, abs), layer, List.of(IdScheme.id(IdScheme.DOI, doi)), TaskPriority.LIVE, null, null,
        texts, titleLang, abstractLang);
    return jdbc.queryForObject("SELECT id FROM work WHERE doi = ?", Long.class, doi);
  }

  @Test
  void parallelVersionsStoredWithLayerAsSourceAndTitleEnRefreshed() {
    long id = store("10.1234/a", ES, "Se describen especies.", Layer.OAI, List.of(
        new TextIn("TITLE", "en", "PARALLEL", EN), new TextIn("TITLE", "es", "PARALLEL", ES),
        new TextIn("ABSTRACT", "en", "PARALLEL", "Species are described."),
        new TextIn("TITLE", null, "PARALLEL", "Notes")), "es", "es");
    assertThat(jdbc.queryForList("SELECT field || '|' || lang || '|' || kind || '|' || source FROM work_text "
        + "WHERE work_id = ? ORDER BY field, lang", String.class, id))
        .containsExactly("ABSTRACT|en|PARALLEL|OAI", "TITLE|en|PARALLEL|OAI");
    assertThat(jdbc.queryForMap("SELECT title, title_lang, abstract_lang, title_en, title_en_kind FROM work WHERE id = "
        + id)).containsEntry("title", ES).containsEntry("title_lang", "es").containsEntry("abstract_lang", "es")
        .containsEntry("title_en", EN).containsEntry("title_en_kind", "PARALLEL");
  }

  @Test
  void crossrefOriginalTitleGivesTitleLang() {
    long id = store("10.1234/b", EN, null, Layer.CROSSREF, List.of(new TextIn("TITLE", null, "ORIGINAL", ZH)), null, null);
    assertThat(jdbc.queryForMap("SELECT title, title_lang FROM work WHERE id = " + id))
        .containsEntry("title", EN).containsEntry("title_lang", "en");
    assertThat(jdbc.queryForList("SELECT lang || '|' || kind || '|' || source FROM work_text WHERE work_id = ?",
        String.class, id)).containsExactly("zh|ORIGINAL|CROSSREF");
  }

  @Test
  void crossrefOriginalInTheTitlesLanguageOrNotConfidentStoresNothing() {
    long same = store("10.1234/c", EN, null, Layer.CROSSREF, List.of(new TextIn("TITLE", null, "ORIGINAL",
        "Two new species of cockroaches from south-western China")), null, null);
    long unsure = store("10.1234/d", EN, null, Layer.CROSSREF, List.of(new TextIn("TITLE", null, "ORIGINAL", "Notes")),
        null, null);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_text", Long.class)).isZero();
    assertThat(jdbc.queryForList("SELECT title_lang FROM work WHERE id IN (" + same + "," + unsure + ")",
        String.class)).containsOnlyNulls();
  }

  @Test
  void textLangsNeverOverwriteWithNull() {
    long id = store("10.1234/e", EN, "An abstract.", Layer.MAGTECH, List.of(), "en", "en");
    assertThat(works.setTextLangs(id, null, null)).isZero();
    assertThat(works.setTextLangs(id, null, "fr")).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT title_lang, abstract_lang FROM work WHERE id = " + id))
        .containsEntry("title_lang", "en").containsEntry("abstract_lang", "fr");
  }

  @Test
  void fillAbstractOnlyWhenNone() {
    long id = store("10.1234/f", EN, null, Layer.OAI, List.of(), null, null);
    assertThat(works.fillAbstract(id, "First.", null, "EUROPEPMC")).isEqualTo(1);
    assertThat(works.fillAbstract(id, "Second.", null, "OAI")).isZero();
    assertThat(jdbc.queryForMap("SELECT abstract, abstract_source FROM work WHERE id = " + id))
        .containsEntry("abstract", "First.").containsEntry("abstract_source", "EUROPEPMC");
  }

  @Test
  void unsureTitleLanguageDropsTheOriginal() {
    long id = store("10.1234/g", "Notes", null, Layer.CROSSREF, List.of(new TextIn("TITLE", null, "ORIGINAL", ZH)),
        null, null);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_text WHERE work_id = " + id, Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT title_lang FROM work WHERE id = " + id, String.class)).isNull();
  }

  @Test
  void lateLowerRankedLayerDoesNotRelabelOrOverwrite() {
    long id = store("10.1234/h", EN, "An abstract.", Layer.CROSSREF, List.of(
        new TextIn("TITLE", "zh", "PARALLEL", ZH)), "en", "en");
    long better = store("10.1234/i", EN, null, Layer.OPENALEX, List.of(new TextIn("TITLE", "es", "PARALLEL", ES)),
        null, null);
    // a later OAI record: another title (not kept by the merge), another parallel of the same language, a claimed lang
    store("10.1234/h", "Dos especies nuevas", null, Layer.OAI, List.of(new TextIn("TITLE", "zh", "PARALLEL", "其他")),
        "es", "es");
    store("10.1234/i", "Dos especies", null, Layer.OAI, List.of(new TextIn("TITLE", "es", "PARALLEL", "Otro título")),
        "fr", null);
    assertThat(jdbc.queryForMap("SELECT title, title_lang, abstract_lang FROM work WHERE id = " + id))
        .containsEntry("title", EN).containsEntry("title_lang", "en").containsEntry("abstract_lang", "en");
    // the OpenAlex work had no title_lang: a lower-ranked layer may fill it, but not replace the better row
    assertThat(jdbc.queryForMap("SELECT text, source FROM work_text WHERE work_id = " + better))
        .containsEntry("text", ES).containsEntry("source", "OPENALEX");
  }

  @Test
  void reharvestIsIdempotentAndAHigherRankReplaces() {
    List<TextIn> v = List.of(new TextIn("TITLE", "es", "PARALLEL", ES));
    long id = store("10.1234/j", EN, null, Layer.OAI, v, "en", null);
    store("10.1234/j", EN, null, Layer.OAI, v, "en", null);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_text WHERE work_id = " + id, Long.class)).isEqualTo(1);
    store("10.1234/j", EN, null, Layer.OPENALEX, List.of(new TextIn("TITLE", "es", "PARALLEL", "Otro título de especies")),
        null, null);
    assertThat(jdbc.queryForMap("SELECT text, source FROM work_text WHERE work_id = " + id))
        .containsEntry("text", "Otro título de especies").containsEntry("source", "OPENALEX");
  }

  @Test
  void textKeepsItsOriginatingLayer() {
    long id = store("10.1234/k", EN, null, Layer.OPENALEX, List.of(
        new TextIn("TITLE", null, "ORIGINAL", ZH, Layer.CROSSREF)), null, null);
    assertThat(jdbc.queryForList("SELECT source FROM work_text WHERE work_id = ?", String.class, id))
        .containsExactly("CROSSREF");
  }
}
