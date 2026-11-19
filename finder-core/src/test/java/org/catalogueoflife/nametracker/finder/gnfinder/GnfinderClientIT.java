package org.catalogueoflife.nametracker.finder.gnfinder;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real gnfinder, built from docker/gnfinder/Dockerfile (the tag there; no upstream image exists for
 * v1.5.0). The image is kept, so docker's layer cache makes later runs start at once.
 */
@Testcontainers
class GnfinderClientIT {
  @Container
  static final GenericContainer<?> GNFINDER = new GenericContainer<>(
      new ImageFromDockerfile("nametracker/gnfinder:v1.5.0", false)
          .withDockerfile(Path.of("../docker/gnfinder/Dockerfile").toAbsolutePath().normalize()))
      .withExposedPorts(8999)
      .waitingFor(Wait.forHttp("/api/v1/ping").forStatusCode(200).withStartupTimeout(java.time.Duration.ofMinutes(3)));

  private static GnfinderClient client() {
    return new GnfinderClient(URI.create("http://" + GNFINDER.getHost() + ":" + GNFINDER.getMappedPort(8999)), HttpClient.newHttpClient());
  }

  private static List<String> trimmed(String text, GnfinderResponse r) {
    return r.names().stream().map(n -> GnfinderGenerator.trim(n.verbatim())).toList();
  }

  @Test
  void findsNamesInSampleText() {
    String text = "A new species of Carabus (Coleoptera: Carabidae), Carabus nigrus sp. nov., is described. C. nigrus differs from Pterostichus niger.";
    GnfinderResponse r = client().find(text);
    assertThat(trimmed(text, r)).containsExactly("Carabus", "Coleoptera", "Carabidae", "Carabus nigrus", "C. nigrus", "Pterostichus niger");
    GnName abbr = r.names().get(4);
    assertThat(abbr.name()).isEqualTo("C. nigrus");
    assertThat(abbr.cardinality()).isEqualTo(2);
    assertThat(abbr.annotationNomenType()).isEqualTo("NO_ANNOT");
  }

  @Test
  void umlautTextUsesCodePointOffsets() {
    GnfinderResponse r = client().find("Über die Käfer: Carabus nigrus");
    assertThat(r.names()).hasSize(1);
    assertThat(r.names().get(0).start()).isEqualTo(16);
    assertThat(r.names().get(0).end()).isEqualTo(30);
    // an astral character is one code point, not two UTF-16 chars
    GnName g = client().find("😀 Carabus nigrus").names().get(0);
    assertThat(g.start()).isEqualTo(2);
    assertThat(g.end()).isEqualTo(16);
  }

  @Test
  void hyphenatedLineBreakBehaviour() {
    // Observed with gnfinder v1.4.0 and v1.5.0: a name split by a hyphen and a line break is not found at all, so de-hyphenation has to
    // happen before gnfinder sees the text (input for ADR-0003).
    assertThat(client().find("Pterosti-\nchus niger").names()).isEmpty();
    assertThat(client().find("Pterostichus niger").names()).hasSize(1);
  }
}
