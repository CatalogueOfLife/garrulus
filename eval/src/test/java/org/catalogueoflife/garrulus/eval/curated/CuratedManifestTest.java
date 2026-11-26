package org.catalogueoflife.garrulus.eval.curated;

import org.catalogueoflife.garrulus.eval.corpus.GoldDocument;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CuratedManifestTest {
  private static GoldDocument gold(String id, String sha) {
    return new GoldDocument(id, "curated", "dev", "CC-BY", true, sha, new Document(id, "en", List.of(), Map.of()),
        List.of(), List.of(), List.of(), Set.of());
  }

  private static final CuratedManifest MANIFEST = new CuratedManifest(List.of(
      new CuratedManifest.Row("openalex:W1", "BROAD_EN", "dev", "en", "CC-BY", true, "10.1/x", "https://openalex.org/W1", "abc")));

  @Test
  void matchingHashPasses() {
    MANIFEST.check(List.of(gold("openalex:W1", "abc")));
  }

  @Test
  void hashMismatchAndUnknownDocIdFailByName() {
    assertThatThrownBy(() -> MANIFEST.check(List.of(gold("openalex:W1", "zzz"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("openalex:W1").hasMessageContaining("abc");
    assertThatThrownBy(() -> MANIFEST.check(List.of(gold("openalex:W2", "abc"))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("openalex:W2").hasMessageContaining("not in");
  }
}
