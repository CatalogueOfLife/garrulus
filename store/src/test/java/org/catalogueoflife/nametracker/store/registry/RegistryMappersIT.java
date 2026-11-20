package org.catalogueoflife.nametracker.store.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RegistryMappersIT extends AbstractPostgresIT {

  @Autowired SourceMapper sources;
  @Autowired SourceSignalMapper signals;
  @Autowired IssnLinkMapper issnLinks;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  private long coreSource(String oa) {
    return sources.upsertSeed(new Source(null, oa, "1111-2222", List.of("1111-2222"), "T", "Pub", "host", true, true,
        "CORE", "SEED_TOPIC", "{}", true, List.of(), null, null, List.of(), "{}", List.of(), null, null, null, null,
        null, null, null, null, null, "SEED"));
  }

  @Test
  void signalUpsertKeepsFirstSeenAndBumpsLastSeen() {
    long s = coreSource("S-sig-1");
    assertThat(signals.upsert(s, "PLAZI", "k", "{\"treatments\":1}")).isEqualTo(1);
    jdbc.update("UPDATE source_signal SET first_seen = now() - interval '2 days', last_seen = now() - interval '2 days'"
        + " WHERE source_id = ?", s);
    SourceSignal before = signals.forSource(s).get(0);
    assertThat(signals.upsert(s, "PLAZI", "k", "{\"treatments\":2}")).isEqualTo(1);
    List<SourceSignal> after = signals.forSource(s);
    assertThat(after).hasSize(1);
    assertThat(after.get(0).firstSeen()).isEqualTo(before.firstSeen());
    assertThat(after.get(0).lastSeen()).isAfter(before.lastSeen());
    assertThat(after.get(0).detail()).contains("\"treatments\"").contains("2");
    assertThat(signals.delete(s, "PLAZI", "k")).isEqualTo(1);
    assertThat(signals.forSource(s)).isEmpty();
  }

  @Test
  void signalsMoveToAnotherSource() {
    long a = coreSource("S-sig-a");
    long b = coreSource("S-sig-b");
    signals.upsert(a, "DOAJ", "", "{}");
    signals.upsert(a, "BLR", "", "{}");
    signals.upsert(b, "DOAJ", "", "{}"); // already on the target
    assertThat(signals.moveAll(a, b)).isEqualTo(1);
    assertThat(signals.forSource(b)).extracting(SourceSignal::origin).containsExactlyInAnyOrder("DOAJ", "BLR");
    assertThat(signals.forSource(a)).extracting(SourceSignal::origin).containsExactly("DOAJ");
  }

  @Test
  void ceasedSourcesAreNotHarvestable() {
    long live = coreSource("S-ceased-live");
    long ceased = coreSource("S-ceased-x");
    sources.setOnboarded(live, Instant.now());
    sources.setOnboarded(ceased, Instant.now());
    long notOnboarded = coreSource("S-ceased-n");
    jdbc.update("UPDATE source SET publication_status = 'CEASED', ceased_reason = 'STOPPED' WHERE id IN (?, ?)",
        ceased, notOnboarded);
    assertThat(sources.listHarvestable()).extracting(Source::id).contains(live).doesNotContain(ceased);
    assertThat(sources.listToOnboard()).extracting(Source::id).doesNotContain(notOnboarded);
    assertThat(sources.listBackfillable()).extracting(Source::id).contains(live, ceased, notOnboarded);
    assertThat(sources.get(ceased).publicationStatus()).isEqualTo("CEASED");
    assertThat(sources.get(ceased).ceasedReason()).isEqualTo("STOPPED");
    assertThat(sources.get(live).publicationStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void updateIdentityIgnoresNullIssnElements() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of("2222-3334"), "Cand", null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null));
    sources.updateIdentity(id, null, java.util.Arrays.asList("3333-4445", null), null, null, null, null, null);
    assertThat(sources.get(id).issns()).containsExactly("2222-3334", "3333-4445");
  }

  @Test
  void updateIdentityUnionsIssnsAndKeepsNulls() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of("2222-3334"), "Cand", null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null));
    Source c = sources.get(id);
    assertThat(c.tier()).isEqualTo("CANDIDATE");
    assertThat(c.tierSetBy()).isEqualTo("SEED");
    assertThat(sources.updateIdentity(id, "2222-3334", List.of("2222-3334", "3333-4445"), null, "Pub", null,
        "S-ident-1", null)).isEqualTo(1);
    assertThat(sources.updateIdentity(id, null, List.of("5555-6666"), "New title", null, "DE", null, "host"))
        .isEqualTo(1);
    Source u = sources.get(id);
    assertThat(u.issnL()).isEqualTo("2222-3334");
    assertThat(u.issns()).containsExactlyInAnyOrder("2222-3334", "3333-4445", "5555-6666");
    assertThat(u.title()).isEqualTo("New title");
    assertThat(u.publisher()).isEqualTo("Pub");
    assertThat(u.country()).isEqualTo("DE");
    assertThat(u.openalexId()).isEqualTo("S-ident-1");
    assertThat(u.host()).isEqualTo("host");
    assertThat(sources.findByIssnL("2222-3334")).extracting(Source::id).containsExactly(id);
    assertThat(sources.setCandidateScore(id, 0.5f)).isEqualTo(1);
    assertThat(sources.get(id).candidateScore()).isEqualTo(0.5f);
    assertThat(sources.get(id).candidateScoredAt()).isNotNull();
  }

  @Test
  void insertCandidateWithoutTitleFallsBackToFirstIssn() {
    long id = sources.insertCandidate(new Source(null, null, null, List.of("7777-8888"), null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null));
    assertThat(sources.get(id).title()).isEqualTo("7777-8888");
  }

  @Test
  void issnLinkLookups() {
    jdbc.update("INSERT INTO issn_link (issn, issn_l) VALUES ('1000-0001', '1000-0001'), ('1000-0002', '1000-0001')");
    assertThat(issnLinks.issnL("1000-0002")).isEqualTo("1000-0001");
    assertThat(issnLinks.issnL("9999-9999")).isNull();
    assertThat(issnLinks.siblings("1000-0001")).containsExactly("1000-0001", "1000-0002");
  }

  @Test
  void migrationBackfilledRawSubfields() {
    JdbcTemplate j = new JdbcTemplate(dataSource);
    j.execute("CREATE SCHEMA migcheck19");
    Flyway.configure().dataSource(dataSource).schemas("migcheck19").target("18").load().migrate();
    j.execute("INSERT INTO migcheck19.source (openalex_id, title, origin, subfields) "
        + "VALUES ('S-mig19', 'M', 'SEED_TOPIC', '{1105,1103}')");
    j.execute("INSERT INTO migcheck19.source (openalex_id, title, origin) VALUES ('S-mig19b', 'N', 'SEED_TOPIC')");
    Flyway.configure().dataSource(dataSource).schemas("migcheck19").target("19").load().migrate();
    assertThat(j.queryForList("SELECT code, rank FROM migcheck19.source_classification_raw c "
        + "JOIN migcheck19.source s ON s.id = c.source_id WHERE s.openalex_id = 'S-mig19' AND scheme = "
        + "'OPENALEX_SUBFIELD' ORDER BY rank")).extracting(m -> m.get("code") + ":" + m.get("rank"))
        .containsExactly("1105:0", "1103:1");
    assertThat(j.queryForObject("SELECT discipline_source FROM migcheck19.source WHERE openalex_id = 'S-mig19'",
        String.class)).isEqualTo("OPENALEX");
    assertThat(j.queryForObject("SELECT discipline_source FROM migcheck19.source WHERE openalex_id = 'S-mig19b'",
        String.class)).isNull();
    assertThat(j.queryForObject("SELECT count(*) FROM migcheck19.discipline", Integer.class)).isEqualTo(9);
  }
}
