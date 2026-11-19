package org.catalogueoflife.nametracker.store.ident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.work.Work;
import org.catalogueoflife.nametracker.store.work.WorkMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityMappersIT extends AbstractPostgresIT {

  @Autowired WorkMapper works;
  @Autowired WorkIdentifierMapper identifiers;
  @Autowired WorkSightingMapper sightings;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;

  private long work(String doi) {
    return works.insert(new Work(null, null, doi, null, "t", null, null, null, null, null, "[]", null, null, null,
        null, null, null, null, "LIVE", "DISCOVERED", null, List.of(), List.of(), false, null, null, null, null,
        false, null, null, null));
  }

  @Test
  void insertAllIsIdempotent() {
    long w = work("10.16/idem");
    List<Identifier> ids = List.of(new Identifier("DOI", "10.16/idem"), new Identifier("PMCID", "PMC16"));
    assertThat(identifiers.insertAll(w, ids, "CROSSREF")).isEqualTo(2);
    assertThat(identifiers.insertAll(w, ids, "CROSSREF")).isZero();
    assertThat(identifiers.list(w)).containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  void findWorkIdsReturnsEveryHolder() {
    long a = work("10.16/ha");
    long b = work("10.16/hb");
    identifiers.insertAll(a, List.of(new Identifier("DOI", "10.16/ha")), "CROSSREF");
    identifiers.insertAll(b, List.of(new Identifier("PMCID", "PMC16b")), "EUROPEPMC");
    List<Long> found = identifiers.findWorkIds(
        List.of(new Identifier("PMCID", "PMC16b"), new Identifier("DOI", "10.16/ha"), new Identifier("DOI", "none")));
    assertThat(found).containsExactly(Math.min(a, b), Math.max(a, b));
    assertThat(identifiers.findWorkIds(List.of())).isEmpty();
  }

  @Test
  void moveAllMovesIdentifiersAndSightings() {
    long from = work("10.16/from");
    long to = work("10.16/to");
    identifiers.insertAll(from, List.of(new Identifier("DOI", "10.16/from"), new Identifier("PMCID", "PMC16m")),
        "CROSSREF");
    identifiers.insertAll(to, List.of(new Identifier("DOI", "10.16/to")), "CROSSREF");
    sightings.sight(from, "CROSSREF", null);
    sightings.sight(from, "EUROPEPMC", null);
    sightings.sight(to, "CROSSREF", null);

    assertThat(identifiers.moveAll(from, to)).isEqualTo(2);
    assertThat(sightings.moveAll(from, to)).isEqualTo(1); // CROSSREF is already on the target
    assertThat(identifiers.list(from)).isEmpty();
    assertThat(identifiers.list(to)).hasSize(3);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work_sighting WHERE work_id = ?", Integer.class, to))
        .isEqualTo(2);
  }

  @Test
  void sightIsIdempotent() {
    long w = work("10.16/sight");
    assertThat(sightings.sight(w, "OPENALEX", null)).isEqualTo(1);
    assertThat(sightings.sight(w, "OPENALEX", null)).isZero();
  }

  @Test
  void migrationBackfilledExistingWorks() {
    // a separate schema: the shared one is already at the latest version
    DataSource ds = dataSource;
    JdbcTemplate j = new JdbcTemplate(ds);
    j.execute("CREATE SCHEMA migcheck");
    Flyway at15 = Flyway.configure().dataSource(ds).schemas("migcheck").target("15").load();
    at15.migrate();
    j.execute("INSERT INTO migcheck.work (openalex_id, doi, origin, abstract_jats, pmcid) "
        + "VALUES ('W-mig1', '10.16/mig1', 'LIVE', '<abstract/>', 'PMC1')");
    j.execute("INSERT INTO migcheck.work (doi, origin) VALUES ('10.16/mig2', 'LIVE')");
    Flyway.configure().dataSource(ds).schemas("migcheck").target("16").load().migrate();

    assertThat(j.queryForObject("SELECT count(*) FROM migcheck.work_identifier", Integer.class)).isEqualTo(4);
    assertThat(j.queryForList("SELECT scheme FROM migcheck.work_identifier WHERE value = 'PMC1'", String.class))
        .containsExactly("PMCID");
    assertThat(j.queryForList("SELECT layer FROM migcheck.work_sighting ORDER BY layer", String.class))
        .containsExactly("CROSSREF", "OPENALEX");
    assertThat(j.queryForList("SELECT abstract_jats_source FROM migcheck.work WHERE abstract_jats IS NOT NULL",
        String.class)).containsExactly("CROSSREF");
    // a work with no DOI and no OpenAlex id is now allowed
    j.execute("INSERT INTO migcheck.work (origin) VALUES ('LIVE')");
  }
}
