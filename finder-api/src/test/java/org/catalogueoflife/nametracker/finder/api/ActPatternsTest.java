package org.catalogueoflife.nametracker.finder.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActPatternsTest {
  private static List<ActType> types(String s) {
    return ActPatterns.find(s).stream().map(ActPatterns.ActMatch::type).toList();
  }

  @Test
  void flexibleSpellings() {
    for (String s : new String[] {"sp. nov.", "sp.nov.", "sp. n.", "sp nov", "SP. NOV.", "n.sp.", "n. sp", "sp.  nov"})
      assertThat(types("Foo bar " + s + " is")).as(s).containsExactly(ActType.SP_NOV);
  }

  @Test
  void wholeWordsOnly() {
    assertThat(types("collected near Novosibirsk, nov. 2019")).isEmpty();
    assertThat(types("sp. nova")).isEmpty();
    assertThat(types("Foo gen. novelty")).isEmpty();
  }

  @Test
  void longestWins() {
    assertThat(types("Foo bar subsp. nov.")).containsExactly(ActType.SSP_NOV);
    assertThat(types("Foo subfam. nov.")).containsExactly(ActType.FAM_NOV);
    assertThat(types("subgen. nov.")).containsExactly(ActType.SUBGEN_NOV);
    assertThat(types("a new synonymy here")).containsExactly(ActType.SYN_NOV);
    assertThat(types("Foo gen. nov. et sp. nov.")).containsExactly(ActType.GEN_NOV, ActType.SP_NOV);
  }

  @Test
  void multilingualAndOffsets() {
    assertThat(types("una nueva especie de")).containsExactly(ActType.SP_NOV);
    assertThat(types("eine neue Art")).containsExactly(ActType.SP_NOV);
    assertThat(types("nouveau genre")).containsExactly(ActType.GEN_NOV);
    assertThat(types("lectotype here designated")).containsExactly(ActType.TYPE_DESIGNATION);
    var m = ActPatterns.find("🐛 Foo bar sp. nov.").get(0);
    assertThat(m.start()).isEqualTo(10); // code points, not chars
    assertThat(m.end()).isEqualTo(18);
  }

  @Test
  void classifyStatusTexts() {
    assertThat(ActPatterns.classify("sp. n.")).contains(ActType.SP_NOV);
    assertThat(ActPatterns.classify("comb. nov.")).contains(ActType.COMB_NOV);
    assertThat(ActPatterns.classify("stat. rev.")).contains(ActType.STAT_REV);
    assertThat(ActPatterns.classify(" Sp. Nov. ;")).contains(ActType.SP_NOV);
    assertThat(ActPatterns.classify("subsp. nov.,")).contains(ActType.SSP_NOV);
    assertThat(ActPatterns.classify("gen. nov. et sp. nov.")).contains(ActType.SP_NOV);
    assertThat(ActPatterns.classify("gen. et sp. nov.")).contains(ActType.SP_NOV);
    assertThat(ActPatterns.classify("comb. et stat. nov.")).contains(ActType.COMB_NOV);
    assertThat(ActPatterns.classify("rediscovered")).isEmpty();
    assertThat(ActPatterns.classify(null)).isEmpty();
  }

  @Test
  void everyMarkerInTheTableMatchesItsType() {
    ActPatterns.markers().forEach((type, list) -> {
      for (String marker : list) {
        for (String variant : new String[] {marker, marker.toUpperCase(java.util.Locale.ROOT), marker.replace(".", "")}) {
          var found = ActPatterns.find("Foo bar " + variant + " is");
          assertThat(found).as(variant).hasSize(1);
          assertThat(found.get(0).type()).as(variant).isEqualTo(type);
          assertThat(found.get(0).marker()).isEqualToIgnoringCase(variant);
        }
      }
    });
  }

  @Test
  void accentedLetterAdjacentToMarkerBoundaryBlocksMatch() {
    assertThat(types("Foo bar \u00e9sp. nov.")).isEmpty();
    assertThat(types("Foo bar sp. nov\u00e9")).isEmpty();
    assertThat(types("Foo bar nova esp\u00e9cie\u00e9")).isEmpty();
    assertThat(types("Foo bar \u00e9nova esp\u00e9cie")).isEmpty();
    assertThat(types("Foo bar (nova esp\u00e9cie)")).containsExactly(ActType.SP_NOV);
  }

  @Test
  void compoundInRunningTextIsOneMatchCoveringTheWholeCompound() {
    String text = "We describe Aus bus comb. et stat. nov. here";
    var found = ActPatterns.find(text);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).type()).isEqualTo(ActType.COMB_NOV);
    assertThat(CodePoints.slice(text, found.get(0).start(), found.get(0).end())).isEqualTo("comb. et stat. nov.");
    assertThat(ActPatterns.classify("comb. et stat. nov.")).contains(found.get(0).type());

    var gs = ActPatterns.find("\ud83d\udc1b Foo bar gen. et sp. nov.");
    assertThat(gs).hasSize(1);
    assertThat(gs.get(0).type()).isEqualTo(ActType.SP_NOV);
    assertThat(gs.get(0).start()).isEqualTo(10);
    assertThat(gs.get(0).end()).isEqualTo(26);
  }

  @Test
  void compoundWithDottedEt() {
    // dev: "Paramphibambusa bambusicola gen. et. sp. nov."
    String text = "Paramphibambusa bambusicola gen. et. sp. nov., and Aus bus comb. et. stat. nov.";
    var found = ActPatterns.find(text);
    assertThat(found).extracting(ActPatterns.ActMatch::type).containsExactly(ActType.SP_NOV, ActType.COMB_NOV);
    assertThat(CodePoints.slice(text, found.get(0).start(), found.get(0).end())).isEqualTo("gen. et. sp. nov.");
    assertThat(ActPatterns.classify("gen. et. sp. nov.")).contains(ActType.SP_NOV);
  }

  @Test
  void unrelatedCommaIsNotACompound() {
    var found = ActPatterns.find("Foo bar, sp. nov.");
    assertThat(found).hasSize(1);
    assertThat(found.get(0).marker()).isEqualTo("sp. nov.");
  }

  @Test
  void higherRankActs() {
    for (String s : new String[] {"ord. nov.", "ord. n.", "subord. nov.", "infraord. n.", "superord. nov.", "cl. nov.", "cl. n.",
        "subcl. n.", "infracl. nov.", "phyl. nov.", "phyl. n.", "subphyl. n.", "infraphyl. nov.", "superphyl. n.", "regn. nov.",
        "subregn. n.", "div. nov.", "new order", "new class", "new phylum"})
      assertThat(types("Foo " + s + " is")).as(s).containsExactly(ActType.HIGHER_NOV);
    // family-group markers stay FAM_NOV
    assertThat(types("Fooidae fam. nov.")).containsExactly(ActType.FAM_NOV);
  }

  @Test
  void leadingNForms() {
    assertThat(types("Foo bar (Smith) n. comb.")).containsExactly(ActType.COMB_NOV);
    assertThat(types("= Foo baz n. syn.")).containsExactly(ActType.SYN_NOV);
    assertThat(types("Foo n. stat.")).containsExactly(ActType.STAT_NOV);
  }
}
