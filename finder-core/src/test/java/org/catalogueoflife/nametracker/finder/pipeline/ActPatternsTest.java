package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.ActType;
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
}
