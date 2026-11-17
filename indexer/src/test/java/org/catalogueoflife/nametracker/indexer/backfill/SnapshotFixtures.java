package org.catalogueoflife.nametracker.indexer.backfill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Tiny OpenAlex snapshot files built in the tests: a manifest, gzip JSON lines and work records. */
final class SnapshotFixtures {

  private SnapshotFixtures() {
  }

  /** The gzip of the lines, each ended by a newline. */
  static byte[] gzip(List<String> lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      for (String l : lines) gz.write((l + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** The first {@code share} of the bytes: a gzip stream cut off in the middle. */
  static byte[] cut(byte[] gzip, double share) {
    return Arrays.copyOf(gzip, (int) (gzip.length * share));
  }

  /**
   * A snapshot work record with more fields than the API select (unknown ones included). A null source has no
   * primary_location; a null year no publication_year.
   */
  static String record(String id, String source, Integer year, String type, boolean tei) {
    String loc = source == null ? "" : "\"primary_location\":{\"is_oa\":true,\"source\":{\"id\":"
        + "\"https://openalex.org/" + source + "\",\"display_name\":\"Journal " + source + "\",\"type\":\"journal\"}},";
    String y = year == null ? "" : "\"publication_year\":" + year + ",\"publication_date\":\"" + year + "-03-01\",";
    return "{\"id\":\"https://openalex.org/" + id + "\",\"doi\":\"https://doi.org/10.5555/" + id.toLowerCase()
        + "\",\"title\":\"Title of " + id + " &amp; more\"," + y + "\"type\":\"" + type + "\"," + loc
        + "\"abstract_inverted_index\":{\"An\":[0],\"abstract.\":[1]},"
        + "\"authorships\":[{\"author\":{\"display_name\":\"Ann Author\",\"orcid\":null},\"countries\":[\"DE\"]}],"
        + "\"open_access\":{\"is_oa\":true,\"oa_status\":\"gold\",\"oa_url\":null},"
        + "\"has_content\":{\"grobid_xml\":" + tei + ",\"pdf\":false},"
        + "\"concepts\":[{\"id\":\"C1\",\"score\":0.5}],\"updated_date\":\"2026-10-01T00:00:00\",\"cited_by_count\":3}";
  }

  static String record(String id, String source, Integer year, String type) {
    return record(id, source, year, type, false);
  }

  /** A works file of a manifest: its path in the bucket and its record count. */
  record Entry(String path, long records) {
  }

  /** A manifest with the given works file paths (relative to the bucket), record counts 10, 11, …. */
  static String manifest(String date, String... worksPaths) {
    Entry[] entries = new Entry[worksPaths.length];
    for (int i = 0; i < worksPaths.length; i++) entries[i] = new Entry(worksPaths[i], 10 + i);
    return manifest(date, entries);
  }

  /** A manifest with the given works files and an authors entity besides; content lengths 1000, 1001, …. */
  static String manifest(String date, Entry... worksFiles) {
    StringBuilder files = new StringBuilder();
    for (int i = 0; i < worksFiles.length; i++) {
      if (i > 0) files.append(',');
      files.append("{\"url\":\"s3://openalex/").append(worksFiles[i].path()).append("\",\"meta\":{\"content_length\":")
          .append(1000 + i).append(",\"record_count\":").append(worksFiles[i].records()).append("}}");
    }
    return "{\"date\":\"" + date + "\",\"format\":\"jsonl\",\"meta\":{\"content_length\":1,\"record_count\":1},"
        + "\"entities\":[{\"entity\":\"authors\",\"record_count\":1,\"content_length\":1,\"files\":[{\"url\":"
        + "\"s3://openalex/data/jsonl/authors/updated_date=2026-01-01/part_0000.gz\",\"meta\":{\"content_length\":1,"
        + "\"record_count\":1}}]},{\"entity\":\"works\",\"record_count\":" + worksFiles.length + ",\"content_length\":1,"
        + "\"files\":[" + files + "]}]}";
  }
}
