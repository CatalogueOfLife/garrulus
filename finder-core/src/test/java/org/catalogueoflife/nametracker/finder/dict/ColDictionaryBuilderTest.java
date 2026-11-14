package org.catalogueoflife.nametracker.finder.dict;

import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ColDictionaryBuilderTest {
  static ColDictionary sample() throws IOException {
    try (Reader r = new InputStreamReader(ColDictionaryBuilderTest.class.getResourceAsStream("/dict/NameUsage-sample.tsv"), StandardCharsets.UTF_8)) {
      return ColDictionaryBuilder.fromNameUsageTsv(r, "9999", CommonWords.load(Set.of("en")));
    }
  }

  @Test
  void buildsCategoriesFromFixture() throws IOException {
    ColDictionary d = sample();
    assertThat(d.releaseKey()).isEqualTo("9999");
    assertThat(d.isGenus("Carabus")).isTrue();
    assertThat(d.isGenus("Procarabus")).isTrue(); // from a synonym
    assertThat(d.isGenus("carabus")).isFalse(); // case-sensitive
    assertThat(d.isGenus("Carabidae")).isFalse();
    assertThat(d.isEpithet("nigrus")).isTrue();
    assertThat(d.isEpithet("Nigrus")).isTrue(); // epithets are lower-cased
    assertThat(d.isEpithet("montanus")).isTrue();
    assertThat(d.isEpithet("ater")).isTrue();
    assertThat(d.isEpithet("Carabus")).isFalse();
    assertThat(d.isUninomial("Carabidae")).isTrue();
    assertThat(d.uninomialRank("Carabidae")).contains(Rank.FAMILY);
    assertThat(d.uninomialRank("Rosaceae")).contains(Rank.FAMILY);
    assertThat(d.uninomialRank("Coleoptera")).contains(Rank.ORDER);
    assertThat(d.uninomialRank("Carabinae")).contains(Rank.SUBFAMILY);
    assertThat(d.uninomialRank("Carabus")).isEmpty();
    assertThat(d.isUninomial("Carabus")).isFalse();
    assertThat(d.isKnownWord("carabus")).isTrue();
    assertThat(d.isKnownWord("nigrus")).isTrue();
    assertThat(d.isKnownWord("rosaceae")).isTrue();
  }

  @Test
  void commonWordGenusIsAmbiguous() throws IOException {
    ColDictionary d = sample();
    assertThat(d.isGenus("Major")).isTrue();
    assertThat(d.isAmbiguousWord("Major")).isTrue();
    assertThat(d.isAmbiguousWord("Carabus")).isFalse();
  }

  @Test
  void unknownWordIsNotKnown() throws IOException {
    ColDictionary d = sample();
    assertThat(d.isKnownWord("zzzqqq")).isFalse();
    assertThat(d.isGenus("Zzzqqq")).isFalse();
    assertThat(d.isEpithet("zzzqqq")).isFalse();
    assertThat(d.uninomialRank("Zzzqqq")).isEmpty();
    assertThat(d.isKnownWord("")).isFalse();
  }

  @Test
  void releaseKeyFromMetadata() {
    assertThat(ColDictionaryBuilder.releaseKeyFromYaml("title: x\nkey: 12345\nversion: '2026-10-22'\n")).isEqualTo("12345");
    assertThat(ColDictionaryBuilder.releaseKeyFromYaml("title: x\nversion: \"2026-10-22\"\n  key: 5\n")).isEqualTo("2026-10-22");
  }
}
