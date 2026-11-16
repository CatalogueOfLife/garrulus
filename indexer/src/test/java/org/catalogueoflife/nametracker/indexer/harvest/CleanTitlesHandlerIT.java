package org.catalogueoflife.nametracker.indexer.harvest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.finder.DocumentLoader;
import org.catalogueoflife.nametracker.indexer.finder.FindNamesHandler;
import org.catalogueoflife.nametracker.store.content.Content;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.task.Task;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(CleanTitlesHandlerIT.StubConfig.class)
class CleanTitlesHandlerIT extends AbstractPostgresIT {

  static final String DIRTY = "Identification of geraniol as a bioactive compound from\n"
      + "                    <i>Fraxinus mandschurica</i>\n                    and development";
  static final String CLEAN = "Identification of geraniol as a bioactive compound from Fraxinus mandschurica and development";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) throws Exception {
    Path dir = Files.createTempDirectory("garrulus-clean-titles");
    dir.toFile().deleteOnExit();
    r.add("nametracker.blobs.dir", () -> dir.resolve("blobs").toString());
    r.add("nametracker.content.spool-dir", () -> dir.resolve("spool").toString());
  }

  /** Finds "Fraxinus mandschurica" wherever it occurs. */
  static class FraxinusFinder implements NameFinder {
    @Override
    public FinderInfo info() {
      return new FinderInfo("stub", "1", "cfg1", null);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      List<Mention> ms = new ArrayList<>();
      for (Block b : doc.blocks()) {
        int cs = b.text().indexOf("Fraxinus mandschurica");
        if (cs < 0) continue;
        int start = CodePoints.toCodePoint(b.text(), cs);
        ms.add(new Mention("m" + ms.size(), b.id(), start, start + 21, "Fraxinus mandschurica", "Fraxinus mandschurica",
            NameKind.BINOMIAL, Rank.SPECIES, null, null, 0.9, new Evidence(Set.of(Generator.DICTIONARY), null, null),
            null, null, Map.of()));
      }
      return new FinderResult(info(), ms, List.of(), List.of(), List.of(), Map.of());
    }
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    FraxinusFinder fraxinusFinder() {
      return new FraxinusFinder();
    }
  }

  @Autowired CleanTitlesHandler handler;
  @Autowired FindNamesHandler find;
  @Autowired WorkMapper works;
  @Autowired ContentMapper contents;
  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
  }

  private long work(String title, String state, String level) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, abstract, license, origin, state, text_level) "
        + "VALUES ('10.1/' || nextval('work_id_seq'), ?, 'An abstract.', 'cc-by', 'LIVE', ?, ?) RETURNING id",
        Long.class, title, state, level);
  }

  private static Task task(String type, String payload) {
    return new Task(1, type, payload, TaskPriority.LIVE, null, 1, Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),
        null, null, null, "k", null);
  }

  private String title(long id) {
    return jdbc.queryForObject("SELECT title FROM work WHERE id = ?", String.class, id);
  }

  private List<String> queued(String type) {
    return jdbc.queryForList("SELECT payload::text FROM task WHERE type = ? AND state = 'QUEUED' ORDER BY id",
        String.class, type);
  }

  @Test
  void cleansTitlesAndRefindsOnlyChangedDocuments() throws Exception {
    long found = work(DIRTY, "CONTENT_RESOLVED", "ABSTRACT_ONLY");
    find.handle(task("FIND_NAMES", "{\"workId\":" + found + "}"));
    MentionRow before = index.mentions(found).get(0);
    assertThat(before.blockType()).isEqualTo("TITLE");
    String oldSha = index.namesDocSha(found);

    long unseen = work("New Aleocharinae (Mulsant &amp; Rey)", "CONTENT_RESOLVED", "ABSTRACT_ONLY");
    long clean = work("Spiders at pH < 5", "INDEXED", "ABSTRACT_ONLY");
    jdbc.update("UPDATE work SET names_doc_sha = ? WHERE id = ?", DocumentLoader.abstractSha(works.get(clean)), clean);
    // full text: the title is not in the document the finder read
    long full = work("A <i>Carabus</i> review", "INDEXED", "FULLTEXT");
    contents.upsert(new Content(null, full, "PMC", "JATS", "cc-by", true, "raw", "f".repeat(64), null, null));
    jdbc.update("UPDATE work SET names_doc_sha = ? WHERE id = ?", "f".repeat(64), full);
    jdbc.update("DELETE FROM task");

    handler.handle(task("CLEAN_TITLES", "{\"afterId\":0,\"batch\":1000}"));

    assertThat(title(found)).isEqualTo(CLEAN);
    assertThat(title(unseen)).isEqualTo("New Aleocharinae (Mulsant & Rey)");
    assertThat(title(clean)).isEqualTo("Spiders at pH < 5");
    assertThat(title(full)).isEqualTo("A Carabus review");
    // only the work whose named document changed is found again
    assertThat(queued("FIND_NAMES")).containsExactly("{\"workId\": " + found + "}");
    assertThat(jdbc.queryForObject("SELECT priority FROM task WHERE type = 'FIND_NAMES'", Integer.class))
        .isEqualTo(TaskPriority.REPROCESS.code());
    assertThat(queued("CLEAN_TITLES")).isEmpty();

    // the queued FIND_NAMES re-runs on the clean title: new document, mention spans in the clean TITLE block
    find.handle(task("FIND_NAMES", "{\"workId\":" + found + "}"));
    assertThat(index.namesDocSha(found)).isNotEqualTo(oldSha).isEqualTo(DocumentLoader.abstractSha(works.get(found)));
    MentionRow after = index.mentions(found).get(0);
    assertThat(after.blockType()).isEqualTo("TITLE");
    assertThat(CodePoints.slice(CLEAN, after.startCp(), after.endCp())).isEqualTo("Fraxinus mandschurica");
    assertThat(after.snippet()).doesNotContain("<i>").contains("from Fraxinus mandschurica and");
    assertThat(queued("MATCH_NAMES")).isNotEmpty();

    // idempotent: nothing left to clean
    jdbc.update("DELETE FROM task");
    handler.handle(task("CLEAN_TITLES", "{\"afterId\":0,\"batch\":1000}"));
    assertThat(queued("FIND_NAMES")).isEmpty();
  }

  @Test
  void continuesPerBatch() throws Exception {
    long a = work("<b>A</b>", "DISCOVERED", null);
    long b = work("B &amp; C", "DISCOVERED", null);
    long c = work("C\n  D", "DISCOVERED", null);

    handler.handle(task("CLEAN_TITLES", "{\"afterId\":0,\"batch\":2}"));
    assertThat(title(a)).isEqualTo("A");
    assertThat(title(b)).isEqualTo("B & C");
    assertThat(title(c)).isEqualTo("C\n  D");
    assertThat(queued("CLEAN_TITLES")).containsExactly("{\"batch\": 2, \"afterId\": " + b + "}");

    handler.handle(task("CLEAN_TITLES", "{\"afterId\":" + b + ",\"batch\":2}"));
    assertThat(title(c)).isEqualTo("C D");
    assertThat(queued("CLEAN_TITLES")).hasSize(1); // the last batch was short: no continuation
  }
}
