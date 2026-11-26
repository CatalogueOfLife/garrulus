package org.catalogueoflife.garrulus.store.ident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.garrulus.store.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.work.Work;
import org.catalogueoflife.garrulus.store.work.WorkMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityMappersIT extends AbstractPostgresIT {

  @Autowired WorkMapper works;
  @Autowired WorkIdentifierMapper identifiers;
  @Autowired WorkSightingMapper sightings;
  @Autowired JdbcTemplate jdbc;

  private long work(String doi) {
    return works.insert(new Work(null, null, doi, null, "t", null, null, null, null, null, "[]", null, null, null,
        null, null, null, null, "LIVE", "DISCOVERED", null, List.of(), List.of(), false, null, null, null, null,
        false, null, null, null, null));
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
}
