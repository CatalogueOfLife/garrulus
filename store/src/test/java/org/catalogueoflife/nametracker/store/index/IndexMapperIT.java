package org.catalogueoflife.nametracker.store.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class IndexMapperIT extends AbstractPostgresIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired IndexMapper index;
  @Autowired WorkMapper works;
  @Autowired JdbcTemplate jdbc;

  private long newWork() {
    String doi = "10.1/idx" + SEQ.incrementAndGet();
    return new org.catalogueoflife.nametracker.store.work.WorkUpsert(works).upsert(new Work(null, null, doi, null, "T", null, null, null, null, null, null, null, null, null,
        null, null, null, null, "LIVE", null, null, null, null, null, null, null, null, null, null, null, null, null)).id();
  }

  @Test
  void finderRunUpsertIsStable() {
    long a = index.finderRunId(new FinderRun(null, "pipeline", "1", "abc", "dict1"));
    assertThat(index.finderRunId(new FinderRun(null, "pipeline", "1", "abc", "dict1"))).isEqualTo(a);
    long n = index.finderRunId(new FinderRun(null, "pipeline", "1", "abc", null));
    assertThat(n).isNotEqualTo(a);
    assertThat(index.finderRunId(new FinderRun(null, "pipeline", "1", "abc", null))).isEqualTo(n);
    assertThat(index.finderRunId(new FinderRun(null, "pipeline", "1", "def", null))).isNotEqualTo(n);
  }

  @Test
  void nameStringUpsertKeepsFirstKind() {
    long id = index.nameStringId("Abies alba", "SCIENTIFIC", "species");
    assertThat(index.nameStringId("Abies alba", "OTU", null)).isEqualTo(id);
    assertThat(jdbc.queryForMap("SELECT kind, rank FROM name_string WHERE id = ?", id))
        .containsEntry("kind", "SCIENTIFIC").containsEntry("rank", "species");
    long id2 = index.nameStringId("Quercus", "SCIENTIFIC", null);
    assertThat(id2).isNotEqualTo(id);
    // an existing name is not written to: same row version
    String xmin = jdbc.queryForObject("SELECT xmin::text FROM name_string WHERE id = ?", String.class, id);
    assertThat(index.nameStringId("Abies alba", "SCIENTIFIC", "species")).isEqualTo(id);
    assertThat(jdbc.queryForObject("SELECT xmin::text FROM name_string WHERE id = ?", String.class, id)).isEqualTo(xmin);
    assertThat(index.nameStringIdByName("Quercus")).isEqualTo(id2);
  }

  @Test
  void deleteWorkIndexRemovesOnlyThatWork() {
    long w1 = newWork();
    long w2 = newWork();
    long ns = index.nameStringId("Delete me", "SCIENTIFIC", null);
    for (long w : new long[] {w1, w2}) {
      long ctx = index.insertContext(new ContextRow(null, w, "c1", "Fam. X", "{\"family\":\"X\"}", "BLOCK", "b1", null));
      index.insertMentions(List.of(new MentionRow(null, w, ns, "b1", "PARAGRAPH", 0, 9, "Delete me", null, null, 0.9f,
          null, ctx, "snip", "{}")));
      index.insertWorkNames(List.of(workName(w, ns)));
    }
    index.deleteWorkIndex(w1);
    assertThat(index.mentions(w1)).isEmpty();
    assertThat(index.contexts(w1)).isEmpty();
    assertThat(index.workNames(w1)).isEmpty();
    assertThat(index.mentions(w2)).hasSize(1);
    assertThat(index.contexts(w2)).hasSize(1);
    assertThat(index.workNames(w2)).hasSize(1);
    MentionRow m = index.mentions(w2).get(0);
    assertThat(m.id()).isNotNull();
    assertThat(m.confidence()).isEqualTo(0.9f);
    assertThat(m.contextId()).isEqualTo(index.contexts(w2).get(0).id());
    assertThat(index.contexts(w2).get(0).ranks()).contains("family");
  }

  private static WorkName workName(long w, long ns) {
    return new WorkName(w, ns, 3, 2, true, false, false, false, List.of("sp. nov."), "snippet", "MATCH", "U1",
        List.of("A", "B"), "ICZN", "Plantae", 0.5f, true);
  }

  @Test
  void updateSalienceHandlesWorksWithTensOfThousandsOfNames() {
    int n = 25_000; // 3 bind parameters per row in a VALUES list would be 75,000 > 65,535
    long w = newWork();
    jdbc.update("INSERT INTO name_string (name, kind) SELECT 'Bulk salience ' || g, 'SCIENTIFIC' FROM generate_series(1, ?) g", n);
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract,"
        + " in_heading, only_reference, best_snippet, placement_method, salience, main_subject)"
        + " SELECT ?, id, 1, 1, false, false, false, false, 's', 'NONE', 0.1, false FROM name_string"
        + " WHERE name LIKE 'Bulk salience %'", w);
    List<Long> ids = jdbc.queryForList("SELECT name_string_id FROM work_name WHERE work_id = ? ORDER BY 1", Long.class, w);
    assertThat(ids).hasSize(n);
    List<WorkName> upd = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      upd.add(new WorkName(w, ids.get(i), 1, 1, false, false, false, false, List.of(), "s", "NONE", null, List.of(),
          null, null, i % 2 == 0 ? 0.9f : 0.1f, i % 2 == 0));
    }
    assertThat(index.updateSalience(w, upd)).isEqualTo(n / 2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_name WHERE work_id = ? AND main_subject AND salience > 0.8",
        Integer.class, w)).isEqualTo(n / 2);
    // unchanged values are not written again
    assertThat(index.updateSalience(w, upd)).isZero();
    assertThat(index.updateSalience(w, List.of())).isZero();
  }

  @Test
  void workNameRoundTrip() {
    long w = newWork();
    long ns = index.nameStringId("Round trip", "SCIENTIFIC", null);
    index.insertWorkNames(List.of(workName(w, ns)));
    assertThat(index.workNames(w)).containsExactly(workName(w, ns));
  }

  @Test
  void onlyOneCurrentRelease() {
    index.addRelease(1001, "R1", true);
    assertThat(index.currentRelease().key()).isEqualTo(1001);
    assertThatThrownBy(() -> index.addRelease(1002, "R2", true)).isInstanceOf(Exception.class);
    index.addRelease(1003, "R3", false);
    assertThat(index.currentRelease().alias()).isEqualTo("R1");
  }

  @Test
  void matchRoundTrip() {
    index.addRelease(2001, "R", false);
    long ns = index.nameStringId("Match me", "SCIENTIFIC", null);
    String cls = "[{\"id\":\"X\",\"name\":\"Animalia\",\"rank\":\"kingdom\"}]";
    assertThat(index.match(ns, "ICZN", 2001)).isNull();
    index.putMatch(new NameMatch(ns, "ICZN", 2001, "EXACT", "U1", "Match me", "species", "accepted", null, "ICZN",
        "Animals", List.of("X", "Y", "Z"), cls, null));
    NameMatch m = index.match(ns, "ICZN", 2001);
    assertThat(m.taxonPath()).containsExactly("X", "Y", "Z");
    assertThat(m.classification()).contains("Animalia").contains("kingdom");
    assertThat(m.matchedAt()).isNotNull();
    assertThat(m.acceptedId()).isNull();
    index.putMatch(new NameMatch(ns, "ICZN", 2001, "NONE", null, null, null, null, null, null, null, List.of(), "[]",
        null));
    assertThat(index.match(ns, "ICZN", 2001).matchType()).isEqualTo("NONE");
  }

  @Test
  void stateTransitions() {
    long w = newWork();
    long run = index.finderRunId(new FinderRun(null, "p", "1", "st", null));
    index.setNamesFound(w, run, "sha", "ABSTRACT_ONLY");
    assertThat(jdbc.queryForMap("SELECT state, current_finder_run, names_doc_sha, names_text_level FROM work "
        + "WHERE id = ?", w))
        .containsEntry("state", "NAMES_FOUND").containsEntry("current_finder_run", run)
        .containsEntry("names_doc_sha", "sha").containsEntry("names_text_level", "ABSTRACT_ONLY");
    index.setIndexed(w, new String[] {"ICZN"}, new String[] {"Animals"}, true, 1);
    Work x = works.get(w);
    assertThat(x.state()).isEqualTo("INDEXED");
    assertThat(x.codes()).containsExactly("ICZN");
    assertThat(x.taxgroups()).containsExactly("Animals");
    assertThat(x.hasActs()).isTrue();
    assertThat(jdbc.queryForObject("SELECT indexed_at IS NOT NULL FROM work WHERE id = ?", Boolean.class, w)).isTrue();
    assertThat(jdbc.queryForObject("SELECT placed_release FROM work WHERE id = ?", Integer.class, w)).isEqualTo(1);
  }

  @Test
  void reindexPrevSubscriptionsAreStoredAndCleared() {
    long w = newWork();
    index.setReindexPrevSubscriptions(w, new long[] {3, 5});
    assertThat(jdbc.queryForObject("SELECT reindex_prev_subscriptions::text FROM work WHERE id = ?", String.class, w))
        .isEqualTo("{3,5}");
    index.setReindexPrevSubscriptions(w, null);
    assertThat(jdbc.queryForObject("SELECT reindex_prev_subscriptions IS NULL FROM work WHERE id = ?", Boolean.class,
        w)).isTrue();
  }
}
