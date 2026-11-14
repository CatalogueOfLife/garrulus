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
  void mainSubjectNamesAreWrittenSortedSoTheFileIsReproducible() throws Exception {
    GoldDocument g = doc();
    java.util.Set<String> subjects = new java.util.LinkedHashSet<>(List.of("Ophelia yangi", "Annelida", "Ophelia", "Carabus"));
    GoldDocument d = new GoldDocument(g.docId(), g.corpus(), g.split(), g.license(), g.textRetained(), g.textSha256(),
        g.document(), g.names(), g.acts(), g.contexts(), subjects);
    assertThat(d.mainSubjectNames()).containsExactly("Annelida", "Carabus", "Ophelia", "Ophelia yangi");
    assertThat(org.catalogueoflife.nametracker.finder.api.FinderJson.mapper().writeValueAsString(d))
        .contains("\"mainSubjectNames\":[\"Annelida\",\"Carabus\",\"Ophelia\",\"Ophelia yangi\"]");
    Path a = dir.resolve("a.jsonl.gz");
    Path b = dir.resolve("b.jsonl.gz");
    CorpusStore.write(a, List.of(d));
    CorpusStore.write(b, List.of(new GoldDocument(g.docId(), g.corpus(), g.split(), g.license(), g.textRetained(),
        g.textSha256(), g.document(), g.names(), g.acts(), g.contexts(), java.util.Set.copyOf(subjects))));
    assertThat(java.nio.file.Files.readAllBytes(a)).isEqualTo(java.nio.file.Files.readAllBytes(b));
    assertThat(CorpusStore.read(a, null).get(0).mainSubjectNames()).containsExactly("Annelida", "Carabus", "Ophelia", "Ophelia yangi");
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

  @Test
  void failedWriteLeavesTheOldFileIntactAndNoTempFile() throws Exception {
    GoldDocument g = doc();
    Path f = dir.resolve("gold.jsonl.gz");
    CorpusStore.write(f, List.of(g.withoutText(), g.withoutText()));
    byte[] before = java.nio.file.Files.readAllBytes(f);
    // a source that fails after the first document breaks the write in the middle
    List<GoldDocument> failing = new java.util.AbstractList<>() {
      @Override public GoldDocument get(int i) {
        if (i > 0) throw new IllegalStateException("boom");
        return g;
      }
      @Override public int size() { return 2; }
    };
    assertThatThrownBy(() -> CorpusStore.write(f, failing)).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    assertThat(java.nio.file.Files.readAllBytes(f)).isEqualTo(before);
    try (var files = java.nio.file.Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("gold.jsonl.gz");
    }
  }
}
