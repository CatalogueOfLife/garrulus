package org.catalogueoflife.nametracker.finder.dict;

import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ColDictionaryIOTest {
  @Test
  void writeReadRoundTrip(@TempDir Path dir) throws IOException {
    ColDictionary d = ColDictionaryBuilderTest.sample();
    Path f = dir.resolve("dictionary-9999.bin");
    ColDictionaryIO.write(d, f);
    ColDictionary r = ColDictionaryIO.read(f);
    assertThat(r.releaseKey()).isEqualTo("9999");
    assertThat(r.stats()).isEqualTo(d.stats());
    assertThat(r.isGenus("Carabus")).isTrue();
    assertThat(r.isEpithet("nigrus")).isTrue();
    assertThat(r.uninomialRank("Rosaceae")).contains(Rank.FAMILY);
    assertThat(r.isAmbiguousWord("Major")).isTrue();
    assertThat(r.isKnownWord("zzzqqq")).isFalse();
  }
}
