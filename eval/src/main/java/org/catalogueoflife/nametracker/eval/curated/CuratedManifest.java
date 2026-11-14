package org.catalogueoflife.nametracker.eval.curated;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The manifest of the curated set: one TSV row per document with its stratum, fixed split and the id to re-fetch it by.
 * {@code source} is the OpenAlex work id for abstracts and the TaxPub XML URL for Pensoft excerpts.
 */
public record CuratedManifest(List<Row> rows) {
  public static final String HEADER = "docId\tstratum\tsplit\tlanguage\tlicense\ttextRetained\tdoi\tsource\ttextSha256";

  public record Row(String docId, String stratum, String split, String language, String license, boolean textRetained,
                    String doi, String source, String textSha256) {
    String toLine() {
      return String.join("\t", docId, stratum, split, language, license, Boolean.toString(textRetained), doi, source, textSha256);
    }
  }

  public CuratedManifest {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  public static CuratedManifest of(List<CuratedSelector.Pick> picks) {
    return new CuratedManifest(picks.stream().map(p -> new Row(p.docId(), p.stratum(), p.split(), p.language(), p.license(),
        p.textRetained(), p.doi(), p.source(), p.textSha256())).toList());
  }

  public void write(Path tsv) {
    try {
      List<String> lines = new ArrayList<>();
      lines.add(HEADER);
      rows.forEach(r -> lines.add(r.toLine()));
      if (tsv.toAbsolutePath().getParent() != null) Files.createDirectories(tsv.toAbsolutePath().getParent());
      Files.write(tsv, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static CuratedManifest read(Path tsv) {
    try {
      List<Row> rows = new ArrayList<>();
      for (String line : Files.readAllLines(tsv)) {
        if (line.isBlank() || line.equals(HEADER)) continue;
        String[] c = line.split("\t", -1);
        if (c.length != 9) throw new IllegalArgumentException("Bad curated manifest line in " + tsv + ": " + line);
        rows.add(new Row(c[0], c[1], c[2], c[3], c[4], Boolean.parseBoolean(c[5]), c[6], c[7], c[8]));
      }
      return new CuratedManifest(rows);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
