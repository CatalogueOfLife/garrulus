package org.catalogueoflife.nametracker.eval.corpus;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusRebuilderTest {
  private static final String SUBGENUS = "<tp:taxon-name><tp:taxon-name-part taxon-name-part-type=\"genus\" reg=\"Crematogaster\">"
      + "Crematogaster</tp:taxon-name-part> (<tp:taxon-name-part taxon-name-part-type=\"subgenus\" reg=\"Oxygyne\">Oxygyne"
      + "</tp:taxon-name-part>)</tp:taxon-name>";
  private static final String GENUS = "<tp:taxon-name><tp:taxon-name-part taxon-name-part-type=\"genus\" reg=\"Dorylus\">Dorylus"
      + "</tp:taxon-name-part></tp:taxon-name>";

  private static GoldDocument gold(CorpusManifest.Row row, String body) {
    String xml = "<article xmlns:tp=\"http://www.plazi.org/taxpub\"><body><p>" + body + "</p></body></article>";
    return new TaxPubGoldExtractor().extract(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), row.docId(),
        row.split(), row.license());
  }

  /** The gold as the old extractor built it: the closing parenthesis of the subgenus cut off. */
  private static GoldDocument truncateSubgenus(GoldDocument g) {
    List<GoldName> names = g.names().stream().map(n -> n.text().equals("Crematogaster (Oxygyne)")
        ? new GoldName(n.blockId(), n.start(), n.end() - 1, "Crematogaster (Oxygyne", n.fullName(), n.rank()) : n).toList();
    return new GoldDocument(g.docId(), g.corpus(), g.split(), g.license(), g.textRetained(), g.textSha256(), g.document(), names,
        g.acts(), g.contexts(), g.mainSubjectNames());
  }

  private static CorpusManifest.Row row(String id, String split, String license) {
    return new CorpusManifest.Row("pensoft:" + id, "10.3897/" + id, "zookeys", license, split, "https://x/" + id, "https://x/" + id + ".pdf");
  }

  @Test
  void rebuildsInManifestOrderKeepingSplitsLicencesAndFailedDocuments() {
    CorpusManifest.Row a = row("zookeys.1.1", "dev", "CC-BY");
    CorpusManifest.Row b = row("zookeys.1.2", "test", "CC0");
    CorpusManifest.Row c = row("zookeys.1.3", "dev", "CC-BY");
    // the old corpus: a has a truncated name and one genus less, b is unchanged, c will fail to download
    GoldDocument oldA = truncateSubgenus(gold(a, "the subgenus " + SUBGENUS + " and " + GENUS));
    GoldDocument oldB = gold(b, "only " + GENUS);
    GoldDocument oldC = gold(c, "old " + GENUS);
    List<String> log = new ArrayList<>();

    CorpusRebuilder.Result r = CorpusRebuilder.rebuild(new CorpusManifest(List.of(a, b, c)), List.of(oldC, oldB, oldA), row -> {
      if (row == c) throw new IllegalStateException("HTTP 503 for " + row.xmlUrl());
      return gold(row, row == a ? "the subgenus " + SUBGENUS + " and " + GENUS + " and " + GENUS : "only " + GENUS);
    }, log::add);

    assertThat(r.documents()).extracting(GoldDocument::docId).containsExactly(a.docId(), b.docId(), c.docId());
    assertThat(r.documents()).extracting(GoldDocument::split).containsExactly("dev", "test", "dev");
    assertThat(r.documents()).extracting(GoldDocument::license).containsExactly("CC-BY", "CC0", "CC-BY");
    assertThat(r.documents().get(0).names()).extracting(GoldName::text)
        .containsExactly("Crematogaster (Oxygyne)", "Dorylus", "Dorylus");
    assertThat(r.documents().get(1)).isEqualTo(oldB);
    assertThat(r.documents().get(2)).isSameAs(oldC);
    assertThat(log).anyMatch(l -> l.contains("HTTP 503") && l.contains("keeping its previous version"));

    CorpusRebuilder.Stats s = r.stats();
    assertThat(s.documents()).isEqualTo(3);
    assertThat(s.rebuilt()).isEqualTo(2);
    assertThat(s.keptOld()).isEqualTo(1);
    assertThat(s.failed()).containsExactly(c.docId());
    assertThat(s.documentsChanged()).isEqualTo(1);
    assertThat(s.textChanged()).isEqualTo(1);
    assertThat(s.oldNames()).isEqualTo(4);
    assertThat(s.newNames()).isEqualTo(5);
    assertThat(s.namesChanged()).isEqualTo(1);
    assertThat(s.namesAdded()).isEqualTo(1);
    assertThat(s.namesRemoved()).isZero();
  }
}
