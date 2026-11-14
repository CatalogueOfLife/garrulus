package org.catalogueoflife.nametracker.eval.corpus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusStoreTest {
  @TempDir Path dir;

  private GoldDocument doc() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/zookeys-218447-excerpt.xml")) {
      return new TaxPubGoldExtractor().extract(in, "pensoft:x", "test");
    }
  }

  @Test
  void roundTripWithText() throws Exception {
    GoldDocument g = doc();
    Path f = dir.resolve("gold.jsonl.gz");
    CorpusStore.write(f, List.of(g));
    assertThat(CorpusStore.read(f, null)).containsExactly(g);
  }

  @Test
  void refetchRestoresText() throws Exception {
    GoldDocument g = doc();
    Path f = dir.resolve("gold.jsonl.gz");
    GoldDocument stripped = g.withoutText();
    assertThat(stripped.document().blocks()).allSatisfy(b -> assertThat(b.text()).isEmpty());
    assertThat(stripped.document().blocks()).extracting(b -> b.id()).isEqualTo(g.document().blocks().stream().map(b -> b.id()).toList());
    CorpusStore.write(f, List.of(stripped));
    GoldDocument back = CorpusStore.read(f, s -> CorpusStore.flat(g)).get(0);
    assertThat(back.document().blocks()).isEqualTo(g.document().blocks());
    assertThat(back.textRetained()).isTrue();
  }

  @Test
  void refetchMismatchFails() throws Exception {
    Path f = dir.resolve("gold.jsonl.gz");
    CorpusStore.write(f, List.of(doc().withoutText()));
    assertThatThrownBy(() -> CorpusStore.read(f, s -> "something else"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("pensoft:x");
    assertThatThrownBy(() -> CorpusStore.read(f, null)).hasMessageContaining("pensoft:x");
  }
}
