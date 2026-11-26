package org.catalogueoflife.garrulus.finder.dict;

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
  void genusEpithetPairsFromSpeciesAndInfraspecificRows() throws IOException {
    ColDictionary d = sample();
    assertThat(d.isBinomial("Carabus", "nigrus")).isTrue();
    assertThat(d.isBinomial("Procarabus", "ater")).isTrue(); // from a synonym
    assertThat(d.isBinomial("Rosa", "canina")).isTrue();
    assertThat(d.isBinomial("Major", "minor")).isTrue();
    assertThat(d.isBinomial("Carabus", "Nigrus")).isTrue(); // the epithet is lower-cased
    assertThat(d.isBinomial("Carabus", "ater")).isFalse(); // both words known, the pair is not
    assertThat(d.isBinomial("Carabus", "montanus")).isFalse(); // an infraspecific epithet pairs with nothing
    assertThat(d.isBinomial("carabus", "nigrus")).isFalse(); // the genus is case-sensitive
    assertThat(d.isBinomial("Carabus", null)).isFalse();
    // a variety row alone gives the pair of its species
    ColDictionary v = ColDictionaryBuilder.fromNameUsageTsv(new java.io.StringReader(
        "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
            + "1\tvariety\t\tAus\tbus\tcus\n"), "1", CommonWords.of(java.util.Set.of()));
    assertThat(v.isBinomial("Aus", "bus")).isTrue();
    assertThat(v.pairCount()).isEqualTo(1);
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
