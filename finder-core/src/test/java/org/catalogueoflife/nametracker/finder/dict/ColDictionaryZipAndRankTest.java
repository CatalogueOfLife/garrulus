package org.catalogueoflife.nametracker.finder.dict;

import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColDictionaryZipAndRankTest {
  private static final String HEADER = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n";
  private static final String TSV = HEADER + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n";
  private static final CommonWords COMMON = CommonWords.of(Set.of());

  private static byte[] zip(Map<String, String> entries) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ZipOutputStream z = new ZipOutputStream(bos)) {
      for (var e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
      }
    }
    return bos.toByteArray();
  }

  private static ColDictionary build(Map<String, String> entries) throws IOException {
    return ColDictionaryBuilder.fromXrZip(new ByteArrayInputStream(zip(entries)), COMMON);
  }

  @Test
  void metadataAfterNameUsage() throws IOException {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("NameUsage.tsv", TSV);
    m.put("metadata.yaml", "title: x\nkey: 42\nversion: v\n");
    ColDictionary d = build(m);
    assertThat(d.releaseKey()).isEqualTo("42");
    assertThat(d.isGenus("Carabus")).isTrue();
    assertThat(d.isEpithet("nigrus")).isTrue();
  }

  @Test
  void metadataBeforeNameUsage() throws IOException {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("metadata.yaml", "title: x\nkey: 42\n");
    m.put("Other.tsv", "ignored\n");
    m.put("NameUsage.tsv", TSV);
    ColDictionary d = build(m);
    assertThat(d.releaseKey()).isEqualTo("42");
    assertThat(d.isEpithet("nigrus")).isTrue();
  }

  @Test
  void fallsBackToVersion() throws IOException {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("metadata.yaml", "title: x\nversion: '2026-10-16 XR'\n");
    m.put("NameUsage.tsv", TSV);
    assertThat(build(m).releaseKey()).isEqualTo("2026-10-16 XR");
  }

  @Test
  void missingNameUsageIsAClearError() throws IOException {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("metadata.yaml", "key: 1\n");
    assertThatThrownBy(() -> build(m)).isInstanceOf(IOException.class).hasMessageContaining("NameUsage.tsv");
  }

  @Test
  void missingKeyIsAClearError() throws IOException {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("NameUsage.tsv", TSV);
    assertThatThrownBy(() -> build(m)).isInstanceOf(IOException.class).hasMessageContaining("key:");
  }

  private static ColDictionary ranks() throws IOException {
    String tsv = HEADER
        + "1\tfamily\tAaidae\t\t\t\n2\tfamily\tAaidae\t\t\t\n3\tsubfamily\tAaidae\t\t\t\n"   // 2:1 FAMILY
        + "4\torder\tBbales\t\t\t\n5\tclass\tBbales\t\t\t\n"                                // 1:1 tie, higher rank
        + "6\tclass\tCcales\t\t\t\n7\torder\tCcales\t\t\t\n";                              // same tie, other row order
    return ColDictionaryBuilder.fromNameUsageTsv(new StringReader(tsv), "r", COMMON);
  }

  private static void assertRanks(ColDictionary d) {
    assertThat(d.uninomialRank("Aaidae")).contains(Rank.FAMILY);
    assertThat(d.uninomialRank("Bbales")).contains(Rank.CLASS);
    assertThat(d.uninomialRank("Ccales")).contains(Rank.CLASS);
  }

  @Test
  void mostFrequentRankWinsAndTiesGoToHigherRank(@TempDir Path dir) throws IOException {
    ColDictionary d = ranks();
    assertRanks(d);
    Path f = dir.resolve("d.bin");
    ColDictionaryIO.write(d, f);
    assertRanks(ColDictionaryIO.read(f));
  }

  @Test
  void badMagicAndTruncationAreReported(@TempDir Path dir) throws IOException {
    Path bad = dir.resolve("bad.bin");
    Files.write(bad, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
    assertThatThrownBy(() -> ColDictionaryIO.read(bad)).isInstanceOf(IOException.class).hasMessageContaining("bad magic").hasMessageContaining("bad.bin");

    Path good = dir.resolve("good.bin");
    ColDictionaryIO.write(ColDictionaryBuilderTest.sample(), good);
    byte[] all = Files.readAllBytes(good);
    Path cut = dir.resolve("cut.bin");
    Files.write(cut, java.util.Arrays.copyOf(all, all.length - 20));
    assertThatThrownBy(() -> ColDictionaryIO.read(cut)).isInstanceOf(IOException.class).hasMessageContaining("cut.bin")
        .hasMessageMatching(".*(truncated|corrupt).*");
  }
}
