package org.catalogueoflife.nametracker.store.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SearchMapperIT extends AbstractPostgresIT {

  private static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired SearchMapper search;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    jdbc.update("DELETE FROM name_string");
  }

  static WorkFilter filter(UnaryOperator<Builder> f) {
    return f.apply(new Builder()).build();
  }

  /** Test helper: all-null filter with a few setters. */
  static final class Builder {
    String taxon, taxgroup, q, code, actType, journalType;
    Long source;
    Boolean hasActs;
    boolean mainSubjectOnly;
    Integer yearFrom, yearTo;

    Builder taxon(String v) { taxon = v; return this; }
    Builder taxgroup(String v) { taxgroup = v; return this; }
    Builder q(String v) { q = v; return this; }
    Builder code(String v) { code = v; return this; }
    Builder journalType(String v) { journalType = v; return this; }
    Builder main() { mainSubjectOnly = true; return this; }

    WorkFilter build() {
      return new WorkFilter(taxon, null, code, taxgroup, null, journalType, null, null, null, source, yearFrom, yearTo,
          hasActs, actType, mainSubjectOnly, q);
    }
  }

  long source(String journalType) {
    return jdbc.queryForObject("INSERT INTO source (title, origin, journal_type) VALUES ('J', 'MANUAL', ?) RETURNING id",
        Long.class, journalType);
  }

  long work(String state, String date, String title, String abs, String[] codes, String[] groups, Long source) {
    int n = SEQ.incrementAndGet();
    return jdbc.queryForObject(
        "INSERT INTO work (doi, title, abstract, publication_date, year, state, origin, codes, taxgroups, source_id, text_level) "
            + "VALUES (?, ?, ?, ?::date, EXTRACT(YEAR FROM ?::date), ?, 'LIVE', ?, ?, ?, 'ABSTRACT_ONLY') RETURNING id",
        Long.class, "10.9/s" + n, title, abs, date, date, state, codes, groups, source);
  }

  long indexed(String date, String title, String[] groups) {
    return work("INDEXED", date, title, null, new String[] {"ICZN"}, groups, null);
  }

  void name(long work, String taxonId, boolean onlyRef, boolean main) {
    long ns = jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES (?, 'SCIENTIFIC') RETURNING id",
        Long.class, "Name " + SEQ.incrementAndGet());
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, false, false, ?, 's', 'MATCH', ?, ?, 0.5, ?)",
        work, ns, onlyRef, taxonId, new String[] {taxonId, "ROOT"}, main);
  }

  List<Long> ids(WorkFilter f) {
    return search.search(f, 100, 0).stream().map(WorkHit::id).toList();
  }

  @Test
  void onlyIndexedWorksAreListed() {
    long a = indexed("2026-01-01", "a", new String[] {});
    long b = work("NOTIFIED", "2026-01-02", "b", null, new String[] {}, new String[] {}, null);
    work("DISCOVERED", "2026-01-03", "c", null, new String[] {}, new String[] {}, null);
    work("MATCHED", "2026-01-04", "d", null, new String[] {}, new String[] {}, null);
    assertThat(ids(filter(x -> x))).containsExactly(b, a);
    assertThat(search.count(filter(x -> x))).isEqualTo(2);
  }

  @Test
  void taxonFilterIncludesDescendants() {
    long w = indexed("2026-01-01", "a", new String[] {});
    name(w, "SPECIES1", false, true); // path SPECIES1, ROOT
    long other = indexed("2026-01-02", "b", new String[] {});
    name(other, "OTHER", false, true);
    assertThat(ids(filter(x -> x.taxon("ROOT")))).containsExactlyInAnyOrder(w, other);
    assertThat(ids(filter(x -> x.taxon("SPECIES1")))).containsExactly(w);
  }

  @Test
  void taxonFilterFindsWorksNamingASynonym() {
    long w = indexed("2026-01-01", "a", new String[] {});
    long ns = jdbc.queryForObject("INSERT INTO name_string (name, kind) VALUES ('Syn onym', 'SCIENTIFIC') RETURNING id", Long.class);
    // a synonym is placed under its accepted taxon: the path is the accepted taxon's
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
        + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, salience, main_subject) "
        + "VALUES (?, ?, 1, 1, false, false, false, false, 's', 'MATCH', 'SYN', ?, 0.5, true)",
        w, ns, new String[] {"ACC", "GEN", "ROOT"});
    assertThat(ids(filter(x -> x.taxon("ACC")))).containsExactly(w);
  }

  @Test
  void taxonFilterIgnoresReferenceOnlyNames() {
    long w = indexed("2026-01-01", "a", new String[] {});
    name(w, "T1", true, false);
    assertThat(ids(filter(x -> x.taxon("T1")))).isEmpty();
  }

  @Test
  void mainSubjectOnlyNarrows() {
    long w1 = indexed("2026-01-01", "a", new String[] {});
    name(w1, "T1", false, true);
    long w2 = indexed("2026-01-02", "b", new String[] {});
    name(w2, "T1", false, false);
    assertThat(ids(filter(x -> x.taxon("T1")))).containsExactlyInAnyOrder(w1, w2);
    assertThat(ids(filter(x -> x.taxon("T1").main()))).containsExactly(w1);
    assertThat(ids(filter(Builder::main))).containsExactly(w1);
  }

  @Test
  void taxgroupFilterIsHierarchical() {
    long beetle = indexed("2026-01-01", "beetle", new String[] {"Coleoptera", "Insects", "Animals"});
    indexed("2026-01-02", "plant", new String[] {"Plants"});
    assertThat(ids(filter(x -> x.taxgroup("Insects")))).containsExactly(beetle);
    assertThat(ids(filter(x -> x.taxgroup("Coleoptera")))).containsExactly(beetle);
  }

  @Test
  void qSearchesTitleAndAbstract() {
    long a = work("INDEXED", "2026-01-01", "A new weevil", null, new String[] {}, new String[] {}, null);
    long b = work("INDEXED", "2026-01-02", "Other", "about a weevil too", new String[] {}, new String[] {}, null);
    work("INDEXED", "2026-01-03", "Nothing", "nada", new String[] {}, new String[] {}, null);
    assertThat(ids(filter(x -> x.q("weevil")))).containsExactly(b, a);
    assertThat(ids(filter(x -> x.q("\"new weevil\"")))).containsExactly(a);
  }

  @Test
  void facetsCountTheFilteredSet() {
    long tax = source("TAXONOMIC");
    long gen = source("GENERAL");
    work("INDEXED", "2026-01-01", "a", null, new String[] {"ICZN"}, new String[] {"Insects", "Animals"}, tax);
    work("INDEXED", "2026-02-01", "b", null, new String[] {"ICZN", "ICN"}, new String[] {"Animals"}, tax);
    work("INDEXED", "2025-02-01", "c", null, new String[] {"ICN"}, new String[] {"Plants"}, gen);
    work("DISCOVERED", "2025-02-01", "d", null, new String[] {"ICN"}, new String[] {"Plants"}, gen);

    assertThat(search.facet(filter(x -> x), "code"))
        .containsExactly(new FacetCount("ICN", 2), new FacetCount("ICZN", 2));
    assertThat(search.facet(filter(x -> x), "year"))
        .containsExactlyInAnyOrder(new FacetCount("2026", 2), new FacetCount("2025", 1));
    assertThat(search.facet(filter(x -> x), "journalType"))
        .containsExactly(new FacetCount("TAXONOMIC", 2), new FacetCount("GENERAL", 1));
    // facets honour the filter
    assertThat(search.facet(filter(x -> x.journalType("TAXONOMIC")), "taxgroup"))
        .containsExactly(new FacetCount("Animals", 2), new FacetCount("Insects", 1));
    assertThat(search.facet(filter(x -> x.taxgroup("Plants")), "source")).containsExactly(new FacetCount("" + gen, 1));
    assertThat(search.facet(filter(x -> x), "hasActs")).containsExactly(new FacetCount("false", 3));
  }

  @Test
  void sortIsNewestFirst() {
    long old = indexed("2020-01-01", "old", new String[] {});
    long newer = indexed("2026-03-01", "new", new String[] {});
    long undated = work("INDEXED", null, "undated", null, new String[] {}, new String[] {}, null);
    long sameDayLow = indexed("2026-03-01", "same", new String[] {});
    assertThat(ids(filter(x -> x))).containsExactly(sameDayLow, newer, old, undated);
    assertThat(search.search(filter(x -> x), 2, 1).stream().map(WorkHit::id)).containsExactly(newer, old);
  }
}
