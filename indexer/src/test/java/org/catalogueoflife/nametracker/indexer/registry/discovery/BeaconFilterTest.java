package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BeaconFilterTest {

  static final Clock NOW = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC);
  final BeaconFilter f = BeaconFilter.defaults(NOW);

  static BeaconRow row(String name, Integer rec, String last, boolean unresponsive) {
    return new BeaconRow(name, List.of("1175-5326"), null, null, null, null, rec,
        last == null ? null : Instant.parse(last), unresponsive);
  }

  @Test
  void topicTermsMatchAtWordStartAfterFolding() {
    assertThat(f.accepts(row("Revista de Entomología", 9, null, false))).isTrue();
    assertThat(f.accepts(row("BOTÁNICA hoy", 9, null, false))).isTrue();
    assertThat(f.accepts(row("Histoire Naturelle du Maroc", 9, null, false))).isTrue();
    assertThat(f.accepts(row("Mastozoología", 9, null, false))).isFalse(); // mid-word
    assertThat(f.accepts(row("Journal of Economics", 9, null, false))).isFalse();
  }

  @Test
  void excludedWordsWin() {
    assertThat(f.accepts(row("Veterinary Parasitology", 9, null, false))).isFalse();
    assertThat(f.accepts(row("Museum Studies Quarterly", 9, null, false))).isFalse();
    assertThat(f.accepts(row("Museología", 9, null, false))).isFalse();
    assertThat(f.accepts(row("Medical Entomology", 9, null, false))).isFalse();
  }

  @Test
  void activityNeedsRecordsOrRecentResponseAndAResponsiveEndpoint() {
    assertThat(f.accepts(row("Zoology", 5, null, false))).isTrue();
    assertThat(f.accepts(row("Zoology", 4, null, false))).isFalse();
    assertThat(f.accepts(row("Zoology", 0, "2026-06-01T00:00:00Z", false))).isTrue(); // Zootaxa reports 0
    assertThat(f.accepts(row("Zoology", 0, "2025-10-01T00:00:00Z", false))).isFalse(); // older than 365 days
    assertThat(f.accepts(row("Zoology", null, "2026-06-01T00:00:00Z", false))).isTrue();
    assertThat(f.accepts(row("Zoology", 50, "2026-06-01T00:00:00Z", true))).isFalse();
    assertThat(f.accepts(row(null, 50, null, false))).isFalse();
  }

  @Test
  void tunableLists() {
    BeaconFilter g = BeaconFilter.of(List.of("coral"), List.of("reef shop"), NOW);
    assertThat(g.accepts(row("Coral Research", 9, null, false))).isTrue();
    assertThat(g.accepts(row("Zoology", 9, null, false))).isFalse();
    assertThat(g.accepts(row("Coral reef shop", 9, null, false))).isFalse();
  }
}
