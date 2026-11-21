package org.catalogueoflife.nametracker.indexer.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.lang.WorkTextMapper;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Import(TranslateTitleHandlerIT.Fake.class)
@TestPropertySource(properties = {"nametracker.translation.enabled=true", "nametracker.translation.model=fake/1"})
class TranslateTitleHandlerIT extends AbstractPostgresIT {

  interface ThrowingFn { String apply(String t, String from) throws java.io.IOException; }

  /** A translator whose behaviour and calls the tests control. */
  static class FakeTranslator implements TitleTranslator {
    volatile String model = "fake/1";
    volatile ThrowingFn fn = (t, from) -> "EN: " + t;
    final List<String> calls = new ArrayList<>();

    @Override public String engine() { return "fake"; }
    @Override public String model() { return model; }
    @Override public Set<String> sources() { return Set.of("zh", "ru", "es", "fr"); }
    @Override public synchronized String translate(String text, String from) throws java.io.IOException {
      calls.add(from + ":" + text);
      return fn.apply(text, from);
    }
  }

  @TestConfiguration
  static class Fake {
    @Bean @Primary FakeTranslator fakeTranslator() { return new FakeTranslator(); }
  }

  @Autowired TranslateTitleHandler handler;
  @Autowired FakeTranslator fake;
  @Autowired JdbcTemplate jdbc;
  @Autowired IndexMapper index;
  @Autowired WorkTextMapper texts;
  @Autowired TranslationProperties props;
  @Autowired org.catalogueoflife.nametracker.indexer.lang.LanguageService languages;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    fake.calls.clear();
    fake.model = "fake/1";
    fake.fn = (t, from) -> "EN: " + t;
  }

  private static Task task(String payload) {
    return new Task(1, TranslateTitleHandler.TYPE, payload, TaskPriority.BACKFILL, null, 1,
        Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS), null, null, null, "k", null);
  }

  private long work(String title, String lang, boolean named) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, language, language_source, names_doc_sha, origin, state, "
        + "text_level) VALUES ('10.1000/t' || nextval('work_id_seq'), ?, ?, 'DETECTED', ?, 'LIVE', 'INDEXED', "
        + "'ABSTRACT_ONLY') RETURNING id", Long.class, title, lang, named ? "sha" : null);
  }

  private void titleMention(long work, String verbatim) {
    long ns = index.nameStringId(verbatim, "SCIENTIFIC", null);
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, snippet, evidence) VALUES (?, ?, 'title', 'TITLE', 0, ?, ?, 0.9, ?, '{}'::jsonb)", work, ns,
        verbatim.length(), verbatim, verbatim);
  }

  private long machineRows(long id) {
    return jdbc.queryForObject("SELECT count(*) FROM work_text WHERE work_id = ? AND kind = 'MACHINE'", Long.class, id);
  }

  private void run(long id) throws Exception {
    handler.handle(task("{\"workId\":" + id + "}"));
  }

  @Test
  void chineseTitleGetsMachineTranslationLabelled() throws Exception {
    long id = work("中国步甲属一新种 Carabus auratus", "zh", true);
    titleMention(id, "Carabus auratus");
    fake.fn = (t, from) -> "A new species of Carabus auratus from China";
    run(id);
    assertThat(jdbc.queryForMap("SELECT lang, kind, text, source, model FROM work_text WHERE work_id = ? AND field = 'TITLE'", id))
        .containsEntry("lang", "en").containsEntry("kind", "MACHINE").containsEntry("source", "fake")
        .containsEntry("model", "fake/1").containsEntry("text", "A new species of Carabus auratus from China");
    assertThat(jdbc.queryForMap("SELECT title_en, title_en_kind FROM work WHERE id = ?", id))
        .containsEntry("title_en", "A new species of Carabus auratus from China").containsEntry("title_en_kind", "MACHINE");
    assertThat(jdbc.queryForObject("SELECT title FROM work WHERE id = ?", String.class, id))
        .isEqualTo("中国步甲属一新种 Carabus auratus");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE fts @@ websearch_to_tsquery('english', 'cockroach OR china') "
        + "AND id = ?", Long.class, id)).isEqualTo(1);
  }

  private long translateTasks() {
    return jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'TRANSLATE_TITLE'", Long.class);
  }

  @Test
  void aDroppedTranslationIsNotAskedAgainUntilTheModelChanges() throws Exception {
    long id = work("中国步甲属一新种 Carabus auratus", "zh", true);
    titleMention(id, "Carabus auratus");
    languages.evidence(id, org.catalogueoflife.nametracker.indexer.lang.LanguageService.declared(id, "OAI", "zh"));
    assertThat(translateTasks()).isEqualTo(1); // a named Chinese work without an English title
    jdbc.update("DELETE FROM task");
    fake.fn = (t, from) -> "A new species of ground beetle from China";

    run(id); // dropped: it loses the name
    run(id);
    assertThat(fake.calls).hasSize(1);
    languages.decide(id); // another sighting decides again
    assertThat(translateTasks()).isZero();

    handler.handle(task("{\"workId\":" + id + ",\"force\":true}")); // a forced run asks again
    assertThat(fake.calls).hasSize(2);
    fake.model = "fake/2"; // as does a new model
    run(id);
    assertThat(fake.calls).hasSize(3);
  }

  @Test
  void translationLosingANameIsDropped() throws Exception {
    long id = work("中国步甲属一新种 Carabus auratus", "zh", true);
    titleMention(id, "Carabus auratus");
    fake.fn = (t, from) -> "A new species of ground beetle from China";
    run(id);
    assertThat(machineRows(id)).isZero();
    assertThat(jdbc.queryForObject("SELECT title_en FROM work WHERE id = ?", String.class, id)).isNull();
    assertThat(fake.calls).hasSize(1);
  }

  @Test
  void echoAndEmptyAreDropped() throws Exception {
    long echo = work("Eine neue Art  von Käfern", "fr", true);
    fake.fn = (t, from) -> "Eine neue  Art von Käfern ";
    run(echo);
    long empty = work("Новый вид жуков", "ru", true);
    fake.fn = (t, from) -> "   ";
    run(empty);
    assertThat(machineRows(echo)).isZero();
    assertThat(machineRows(empty)).isZero();
    assertThat(fake.calls).hasSize(2);
  }

  @Test
  void parallelEnglishMeansNoTranslation() throws Exception {
    long id = work("Una especie nueva de escarabajo", "es", true);
    jdbc.update("INSERT INTO work_text (work_id, field, lang, kind, text, source) VALUES (?, 'TITLE', 'en', 'PARALLEL', "
        + "'A new species of beetle', 'OAI')", id);
    run(id);
    assertThat(fake.calls).isEmpty();
    assertThat(machineRows(id)).isZero();
  }

  @Test
  void englishWorkSkipped() throws Exception {
    long en = work("A new species of beetle", "en", true);
    long titleEn = work("Una especie nueva", "es", true);
    jdbc.update("UPDATE work SET title_lang = 'en' WHERE id = ?", titleEn);
    long undecided = work("Una especie nueva", null, true);
    long unsupported = work("Yeni bir tür", "sw", true);
    for (long id : new long[] {en, titleEn, undecided, unsupported}) run(id);
    assertThat(fake.calls).isEmpty();
  }

  @Test
  void notTranslatedBeforeNames() throws Exception {
    long id = work("Una especie nueva de escarabajo", "es", false);
    run(id);
    assertThat(fake.calls).isEmpty();
    assertThat(machineRows(id)).isZero();
  }

  @Test
  void disabledDoesNothing() throws Exception {
    long id = work("Una especie nueva de escarabajo", "es", true);
    TranslateTitleHandler off = new TranslateTitleHandler(
        new TranslationProperties(false, "http://localhost:5055", "m", java.time.Duration.ofSeconds(30)),
        new org.springframework.beans.factory.support.DefaultListableBeanFactory().getBeanProvider(TitleTranslator.class),
        handlerWorks(), texts, handlerSearch(), handlerQueue(), textLookups);
    off.handle(task("{\"workId\":" + id + "}"));
    off.handle(task("{\"all\":true}"));
    assertThat(fake.calls).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }

  @Autowired org.catalogueoflife.nametracker.store.work.WorkMapper workMapper;
  @Autowired org.catalogueoflife.nametracker.store.search.SearchMapper searchMapper;
  @Autowired org.catalogueoflife.nametracker.store.task.TaskQueue taskQueue;
  @Autowired org.catalogueoflife.nametracker.store.content.TextLookupMapper textLookups;

  private org.catalogueoflife.nametracker.store.work.WorkMapper handlerWorks() { return workMapper; }
  private org.catalogueoflife.nametracker.store.search.SearchMapper handlerSearch() { return searchMapper; }
  private org.catalogueoflife.nametracker.store.task.TaskQueue handlerQueue() { return taskQueue; }

  @Test
  void allRunsNonLatinFirst() throws Exception {
    long es1 = work("Una especie nueva", "es", true);
    long zh1 = work("一新种", "zh", true);
    long ru1 = work("Новый вид", "ru", true);
    long fr1 = work("Une espèce nouvelle", "fr", true);
    long en1 = work("A new species", "en", true);
    long unnamed = work("一新种二", "zh", false);
    handler.handle(task("{\"all\":true,\"batch\":1}"));
    // one NON_LATIN work done, continuation for the next page
    assertThat(fake.calls).containsExactly("zh:一新种");
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'TRANSLATE_TITLE'", String.class))
        .containsExactly("translate-all:NON_LATIN:" + zh1);
    jdbc.update("DELETE FROM task");
    handler.handle(task("{\"all\":true,\"afterId\":" + zh1 + ",\"batch\":1,\"script\":\"NON_LATIN\"}"));
    assertThat(fake.calls).containsExactly("zh:一新种", "ru:Новый вид");
    jdbc.update("DELETE FROM task");
    // the NON_LATIN pages are exhausted (a page smaller than the batch): LATIN starts
    handler.handle(task("{\"all\":true,\"afterId\":" + ru1 + ",\"batch\":5,\"script\":\"NON_LATIN\"}"));
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'TRANSLATE_TITLE'", String.class))
        .containsExactly("translate-all:LATIN:0");
    handler.handle(task("{\"all\":true,\"afterId\":0,\"batch\":5,\"script\":\"LATIN\"}"));
    assertThat(fake.calls).contains("es:Una especie nueva", "fr:Une espèce nouvelle");
    assertThat(fake.calls).doesNotContain("en:A new species");
    assertThat(machineRows(es1)).isEqualTo(1);
    assertThat(machineRows(fr1)).isEqualTo(1);
    assertThat(machineRows(en1)).isZero();
    assertThat(machineRows(unnamed)).isZero();
  }

  @Test
  void forceRetranslatesOtherModel() throws Exception {
    long id = work("Una especie nueva", "es", true);
    run(id);
    run(id); // same model: skipped
    assertThat(fake.calls).hasSize(1);
    fake.model = "fake/2";
    run(id); // a translation of another model is not the current one: translated again
    assertThat(fake.calls).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT model FROM work_text WHERE work_id = ? AND kind = 'MACHINE'", String.class, id))
        .isEqualTo("fake/2");
    run(id);
    assertThat(fake.calls).hasSize(2);
    handler.handle(task("{\"workId\":" + id + ",\"force\":true}")); // force: again, though the model is current
    assertThat(fake.calls).hasSize(3);
  }

  @Test
  void clientErrorOnOneTitleSkipsItAndContinues() throws Exception {
    long a = work("一新种甲", "zh", true);
    long bad = work("一新种乙", "zh", true);
    long c = work("一新种丙", "zh", true);
    fake.fn = (t, from) -> {
      if (t.endsWith("乙")) throw new java.io.IOException("LibreTranslate /translate returned HTTP 400");
      return "EN: " + t;
    };
    handler.handle(task("{\"all\":true,\"batch\":3,\"script\":\"NON_LATIN\"}"));
    assertThat(machineRows(a)).isEqualTo(1);
    assertThat(machineRows(bad)).isZero();
    assertThat(machineRows(c)).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT dedup_key FROM task WHERE type = 'TRANSLATE_TITLE'", String.class))
        .containsExactly("translate-all:NON_LATIN:" + c);
    run(bad); // a single-work task ends DONE with a WARN
  }

  @Test
  void nameCheckUsesWordBoundariesAndNfc() throws Exception {
    long diacritic = work("中国一新种 Hylobates müllerı", "zh", true);
    titleMention(diacritic, "Hylobates m\u00fcller\u0131");
    fake.fn = (t, f) -> "New species Hylobates mu\u0308ller\u0131 here"; // decomposed form
    run(diacritic);
    assertThat(machineRows(diacritic)).isEqualTo(1);

    long recased = work("一新种 Carabus", "zh", true);
    titleMention(recased, "Carabus");
    fake.fn = (t, f) -> "A new species of CARABUS";
    run(recased);
    assertThat(machineRows(recased)).isZero();

    long inWord = work("一新种 Rana", "zh", true);
    titleMention(inWord, "Rana");
    fake.fn = (t, f) -> "A new species from Granada";
    run(inWord);
    assertThat(machineRows(inWord)).isZero();
    fake.fn = (t, f) -> "A new species Rana, from Spain";
    handler.handle(task("{\"workId\":" + inWord + ",\"force\":true}")); // the drop is remembered: force asks again
    assertThat(machineRows(inWord)).isEqualTo(1);
  }

  @Test
  void droppedRetranslationLeavesOlderRow() throws Exception {
    long id = work("Una especie nueva", "es", true);
    run(id);
    handler.handle(task("{\"workId\":" + id + ",\"force\":true}")); // same model, echo -> dropped
    String before = jdbc.queryForObject("SELECT text FROM work_text WHERE work_id = ? AND kind = 'MACHINE'", String.class, id);
    fake.fn = (t, f) -> t;
    handler.handle(task("{\"workId\":" + id + ",\"force\":true}"));
    assertThat(jdbc.queryForObject("SELECT text FROM work_text WHERE work_id = ? AND kind = 'MACHINE'", String.class, id))
        .isEqualTo(before);
    assertThat(jdbc.queryForObject("SELECT title_en FROM work WHERE id = ?", String.class, id)).isEqualTo(before);
  }

  @Test
  void outageIsRethrown() {
    long id = work("Una especie nueva", "es", true);
    fake.fn = (t, f) -> { throw new ServiceOutageException("libretranslate", Instant.now()); };
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> run(id)).isInstanceOf(ServiceOutageException.class);
  }
}
