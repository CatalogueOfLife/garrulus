package org.catalogueoflife.nametracker.indexer.finder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.doc.AbstractConverter;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.blob.FileBlobStore;
import org.catalogueoflife.nametracker.store.content.Content;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.work.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentLoaderTest {

  @TempDir Path dir;
  ContentMapper contents = mock(ContentMapper.class);
  BlobStore blobs;
  Spool spool;
  DocumentLoader loader;

  @BeforeEach
  void setUp() {
    blobs = new FileBlobStore(dir.resolve("blobs"));
    spool = new Spool(dir.resolve("spool").toString());
    loader = new DocumentLoader(contents, blobs, spool);
  }

  private static Work work(long id, String level, String title, String abs) {
    return new Work(id, null, "10.1/" + id, null, title, null, null, null, null, null, null, null, null, null, null,
        null, abs, null, "LIVE", "CONTENT_RESOLVED", level, List.of(), List.of(), false, null, null, null, null, null,
        null);
  }

  private static Document fullDoc() {
    return new Document("full:1", "en", List.of(
        new Block("b1", BlockType.TITLE, 0, "Full title", List.of(), List.of()),
        new Block("b2", BlockType.PARAGRAPH, 0, "Body with Homo sapiens.", List.of(), List.of())), Map.of());
  }

  private static byte[] json(Document d) throws Exception {
    return FinderJson.mapper().writeValueAsBytes(d);
  }

  private void rows(long workId, Content... rows) {
    when(contents.listByWork(workId)).thenReturn(List.of(rows));
  }

  private static Content row(long workId, String route, boolean retained, String docSha) {
    return new Content(1L, workId, route, "JATS", "cc-by", retained, "raw", docSha, null, null);
  }

  @Test
  void openFullTextIsReadFromBlob() throws Exception {
    byte[] doc = json(fullDoc());
    String sha = blobs.put(doc);
    rows(7, row(7, "PMC", true, sha), row(7, "ABSTRACT", true, "x"));

    DocumentLoader.Loaded l = loader.load(work(7, "FULLTEXT", "T", "A"));

    assertThat(l.docSha()).isEqualTo(sha);
    assertThat(l.doc()).isEqualTo(fullDoc());
    assertThat(l.diagnostics()).isEmpty();
  }

  @Test
  void closedFullTextIsReadFromSpoolThenDeleted() throws Exception {
    byte[] doc = json(fullDoc());
    String sha = BlobStore.sha256(doc);
    spool.put(sha, doc);
    rows(8, row(8, "PENSOFT", false, sha), row(8, "ABSTRACT", false, "x"));

    DocumentLoader.Loaded l = loader.load(work(8, "FULLTEXT", "T", "A"));

    assertThat(l.docSha()).isEqualTo(sha);
    assertThat(l.doc()).isEqualTo(fullDoc());
    assertThat(blobs.exists(sha)).isFalse();
    spool.delete(sha);
    assertThat(spool.get(sha)).isEmpty();
    // once deleted, a later load has only the abstract
    assertThat(loader.load(work(8, "FULLTEXT", "T", "A")).diagnostics()).containsEntry("fallback", "abstract");
  }

  @Test
  void missingSpoolFallsBackToAbstract() {
    rows(9, row(9, "PENSOFT", false, "f".repeat(64)), row(9, "ABSTRACT", false, "x"));
    Work w = work(9, "FULLTEXT", "Title", "An abstract about Homo sapiens.");

    DocumentLoader.Loaded l = loader.load(w);

    assertThat(l.diagnostics()).isEqualTo(Map.of("fallback", "abstract"));
    assertThat(l.doc()).isEqualTo(AbstractConverter.fromParts("work:9", "Title", "An abstract about Homo sapiens.", null));
    assertThat(l.docSha()).isNotEqualTo("f".repeat(64));
  }

  @Test
  void abstractOnlyWorkUsesRebuiltAbstract() throws Exception {
    rows(10, row(10, "ABSTRACT", true, "x"));
    Work w = work(10, "ABSTRACT_ONLY", "Title", "An abstract.");

    DocumentLoader.Loaded l = loader.load(w);

    Document expected = AbstractConverter.fromParts("work:10", "Title", "An abstract.", null);
    assertThat(l.doc()).isEqualTo(expected);
    assertThat(l.docSha()).isEqualTo(BlobStore.sha256(json(expected))).isEqualTo(DocumentLoader.abstractSha(w));
    assertThat(l.diagnostics()).isEmpty();
  }

  @Test
  void spoolPurgeRemovesOldFilesOnly() throws Exception {
    spool.put("a".repeat(64), new byte[] {1});
    assertThat(spool.purgeOlderThan(java.time.Duration.ofDays(14))).isZero();
    assertThat(spool.get("a".repeat(64))).contains(new byte[] {1});
    assertThat(spool.purgeOlderThan(java.time.Duration.ZERO.minusSeconds(1))).isEqualTo(1);
    assertThat(spool.get("a".repeat(64))).isEmpty();
  }
}
