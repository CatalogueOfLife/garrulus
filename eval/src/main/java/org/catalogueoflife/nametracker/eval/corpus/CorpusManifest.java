package org.catalogueoflife.nametracker.eval.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The manifest of a corpus: one TSV row per document, with its fixed dev/test split. */
public record CorpusManifest(List<Row> rows) {
  public static final String HEADER = "docId\tdoi\tjournal\tlicense\tsplit\txmlUrl\tpdfUrl";

  public record Row(String docId, String doi, String journal, String license, String split, String xmlUrl, String pdfUrl) {
    public Row withSplit(String newSplit) {
      return new Row(docId, doi, journal, license, newSplit, xmlUrl, pdfUrl);
    }

    String toLine() {
      return String.join("\t", docId, doi, journal, license, split, xmlUrl, pdfUrl);
    }
  }

  public CorpusManifest {
    rows = rows == null ? List.of() : List.copyOf(rows);
  }

  public void write(Path tsv) {
    try {
      List<String> lines = new ArrayList<>();
      lines.add(HEADER);
      rows.forEach(r -> lines.add(r.toLine()));
      Files.write(tsv, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static CorpusManifest read(Path tsv) {
    try {
      List<Row> rows = new ArrayList<>();
      for (String line : Files.readAllLines(tsv)) {
        if (line.isBlank() || line.equals(HEADER)) continue;
        String[] c = line.split("\t", -1);
        if (c.length != 7) throw new IllegalArgumentException("Bad manifest line in " + tsv + ": " + line);
        rows.add(new Row(c[0], c[1], c[2], c[3], c[4], c[5], c[6]));
      }
      return new CorpusManifest(rows);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
