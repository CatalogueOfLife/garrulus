package org.catalogueoflife.garrulus.indexer.hits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.garrulus.indexer.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.account.Subscription;
import org.catalogueoflife.garrulus.store.search.SearchMapper;
import org.catalogueoflife.garrulus.store.search.WorkFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The public search (and feeds) and the follow matcher must give the same answer for every target and mentionedIn. */
class MentionedInAgreementIT extends AbstractPostgresIT {

  @Autowired JdbcTemplate jdbc;
  @Autowired SearchMapper search;

  /** name row of a fixture: group, parts. */
  record N(long id, String group, boolean title, boolean abs, boolean heading, int body, boolean ref, boolean main) {}

  long source;
  final List<Long> workIds = new ArrayList<>();
  final List<List<N>> workNames = new ArrayList<>();

  @BeforeEach
  void fixtures() {
    jdbc.update("DELETE FROM work");
    jdbc.update("DELETE FROM source");
    jdbc.update("DELETE FROM name_string");
    source = jdbc.queryForObject("INSERT INTO source (title, origin, tier, active) VALUES ('J', 'MANUAL', 'CORE', true) RETURNING id", Long.class);
    workIds.clear();
    workNames.clear();
    // 0: Mammalia in the title, Coleoptera only in the body
    add(n("Mammalia", true, false, false, 0, false), n("Coleoptera", false, false, false, 3, false));
    // 1: Coleoptera in the title only
    add(n("Coleoptera", true, false, false, 0, false));
    // 2: Coleoptera in a heading only
    add(n("Coleoptera", false, false, true, 0, false));
    // 3: Coleoptera only in references
    add(n("Coleoptera", false, false, false, 0, true));
    // 4: Coleoptera abstract, Aves body
    add(n("Coleoptera", false, true, false, 0, false), n("Aves", false, false, false, 2, false));
    // 6: a non-main Coleoptera name in the title, a main Mammalia name in the body (work.taxgroups holds main names only)
    add(n("Coleoptera", true, false, false, 0, false, false), n("Mammalia", false, false, false, 2, false, true));
    // 7: non-main Coleoptera in the abstract, nothing main
    add(n("Coleoptera", false, true, false, 0, false, false));
    // 5: no part flags at all
    add(n("Coleoptera", false, false, false, 0, false));
  }

  private int seq;

  N n(String group, boolean t, boolean a, boolean h, int b, boolean ref) {
    return n(group, t, a, h, b, ref, !ref); // a reference-only name is never a main subject
  }

  N n(String group, boolean t, boolean a, boolean h, int b, boolean ref, boolean main) {
    long id = jdbc.queryForObject("INSERT INTO name_string (name, kind, rank) VALUES (?, 'SCIENTIFIC', 'species') RETURNING id",
        Long.class, "Name " + group + (++seq));
    return new N(id, group, t, a, h, b, ref, main);
  }

  void add(N... names) {
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, year, publication_date, state, origin, text_level, source_id) "
        + "VALUES ('10.1/ag' || nextval('work_id_seq'), 't', 2026, '2026-01-01', 'INDEXED', 'LIVE', 'FULLTEXT', ?) RETURNING id",
        Long.class, source);
    for (N x : names) {
      jdbc.update("INSERT INTO work_name (work_id, name_string_id, mention_count, body_count, in_title, in_abstract, "
          + "in_heading, only_reference, best_snippet, placement_method, usage_id, taxon_path, taxgroup, salience, main_subject) "
          + "VALUES (?, ?, 1, ?, ?, ?, ?, ?, 's', 'MATCH', 'U1', '{U1,TX}', ?, 0.9, ?)", w, x.id, x.body, x.title, x.abs,
          x.heading, x.ref, x.group, x.main);
    }
    java.util.Set<String> groups = new java.util.LinkedHashSet<>();
    for (N x : names) if (x.main) org.catalogueoflife.garrulus.indexer.match.TaxGroups.addWithAncestors(x.group, groups);
    jdbc.update("UPDATE work SET taxgroups = ?::text[] WHERE id = ?", "{" + String.join(",", groups) + "}", w);
    workIds.add(w);
    workNames.add(List.of(names));
  }

  static final List<List<String>> PARTS = List.of(List.of(), List.of("TITLE"), List.of("ABSTRACT"), List.of("BODY"),
      List.of("TITLE", "ABSTRACT"), List.of("TITLE", "ABSTRACT", "BODY"));

  record Case(String type, String target) {}

  @Test
  void searchAndMatcherAgree() {
    long someName = workNames.get(1).get(0).id;
    List<Case> cases = List.of(new Case("TAXON", "TX"), new Case("NAME", String.valueOf(someName)),
        new Case("TAXGROUP", "Coleoptera"), new Case("TAXGROUP", "Arthropods"), new Case("TAXGROUP", "Mammals"),
        new Case("SOURCE", String.valueOf(source)));
    int checked = 0;
    for (Case c : cases) {
      for (List<String> parts : PARTS) {
        for (int i = 0; i < workIds.size(); i++) {
          long w = workIds.get(i);
          for (boolean main : new boolean[] {true, false}) {
          WorkFilter.Builder b = WorkFilter.builder().mainSubjectOnly(main).mentionedIn(parts);
          switch (c.type) {
            case "TAXON" -> b.taxon(c.target);
            case "NAME" -> b.name(Long.parseLong(c.target));
            case "TAXGROUP" -> b.taxgroup(c.target);
            default -> b.source(Long.parseLong(c.target));
          }
          // the public feed and the work search of the same filter
          boolean found = search.search(b.build(), 100, 0).stream().anyMatch(h -> h.id() == w);
          var s = new Subscription(1L, 1, c.type, c.target, null, main, false, true, false, "{}", "t", null, false,
              Instant.EPOCH, "OK", null, null, null, parts);
          List<WorkNameView> views = workNames.get(i).stream()
              .map(x -> new WorkNameView(x.id, "n" + x.id, new String[] {"U1", "TX"}, x.group, x.main, x.ref, new String[0],
                  "MATCH", x.title, x.abs, x.heading, x.body)).toList();
          boolean matched = SubscriptionMatcher.match(s, new WorkInfo(w, source, "FULLTEXT"), views).matches();
          assertThat(found).as("%s %s %s main=%s work %d", c.type, c.target, parts, main, i).isEqualTo(matched);
          checked++;
          }
        }
      }
    }
    assertThat(checked).isEqualTo(cases.size() * PARTS.size() * workIds.size() * 2);
  }

  /** Taxgroup counts main subjects only: works 5 and 6 (0-based) have a non-main Coleoptera name only, work 1 has it main. */
  @Test
  void taxgroupCountsMainSubjectsOnly() {
    for (List<String> parts : List.of(List.<String>of(), List.of("TITLE"), List.of("ABSTRACT"))) {
      for (boolean main : new boolean[] {true, false}) {
        var f = WorkFilter.builder().mainSubjectOnly(main).mentionedIn(parts).taxgroup("Arthropods").build();
        var ids = search.search(f, 100, 0).stream().map(h -> h.id()).toList();
        assertThat(ids).as("%s main=%s", parts, main).doesNotContain(workIds.get(5), workIds.get(6));
        var sub = new Subscription(1L, 1, "TAXGROUP", "Arthropods", null, main, false, true, false, "{}", "t", null, false,
            Instant.EPOCH, "OK", null, null, null, parts);
        for (int i : new int[] {5, 6}) {
          var views = workNames.get(i).stream().map(x -> new WorkNameView(x.id, "n" + x.id, new String[] {"U1", "TX"}, x.group,
              x.main, x.ref, new String[0], "MATCH", x.title, x.abs, x.heading, x.body)).toList();
          assertThat(SubscriptionMatcher.match(sub, new WorkInfo(workIds.get(i), source, "FULLTEXT"), views).matches())
              .as("work %d %s main=%s", i, parts, main).isFalse();
        }
        // work 1 (index 1) has Coleoptera main in the title: listed for TITLE and for no part
        if (parts.isEmpty() || parts.contains("TITLE")) assertThat(ids).contains(workIds.get(1));
      }
    }
  }
}
