package org.catalogueoflife.nametracker.indexer.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.catalogueoflife.nametracker.store.index.NameString;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlacerTest {

  static ClbMatch fixture(String name) throws IOException {
    return ClbMatch.parse(JsonMapper.builder().build().readTree(ClbClientTest.fixture(name)));
  }

  static NameString ns(String name, String kind, String rank) {
    return new NameString(1L, name, kind, rank);
  }

  static final ClbMatch NONE = new ClbMatch("none", null, null, null, null, null, null, List.of(), List.of());

  @Test
  void exactOrVariantOrCanonicalIsMatch() throws Exception {
    for (String type : List.of("exact", "variant", "canonical")) {
      ClbMatch f = fixture("match-carabidae.json");
      ClbMatch m = new ClbMatch(type, f.usageId(), f.usageName(), f.usageRank(), f.usageStatus(), f.acceptedId(),
          f.code(), f.taxonPath(), f.classification());
      Placement p = Placer.place(ns("Carabidae", "UNINOMIAL", "FAMILY"), m, null);
      assertThat(p.method()).isEqualTo("MATCH");
      assertThat(p.usageId()).isEqualTo("8KTL7");
      assertThat(p.taxonPath()).startsWith("8KTL7", "DDH7R", "C2L");
      assertThat(p.code()).isEqualTo("ZOOLOGICAL");
      assertThat(p.taxgroup()).isEqualTo("Coleoptera");
    }
  }

  @Test
  void higherrankToGenusIsGenus() throws Exception {
    Placement p = Placer.place(ns("Carabus xyzzyus", "BINOMIAL", "SPECIES"), fixture("match-carabus-xyzzyus.json"),
        null);
    assertThat(p.method()).isEqualTo("GENUS");
    assertThat(p.usageId()).isEqualTo("8KVGQ");
    assertThat(p.taxonPath()).startsWith("8KVGQ", "8LTMH", "8KTT7");
    assertThat(p.taxgroup()).isEqualTo("Coleoptera");
  }

  @Test
  void higherrankToGenusOfAnAbbreviatedNameIsGenus() throws Exception {
    // "U. microps sp. nov.", expanded to Uenoites microps: a new species lands on its genus, not on the context
    ClbMatch ctx = fixture("match-carabidae.json");
    Placement p = Placer.place(ns("Carabus xyzzyus", "ABBREVIATED", "SPECIES"), fixture("match-carabus-xyzzyus.json"),
        List.of(ctx));
    assertThat(p.method()).isEqualTo("GENUS");
    assertThat(p.usageId()).isEqualTo("8KVGQ");
    assertThat(Placer.needsContext(ns("Carabus xyzzyus", "ABBREVIATED", "SPECIES"),
        fixture("match-carabus-xyzzyus.json"))).isFalse();
  }

  @Test
  void higherrankToGenusNeedsABinomialKind() throws Exception {
    Placement p = Placer.place(ns("Carabus", "UNINOMIAL", "GENUS"), fixture("match-carabus-xyzzyus.json"), List.of());
    assertThat(p.method()).isEqualTo("NONE");
  }

  @Test
  void higherrankAboveGenusUsesContext() throws Exception {
    ClbMatch f = fixture("match-carabidae.json");
    ClbMatch above = new ClbMatch("higherrank", f.usageId(), f.usageName(), f.usageRank(), f.usageStatus(),
        f.acceptedId(), f.code(), f.taxonPath(), f.classification());
    assertThat(Placer.place(ns("Xus yus", "BINOMIAL", "SPECIES"), above, null).method()).isEqualTo("NONE");
    Placement p = Placer.place(ns("Xus yus", "BINOMIAL", "SPECIES"), above, List.of(fixture("match-coleoptera.json")));
    assertThat(p.method()).isEqualTo("VERBATIM_CONTEXT");
    assertThat(p.usageId()).isEqualTo("C2L");
  }

  @Test
  void noneWithContextUsesLowestMatchedContextTaxon() throws Exception {
    Placement p = Placer.place(ns("Xyzzy foo", "BINOMIAL", "SPECIES"), NONE,
        List.of(fixture("match-coleoptera.json"), NONE, fixture("match-carabidae.json")));
    assertThat(p.method()).isEqualTo("VERBATIM_CONTEXT");
    assertThat(p.usageId()).isEqualTo("8KTL7");
    assertThat(p.taxonPath()).startsWith("8KTL7", "DDH7R", "C2L");
    assertThat(p.taxgroup()).isEqualTo("Coleoptera");
  }

  @Test
  void noneWithoutContextIsNone() {
    for (List<ClbMatch> ctx : java.util.Arrays.asList(null, List.<ClbMatch>of(), List.of(NONE))) {
      Placement p = Placer.place(ns("Xyzzy foo", "BINOMIAL", "SPECIES"), NONE, ctx);
      assertThat(p.method()).isEqualTo("NONE");
      assertThat(p.usageId()).isNull();
      assertThat(p.taxonPath()).isEmpty();
    }
  }

  @Test
  void ambiguousIsNoneUnlessContext() throws Exception {
    ClbMatch amb = new ClbMatch("ambiguous", null, null, null, null, null, null, List.of(), List.of());
    assertThat(Placer.place(ns("Aus bus", "BINOMIAL", "SPECIES"), amb, null).method()).isEqualTo("NONE");
    assertThat(Placer.place(ns("Aus bus", "BINOMIAL", "SPECIES"), amb, List.of(fixture("match-carabidae.json")))
        .method()).isEqualTo("VERBATIM_CONTEXT");
  }

  @Test
  void otuIsNeverMatched() throws Exception {
    assertThat(Placer.needsMatch(ns("BOLD:AAA0001", "OTU", null))).isFalse();
    assertThat(Placer.needsMatch(ns("Carabus nemoralis", "BINOMIAL", "SPECIES"))).isTrue();
    Placement p = Placer.place(ns("BOLD:AAA0001", "OTU", null), fixture("match-carabidae.json"),
        List.of(fixture("match-carabidae.json")));
    assertThat(p.method()).isEqualTo("NONE");
  }

  @Test
  void unrankedContextMatchDoesNotBeatARankedOne() throws Exception {
    ClbMatch f = fixture("match-coleoptera.json");
    ClbMatch unranked = new ClbMatch("exact", "U1", "Weird", "unranked", "accepted", "U1", "ZOOLOGICAL",
        List.of("U1"), List.of());
    Placement p = Placer.place(ns("Xyzzy foo", "BINOMIAL", "SPECIES"), NONE,
        List.of(f, unranked));
    assertThat(p.usageId()).isEqualTo("C2L");
    assertThat(Placer.place(ns("Xyzzy foo", "BINOMIAL", "SPECIES"), NONE, List.of(unranked)).usageId())
        .isEqualTo("U1");
  }
}
