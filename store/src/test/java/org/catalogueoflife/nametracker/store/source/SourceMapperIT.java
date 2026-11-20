package org.catalogueoflife.nametracker.store.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SourceMapperIT extends AbstractPostgresIT {

  @Autowired SourceMapper sources;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired org.apache.ibatis.session.SqlSessionFactory sqlSessions;
  @Autowired org.catalogueoflife.nametracker.store.registry.RegistrationMapper registrations;

  private static Source source(String openalexId, String title, String tier) {
    return new Source(null, openalexId, "1111-2222", List.of("1111-2222"), title, "Pub", "host", true, true, tier,
        "SEED_TOPIC", "{}", true, List.of("sf"), "sf", null, List.of(), "{}", List.of(), "GOLD", "DE", null, null, 50,
        null, null, null, null, "SEED");
  }

  @Test
  void upsertSeedNeverChangesAdminTier() {
    long id = sources.upsertSeed(source("S-admin", "Old", "CANDIDATE"));
    assertThat(sources.setTier(id, "EXCLUDED", false, "ADMIN")).isEqualTo(1);
    long again = sources.upsertSeed(source("S-admin", "New", "CORE"));
    assertThat(again).isEqualTo(id);
    Source s = sources.findByOpenalexId("S-admin");
    assertThat(s.tier()).isEqualTo("EXCLUDED");
    assertThat(s.active()).isFalse();
    assertThat(s.tierSetBy()).isEqualTo("ADMIN");
    assertThat(s.title()).isEqualTo("New");
    assertThat(s.origin()).isEqualTo("SEED_TOPIC");
  }

  @Test
  void reSeedReplacesIssnsWithOpenAlexsList() {
    long id = sources.upsertSeed(new Source(null, "S-drop", null, List.of("1313-2989", "1175-5326"), "A", null, null, null,
        null, "CORE", "SEED_TOPIC", "{}", true, List.of(), null, null, List.of(), "{}", List.of(), null, null, null, null,
        null, null, null, null, null, "SEED"));
    sources.upsertSeed(new Source(null, "S-drop", null, List.of("1313-2989"), "A", null, null, null, null, "CORE",
        "SEED_TOPIC", "{}", true, List.of(), null, null, List.of(), "{}", List.of(), null, null, null, null, null, null,
        null, null, null, "SEED"));
    assertThat(sources.get(id).issns()).containsExactly("1313-2989");
  }

  @Test
  void listHarvestableNeedsOnboarding() {
    long a = sources.upsertSeed(source("S-h-a", "A", "CORE"));
    long b = sources.upsertSeed(source("S-h-b", "B", "CORE"));
    assertThat(sources.setOnboarded(a, Instant.now())).isEqualTo(1);
    assertThat(sources.listHarvestable()).extracting(Source::id).contains(a).doesNotContain(b);
    assertThat(sources.listToOnboard()).extracting(Source::id).contains(b).doesNotContain(a);
    assertThat(sources.setLastHarvested(a, Instant.now())).isEqualTo(1);
    assertThat(sources.get(a).lastHarvested()).isNotNull();
    sources.setTier(a, "CANDIDATE", true, "ADMIN");
    assertThat(sources.listHarvestable()).extracting(Source::id).doesNotContain(a);
  }

  private static final ProposalThresholds P = new ProposalThresholds(0.1f, 5f, 0.02f, 1f, 0.5f);

  private static AdminSourceQuery q(String tier, Boolean active, String q) {
    return new AdminSourceQuery(tier, active, q, null, null, null, null, null, null, null, "id");
  }

  @Test
  void listFiltersAndPages() {
    long a = sources.upsertSeed(source("S-l-a", "Zootaxa listing", "CORE"));
    long b = sources.upsertSeed(source("S-l-b", "ZooKeys listing", "BROAD"));
    sources.setTier(b, "BROAD", false, "ADMIN");
    assertThat(sources.adminList(q("CORE", null, "zootaxa LISTING"), P, 10, 0)).extracting(Source::id).containsExactly(a);
    assertThat(sources.adminList(q(null, false, "listing"), P, 10, 0)).extracting(Source::id).containsExactly(b);
    assertThat(sources.adminList(q(null, null, "listing"), P, 1, 0)).hasSize(1);
    assertThat(sources.adminList(q(null, null, "listing"), P, 1, 1)).hasSize(1);
    assertThat(sources.adminList(q(null, null, "%"), P, 10, 0)).isEmpty();
    assertThat(sources.countByTier()).anySatisfy(r -> {
      assertThat(r).containsEntry("tier", "CORE");
      assertThat(((Number) r.get("n")).longValue()).isGreaterThanOrEqualTo(1);
    });
  }

  @Test
  void findByIssnMatchesLinkingAndOtherIssns() {
    long a = sources.upsertSeed(new Source(null, "S-issn-a", "5555-666X", List.of("5555-666X", "7777-8888"), "A",
        "Pub", "host", true, true, "CORE", "SEED_TOPIC", "{}", true, List.of("sf"), "sf", null, List.of(), "{}",
        List.of(), "GOLD", "DE", null, null, 50, null, null, null, null, "SEED"));
    assertThat(sources.findByIssn("5555-666X")).extracting(Source::id).containsExactly(a);
    assertThat(sources.findByIssn("5555-666x")).extracting(Source::id).containsExactly(a);
    assertThat(sources.findByIssn("7777-8888")).extracting(Source::id).containsExactly(a);
    assertThat(sources.findByIssn("0000-0000")).isEmpty();
  }

  @Test
  void findByIssnUsesTheIssnIndexes() {
    String sql = sqlSessions.getConfiguration()
        .getMappedStatement("org.catalogueoflife.nametracker.store.source.SourceMapper.findByIssn")
        .getBoundSql(java.util.Map.of("issn", "1175-5326")).getSql().replace("?", "'1175-5326'");
    List<String> plan = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<List<String>>) c -> {
      List<String> lines = new java.util.ArrayList<>();
      c.setAutoCommit(false);
      try (var st = c.createStatement()) {
        st.execute("SET LOCAL enable_seqscan = off");
        try (var rs = st.executeQuery("EXPLAIN " + sql)) {
          while (rs.next()) lines.add(rs.getString(1));
        }
      } finally {
        c.rollback();
        c.setAutoCommit(true);
      }
      return lines;
    });
    assertThat(String.join("\n", plan)).contains("source_issns_idx").contains("source_issn_l_idx")
        .doesNotContain("Seq Scan");
  }

  @Test
  void foldDeletesOnlyAFoldableCandidate() {
    long keep = jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('Keep', 'MANUAL') RETURNING id", Long.class);
    long promoted = jdbc.queryForObject("INSERT INTO source (title, origin, tier, tier_set_by) VALUES ('Promoted', "
        + "'DOAJ', 'CORE', 'ADMIN') RETURNING id", Long.class);
    long curated = jdbc.queryForObject("INSERT INTO source (title, origin, tier, tier_set_by) VALUES ('Curated', "
        + "'DOAJ', 'CANDIDATE', 'ADMIN') RETURNING id", Long.class);
    // an admin promoted it after the fold read it as a candidate: the delete finds nothing and the fold fails
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sources.foldInto(promoted, keep))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining(String.valueOf(promoted));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sources.foldInto(curated, keep))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sources.get(promoted)).isNotNull();
    assertThat(sources.get(curated)).isNotNull();
  }

  @Test
  void aRegistrationFoldedIntoAnExcludedJournalIsNoAdminDecision() {
    long user = jdbc.queryForObject("INSERT INTO app_user (email) VALUES ('fold-excluded@store.test') RETURNING id",
        Long.class);
    long excluded = jdbc.queryForObject("INSERT INTO source (title, origin, tier, active) VALUES ('Nope', 'MANUAL', "
        + "'EXCLUDED', false) RETURNING id", Long.class);
    long core = jdbc.queryForObject("INSERT INTO source (title, origin, tier) VALUES ('Yes', 'MANUAL', 'CORE') "
        + "RETURNING id", Long.class);
    long a = jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('A', 'DOAJ') RETURNING id", Long.class);
    long b = jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('B', 'DOAJ') RETURNING id", Long.class);
    long toExcluded = jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, state, outcome, "
        + "source_id, tier_at_identification) VALUES (?, 'ISSN', 'x', 'IDENTIFIED', 'NEW_CANDIDATE', ?, 'CANDIDATE') "
        + "RETURNING id", Long.class, user, a);
    long toCore = jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, state, outcome, "
        + "source_id, tier_at_identification) VALUES (?, 'ISSN', 'y', 'IDENTIFIED', 'NEW_CANDIDATE', ?, 'CANDIDATE') "
        + "RETURNING id", Long.class, user, b);
    try {
      sources.foldInto(a, excluded);
      sources.foldInto(b, core);
      // like a direct hit on the excluded journal: nobody declined anything, no mail; the followed one is accepted
      assertThat(jdbc.queryForObject("SELECT tier_at_identification FROM source_registration WHERE id = ?",
          String.class, toExcluded)).isEqualTo("EXCLUDED");
      assertThat(registrations.claimDecisions(10)).extracting(
          org.catalogueoflife.nametracker.store.registry.RegistrationMapper.Decision::id).containsExactly(toCore);
    } finally {
      jdbc.update("DELETE FROM app_user WHERE id = ?", user);
    }
  }

  @Test
  void foldNeverMakesASourceContinueItself() {
    long keep = jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('Keep', 'MANUAL') RETURNING id", Long.class);
    long dup = jdbc.queryForObject("INSERT INTO source (title, origin, continued_by) VALUES ('Dup', 'MANUAL', ?) "
        + "RETURNING id", Long.class, keep);
    long old = jdbc.queryForObject("INSERT INTO source (title, origin, continued_by) VALUES ('Old', 'MANUAL', ?) "
        + "RETURNING id", Long.class, dup);
    jdbc.update("UPDATE source SET continued_by = ? WHERE id = ?", dup, keep); // keep continues dup, dup continues keep
    sources.foldInto(dup, keep);
    assertThat(jdbc.queryForObject("SELECT continued_by FROM source WHERE id = ?", Long.class, keep)).isNull();
    assertThat(jdbc.queryForObject("SELECT continued_by FROM source WHERE id = ?", Long.class, old)).isEqualTo(keep);
  }
}
