package org.catalogueoflife.nametracker.indexer.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.indexer.match.Placement;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.index.WorkName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkIndexerIT extends AbstractPostgresIT {

  @Autowired IndexMapper index;
  @Autowired JdbcTemplate jdbc;
  @Autowired WorkIndexer indexer;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM name_ubiquity");
  }

  private long work(String textLevel) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, license, origin, state, text_level) VALUES ('10.1/' || "
        + "nextval('work_id_seq'), 't', 'cc-by', 'LIVE', 'MATCHED', ?) RETURNING id", Long.class, textLevel);
  }

  private long mention(long work, String name, String blockType, int start, String snippet, String act) {
    long ns = index.nameStringId(name, "BINOMIAL", "SPECIES");
    jdbc.update("INSERT INTO mention (work_id, name_string_id, block_id, block_type, start_cp, end_cp, verbatim, "
        + "confidence, act_type, snippet, evidence) VALUES (?, ?, ?, ?, ?, ?, ?, 0.9, ?, ?, '{}'::jsonb)",
        work, ns, blockType + start, blockType, start, start + 10, name, act, snippet);
    return ns;
  }

  private static Placement placement(String code, String group) {
    return new Placement("MATCH", "u1", List.of("a", "b"), code, group);
  }

  @Test
  void indexTwiceReplacesWorkNames() {
    long w = work("FULLTEXT");
    long a = mention(w, "Carabus auratus", "TITLE", 0, "title", "sp. nov.");
    long b = mention(w, "Escherichia coli", "PARAGRAPH", 5, "para", null);
    Map<Long, Placement> p = Map.of(a, placement("ZOOLOGICAL", "Coleoptera"), b, placement("BACTERIAL", "Bacteria"));
    indexer.index(w, p);
    List<WorkName> first = index.workNames(w);
    indexer.index(w, p);
    assertThat(index.workNames(w)).isEqualTo(first).hasSize(2);
    assertThat(jdbc.queryForObject("SELECT state FROM work WHERE id = ?", String.class, w)).isEqualTo("INDEXED");
    assertThat(jdbc.queryForObject("SELECT indexed_at IS NOT NULL FROM work WHERE id = ?", Boolean.class, w)).isTrue();
  }

  @Test
  void workColumnsAggregateMainSubjectsOnly() throws Exception {
    long w = work("FULLTEXT");
    long a = mention(w, "Carabus auratus", "TITLE", 0, "title", "sp. nov.");
    long b = mention(w, "Escherichia coli", "ABSTRACT", 5, "para", null);
    jdbc.update("INSERT INTO name_ubiquity (name_string_id, works, share) VALUES (?, 100, 0.05)", b);
    indexer.index(w, Map.of(a, placement("ZOOLOGICAL", "Coleoptera"), b, placement("BACTERIAL", "Bacteria")));
    Map<Long, WorkName> by = new java.util.HashMap<>();
    for (WorkName n : index.workNames(w)) by.put(n.nameStringId(), n);
    assertThat(by.get(a).mainSubject()).isTrue();
    assertThat(by.get(b).mainSubject()).isFalse();
    assertThat(by.get(a).actTypes()).containsExactly("sp. nov.");
    Map<String, Object> row = jdbc.queryForMap("SELECT codes, taxgroups, has_acts FROM work WHERE id = ?", w);
    assertThat((String[]) ((Array) row.get("codes")).getArray()).containsExactly("ZOOLOGICAL");
    List<String> groups = List.of((String[]) ((Array) row.get("taxgroups")).getArray());
    assertThat(groups).contains("Coleoptera", "Insects", "Animals").doesNotContain("Bacteria");
    assertThat(row.get("has_acts")).isEqualTo(true);
  }

  @Test
  void referenceOnlyNameKeepsRowButIsNotMain() {
    long w = work("FULLTEXT");
    long a = mention(w, "Carabus auratus", "REFERENCE", 0, "ref", null);
    indexer.index(w, Map.of(a, placement("ZOOLOGICAL", "Coleoptera")));
    WorkName n = index.workNames(w).get(0);
    assertThat(n.onlyReference()).isTrue();
    assertThat(n.bodyCount()).isZero();
    assertThat(n.salience()).isZero();
    assertThat(n.mainSubject()).isFalse();
  }

  @Test
  void bestSnippetPrefersTitleThenAbstractThenFirstBody() {
    long w = work("FULLTEXT");
    long t = mention(w, "Carabus auratus", "PARAGRAPH", 0, "p1", null);
    mention(w, "Carabus auratus", "ABSTRACT", 20, "abs", null);
    mention(w, "Carabus auratus", "TITLE", 40, "ttl", null);
    long b = mention(w, "Bembidion lampros", "REFERENCE", 60, "ref", null);
    mention(w, "Bembidion lampros", "PARAGRAPH", 80, "body", null);
    long c = mention(w, "Poecilus cupreus", "CAPTION", 100, "cap1", null);
    mention(w, "Poecilus cupreus", "PARAGRAPH", 120, "par2", null);
    indexer.index(w, Map.of());
    Map<Long, String> snip = new java.util.HashMap<>();
    for (WorkName n : index.workNames(w)) snip.put(n.nameStringId(), n.bestSnippet());
    assertThat(snip.get(t)).isEqualTo("ttl");
    assertThat(snip.get(b)).isEqualTo("body");
    assertThat(snip.get(c)).isEqualTo("cap1");
  }

  @Test
  void abstractOnlyWorkCountsBodyMentionsAsAbstract() {
    long w = work("ABSTRACT_ONLY");
    long a = mention(w, "Carabus auratus", "PARAGRAPH", 0, "p", null);
    indexer.index(w, Map.of(a, placement(null, null)));
    assertThat(index.workNames(w).get(0).inAbstract()).isTrue();
  }

  @Test
  void referenceActGivesNoActWeight() {
    long w = work("FULLTEXT");
    long a = mention(w, "Carabus auratus", "PARAGRAPH", 0, "body", null);
    mention(w, "Carabus auratus", "REFERENCE", 20, "ref", "sp. nov.");
    indexer.index(w, Map.of());
    WorkName n = index.workNames(w).get(0);
    assertThat(n.nameStringId()).isEqualTo(a);
    assertThat(n.actTypes()).containsExactly("sp. nov.");
    // share 0.25 only: no act (0.35), so below the 0.35 threshold
    assertThat(n.salience()).isEqualTo(0.25f);
    assertThat(n.mainSubject()).isFalse();
    // an act cited in the references is not an act of this work
    assertThat(jdbc.queryForObject("SELECT has_acts FROM work WHERE id = ?", Boolean.class, w)).isFalse();
  }

  @Test
  void workWithoutMentionsEndsIndexedEmpty() throws Exception {
    long w = work("NO_TEXT");
    indexer.index(w, Map.of());
    assertThat(index.workNames(w)).isEmpty();
    Map<String, Object> row = jdbc.queryForMap("SELECT state, codes, taxgroups, has_acts FROM work WHERE id = ?", w);
    assertThat(row.get("state")).isEqualTo("INDEXED");
    assertThat(((Array) row.get("codes")).getArray()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY).isEmpty();
    assertThat(((Array) row.get("taxgroups")).getArray()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.ARRAY).isEmpty();
    assertThat(row.get("has_acts")).isEqualTo(false);
  }
}
