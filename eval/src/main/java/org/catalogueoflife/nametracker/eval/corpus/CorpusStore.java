package org.catalogueoflife.nametracker.eval.corpus;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gold corpora as gzipped JSON lines, one {@link GoldDocument} per line. */
public final class CorpusStore {
  private CorpusStore() {}

  /** Writes the documents as given; call {@link GoldDocument#withoutText()} first for licences that forbid storing text. */
  public static void write(Path jsonlGz, List<GoldDocument> docs) {
    try {
      if (jsonlGz.getParent() != null) Files.createDirectories(jsonlGz.getParent());
      try (BufferedWriter w = new BufferedWriter(new java.io.OutputStreamWriter(
          new GZIPOutputStream(Files.newOutputStream(jsonlGz)), StandardCharsets.UTF_8))) {
        for (GoldDocument d : docs) {
          w.write(FinderJson.mapper().writeValueAsString(d));
          w.write('\n');
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads the corpus. A document with textRetained=false needs the refetcher: its text is re-fetched and put back, and the
   * SHA-256 must match, otherwise this throws an IllegalStateException naming the docId.
   */
  public static List<GoldDocument> read(Path jsonlGz, @Nullable TextRefetcher refetcher) {
    List<GoldDocument> out = new ArrayList<>();
    try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(
        new GZIPInputStream(Files.newInputStream(jsonlGz)), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        if (line.isBlank()) continue;
        GoldDocument d = FinderJson.mapper().readValue(line, GoldDocument.class);
        if (!d.textRetained()) {
          if (refetcher == null) {
            throw new IllegalStateException("Document " + d.docId() + " has no stored text and no TextRefetcher was given");
          }
          d = d.withText(refetcher.refetch(d));
        } else if (!GoldDocument.textHash(d.document()).equals(d.textSha256())) {
          throw new IllegalStateException("Stored text of " + d.docId() + " does not match its SHA-256");
        }
        out.add(d);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  static String flat(GoldDocument d) {
    return FlatText.of(d.document()).text();
  }
}
