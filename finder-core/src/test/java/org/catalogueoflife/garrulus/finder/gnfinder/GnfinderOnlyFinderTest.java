package org.catalogueoflife.garrulus.finder.gnfinder;

import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The finder over a client that returns the recorded sample response (the override seam of GnfinderClient). */
class GnfinderOnlyFinderTest {
  private static final String TEXT =
      "A new species of Carabus (Coleoptera: Carabidae), Carabus nigrus sp. nov., is described. C. nigrus differs from Pterostichus niger.";

  @Test
  void mapsCandidatesToMentionsAndGnfindersOwnActs() throws IOException {
    GnfinderResponse recorded;
    try (var in = getClass().getResourceAsStream("/gnfinder/sample.json")) {
      recorded = FinderJson.mapper().readValue(in, GnfinderResponse.class);
    }
    GnfinderClient stub = new GnfinderClient(URI.create("http://unused")) {
      @Override
      public GnfinderResponse find(String text) {
        return recorded;
      }
    };
    Document d = new Document("d", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, TEXT, List.of(), List.of())), Map.of());
    FinderResult r = new GnfinderOnlyFinder(stub).find(d, FinderOptions.defaults());
    assertThat(r.mentions()).extracting(Mention::verbatim)
        .containsExactly("Carabus", "Coleoptera", "Carabidae", "Carabus nigrus", "C. nigrus", "Pterostichus niger");
    assertThat(r.mentions()).extracting(Mention::kind).containsExactly(NameKind.UNINOMIAL, NameKind.UNINOMIAL, NameKind.UNINOMIAL,
        NameKind.BINOMIAL, NameKind.ABBREVIATED, NameKind.BINOMIAL);
    assertThat(r.mentions().get(3).confidence()).isEqualTo(1.0);
    assertThat(r.mentions().get(0).confidence()).isBetween(0.4, 0.5);
    // gnfinder hangs "sp. nov." on three names, among them the higher taxa: the baseline keeps that on purpose
    assertThat(r.acts()).hasSize(3).allSatisfy(a -> assertThat(a.type()).isEqualTo(ActType.SP_NOV));
    assertThat(r.mentions().get(1).actId()).isNotNull();
    assertThat(r.mentions().get(0).actId()).isNull();
  }

  @Test
  void nullOddsReadAsZeroAndNullNamesAsEmpty() throws IOException {
    GnfinderResponse r = FinderJson.mapper().readValue(
        "{\"names\":[{\"cardinality\":1,\"verbatim\":\"Abc\",\"name\":\"Abc\",\"oddsLog10\":null,\"start\":0,\"end\":3,\"annotationNomenType\":\"NO_ANNOT\"}]}",
        GnfinderResponse.class);
    assertThat(r.names().get(0).oddsLog10()).isZero();
    assertThat(FinderJson.mapper().readValue("{\"names\":null}", GnfinderResponse.class).names()).isEmpty();
  }
}
