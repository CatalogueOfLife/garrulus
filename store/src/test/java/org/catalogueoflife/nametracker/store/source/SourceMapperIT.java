package org.catalogueoflife.nametracker.store.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SourceMapperIT extends AbstractPostgresIT {

  @Autowired SourceMapper sources;

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
}
