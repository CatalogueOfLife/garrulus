package org.catalogueoflife.nametracker.indexer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class MatchQueryTest {

  private static void roundTrip(MatchQuery q) {
    assertThat(MatchQuery.fromHintKey(q.name(), q.rank(), q.hintKey())).isEqualTo(q);
  }

  @Test
  void hintKeyRoundTrip() {
    TreeMap<String, String> hints = new TreeMap<>(Map.of("family", "Carabidae", "genus", "Carabus"));
    roundTrip(new MatchQuery("Carabus auratus", "SPECIES", "L. | Fabr.", hints));
    roundTrip(new MatchQuery("Carabus auratus", null, null, hints));
    roundTrip(new MatchQuery("Carabus auratus", "SPECIES", "L. | Fabr.", new TreeMap<>()));
    roundTrip(new MatchQuery("Carabus auratus", null, "L.", new TreeMap<>()));
    roundTrip(new MatchQuery("Carabus auratus", null, null, new TreeMap<>()));
    assertThat(MatchQuery.fromHintKey("Carabus", null, "").hints()).isEmpty();
  }
}
