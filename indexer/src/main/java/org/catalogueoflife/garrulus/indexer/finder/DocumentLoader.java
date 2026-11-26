package org.catalogueoflife.garrulus.indexer.finder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.doc.AbstractConverter;
import org.catalogueoflife.garrulus.store.blob.BlobStore;
import org.catalogueoflife.garrulus.store.content.Content;
import org.catalogueoflife.garrulus.store.content.ContentMapper;
import org.catalogueoflife.garrulus.store.work.Work;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds the document the name finder should read for a work. At text level FULLTEXT that is the document of the newest
 * non-ABSTRACT content row: from the blob store when it was retained, else from the {@link Spool}. When it is gone, or
 * at any other text level, it is the abstract document rebuilt from the work (diagnostics {@code fallback=abstract}
 * only for the lost full text). A NO_TEXT work has an empty document.
 */
@Component
public class DocumentLoader {

  private static final Logger LOG = LoggerFactory.getLogger(DocumentLoader.class);
  /** The sha recorded for a work without any text. */
  public static final String NO_TEXT_SHA = BlobStore.sha256("");

  /** The document, the SHA-256 of its JSON and what the loader had to do to get it. */
  public record Loaded(Document doc, String docSha, Map<String, String> diagnostics) {
  }

  private final ContentMapper contents;
  private final BlobStore blobs;
  private final Spool spool;

  public DocumentLoader(ContentMapper contents, BlobStore blobs, Spool spool) {
    this.contents = contents;
    this.blobs = blobs;
    this.spool = spool;
  }

  public Loaded load(Work work) {
    String level = work.textLevel();
    if ("NO_TEXT".equals(level)) {
      return new Loaded(new Document("work:" + work.id(), null, List.of(), Map.of()), NO_TEXT_SHA, Map.of());
    }
    if ("FULLTEXT".equals(level)) {
      Optional<Content> row = fullTextRow(contents.listByWork(work.id()));
      if (row.isPresent()) {
        Optional<byte[]> json = Boolean.TRUE.equals(row.get().retained())
            ? blobs.get(row.get().docSha256()) : spool.get(work.id(), row.get().docSha256());
        if (json.isPresent()) {
          try {
            return new Loaded(FinderJson.mapper().readValue(json.get(), Document.class), row.get().docSha256(),
                Map.of());
          } catch (RuntimeException e) {
            LOG.warn("Unreadable full text document {} of work {}: {}", row.get().docSha256(), work.id(), e.toString());
          }
        }
      }
      LOG.info("Full text of work {} is gone, using the abstract", work.id());
      return new Loaded(abstractDoc(work), abstractSha(work), Map.of("fallback", "abstract"));
    }
    return new Loaded(abstractDoc(work), abstractSha(work), Map.of());
  }

  /** The newest non-ABSTRACT row, see {@link Content#newestFullText}. */
  static Optional<Content> fullTextRow(List<Content> rows) {
    return Content.newestFullText(rows);
  }

  /** The title and abstract of the work as a document. */
  public static Document abstractDoc(Work w) {
    return AbstractConverter.fromParts("work:" + w.id(), w.title(), w.abstractText(), w.abstractJats());
  }

  /** The SHA-256 of the JSON of {@link #abstractDoc}. */
  public static String abstractSha(Work w) {
    return BlobStore.sha256(FinderJson.mapper().writeValueAsBytes(abstractDoc(w)));
  }

  /**
   * The sha of the document the finder should see, as {@link #load} would pick it, without reading it: the full text
   * row's doc sha at FULLTEXT, a fixed marker for NO_TEXT, else that of the rebuilt abstract.
   */
  public static String chosenSha(Work w, String textLevel, List<Content> rows) {
    if ("NO_TEXT".equals(textLevel)) return NO_TEXT_SHA;
    if ("FULLTEXT".equals(textLevel)) {
      Optional<Content> row = fullTextRow(rows);
      if (row.isPresent()) return row.get().docSha256();
    }
    return abstractSha(w);
  }
}
