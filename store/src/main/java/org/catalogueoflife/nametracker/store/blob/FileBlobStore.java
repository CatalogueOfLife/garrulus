package org.catalogueoflife.nametracker.store.blob;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Blobs as {@code root/<sha[0:2]>/<sha[2:4]>/<sha>.gz}, written to a temp file and moved into place atomically. */
public class FileBlobStore implements BlobStore {

  private final Path root;

  public FileBlobStore(Path root) {
    this.root = root;
  }

  private Path path(String sha) {
    if (!sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Not a sha256: " + sha);
    return root.resolve(sha.substring(0, 2)).resolve(sha.substring(2, 4)).resolve(sha + ".gz");
  }

  @Override
  public String put(byte[] bytes) {
    String sha = BlobStore.sha256(bytes);
    Path target = path(sha);
    if (Files.exists(target)) return sha;
    try {
      Files.createDirectories(target.getParent());
      Path tmp = Files.createTempFile(target.getParent(), sha, ".tmp");
      try {
        try (var out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
          out.write(bytes);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sha;
  }

  @Override
  public Optional<byte[]> get(String sha256) {
    Path p = path(sha256);
    if (!Files.exists(p)) return Optional.empty();
    try (var in = new GZIPInputStream(Files.newInputStream(p))) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      in.transferTo(out);
      return Optional.of(out.toByteArray());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public boolean exists(String sha256) {
    return Files.exists(path(sha256));
  }
}
