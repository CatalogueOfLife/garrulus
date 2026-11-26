package org.catalogueoflife.garrulus.store.blob;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** A content-addressed store of byte blobs, keyed by the lower-case hex SHA-256 of the uncompressed bytes. */
public interface BlobStore {

  /** Stores the bytes (a no-op when already there) and returns their hash. */
  String put(byte[] bytes);

  Optional<byte[]> get(String sha256);

  boolean exists(String sha256);

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }
}
