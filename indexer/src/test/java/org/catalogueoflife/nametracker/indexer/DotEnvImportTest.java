package org.catalogueoflife.nametracker.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

/** {@code nametracker.dotenv} replaces the .env import list of application.yml (the ITs rely on it). */
class DotEnvImportTest {

  @Configuration
  static class Empty {
  }

  @Test
  void dotenvPropertyChoosesTheImportedFile(@TempDir Path dir) throws Exception {
    Path env = dir.resolve("probe.env");
    Files.writeString(env, "GARRULUS_DOTENV_PROBE=yes\n");
    SpringApplication app = new SpringApplication(Empty.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setLogStartupInfo(false);
    app.setDefaultProperties(Map.of("nametracker.dotenv", "optional:file:" + env + "[.properties]",
        "spring.main.banner-mode", "off"));
    try (var ctx = app.run()) {
      assertThat(ctx.getEnvironment().getProperty("GARRULUS_DOTENV_PROBE")).isEqualTo("yes");
    }
  }
}
