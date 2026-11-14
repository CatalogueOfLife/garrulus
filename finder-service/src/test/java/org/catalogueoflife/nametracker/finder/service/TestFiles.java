package org.catalogueoflife.nametracker.finder.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class TestFiles {
  private TestFiles() {}

  /** A test resource of finder-api (its test-jar is not on this classpath, so they are copied by the pom into target/). */
  static String read(String name) {
    try (InputStream in = TestFiles.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) throw new IllegalStateException("Missing test resource " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
