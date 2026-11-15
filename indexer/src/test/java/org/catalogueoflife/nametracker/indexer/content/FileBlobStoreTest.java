package org.catalogueoflife.nametracker.indexer.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBlobStoreTest {

  @TempDir Path root;

  private long files() throws Exception {
    try (Stream<Path> s = Files.walk(root)) {
      return s.filter(Files::isRegularFile).count();
    }
  }

  @Test
  void putIsContentAddressedAndIdempotent() throws Exception {
    FileBlobStore store = new FileBlobStore(root);
    byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
    String sha = store.put(bytes);
    assertThat(sha).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    assertThat(root.resolve("2c/f2/" + sha + ".gz")).isRegularFile();
    assertThat(store.exists(sha)).isTrue();
    assertThat(store.get(sha)).hasValueSatisfying(b -> assertThat(b).isEqualTo(bytes));

    assertThat(store.put(bytes)).isEqualTo(sha);
    assertThat(files()).isEqualTo(1); // no second file and no temp file left behind

    assertThat(store.exists("0".repeat(64))).isFalse();
    assertThat(store.get("0".repeat(64))).isEmpty();
  }
}
