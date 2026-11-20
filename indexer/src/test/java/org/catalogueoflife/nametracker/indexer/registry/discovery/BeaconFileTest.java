package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeaconFileTest {

  static InputStream sample() {
    return BeaconFileTest.class.getResourceAsStream("/discovery/beacon-sample.tab");
  }

  @Test
  void readsColumnsByHeader() throws IOException {
    List<BeaconRow> rows;
    try (var s = BeaconFile.read(sample())) {
      rows = s.toList();
    }
    assertThat(rows).hasSize(8);
    BeaconRow enfit = rows.get(1);
    assertThat(enfit.contextName()).isEqualTo("JURNAL ENFIT : Entomologi dan Fitopatologi");
    assertThat(enfit.issns()).containsExactly("2716-0564");
    assertThat(enfit.oaiUrl()).isEqualTo("https://ejournal.unsrat.ac.id/index.php/index/oai");
    assertThat(enfit.setSpec()).isNotBlank();
    assertThat(enfit.country()).isEqualTo("Indonesia");
    assertThat(enfit.totalRecords()).isNotNull();
    assertThat(enfit.records2025()).isEqualTo(5);
    assertThat(enfit.lastOaiResponse()).isEqualTo(Instant.parse("2026-06-30T09:24:33Z"));
    assertThat(enfit.unresponsive()).isFalse();
    assertThat(rows.get(2).unresponsive()).isTrue();
    assertThat(rows.get(4).records2025()).isZero();
    assertThat(rows.get(4).lastOaiResponse().atZone(ZoneOffset.UTC).getYear()).isEqualTo(2024);
  }

  @Test
  void multiIssnCellAndNaValues() throws IOException {
    List<BeaconRow> rows;
    try (var s = BeaconFile.read(sample())) {
      rows = s.toList();
    }
    assertThat(rows.get(0).issns()).containsExactly("0187-7151", "2448-7589"); // the two characters \n
    assertThat(rows.get(5).issns()).isEmpty(); // NA
    assertThat(rows.get(5).contextName()).isEqualTo("Indian Journal of Entomology");
  }

  @Test
  void realNewlinesQuotesAndNaFields() throws IOException {
    String t = "context_name\tissn\toai_url\tcountry\ttotal_record_count\trecord_count_2025\tset_spec\tlast_oai_response"
        + "\tunresponsive_endpoint\tunresponsive_context\n"
        + "\"A \"\"quoted\"\" name\"\t\"1175-5326\n0002-936X\nbad\"\t\"NA\"\t\"NA\"\tNA\t7\t\"NA\"\t\"NA\"\t0\t1\n";
    try (var s = BeaconFile.read(new ByteArrayInputStream(t.getBytes(StandardCharsets.UTF_8)))) {
      BeaconRow r = s.findFirst().orElseThrow();
      assertThat(r.contextName()).isEqualTo("A \"quoted\" name");
      assertThat(r.issns()).containsExactly("1175-5326", "0002-936X");
      assertThat(r.oaiUrl()).isNull();
      assertThat(r.country()).isNull();
      assertThat(r.totalRecords()).isNull();
      assertThat(r.records2025()).isEqualTo(7);
      assertThat(r.lastOaiResponse()).isNull();
      assertThat(r.unresponsive()).isTrue(); // the context flag counts too
    }
  }
}
