package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class VocabControllerIT extends AbstractPostgresIT {

  @LocalServerPort int port;

  @Test
  void taxgroupVocabularyWithParentsCodesAndIcon() throws Exception {
    HttpResponse<String> res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/vocab/taxgroup")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode r = new ObjectMapper().readTree(res.body());
    JsonNode insects = null;
    for (JsonNode g : r) {
      if ("Insects".equals(g.path("name").asString())) {
        insects = g;
      }
    }
    assertThat(insects).isNotNull();
    assertThat(insects.path("parents").get(0).asString()).isEqualTo("Arthropods");
    assertThat(insects.path("icon").asString()).isEqualTo("https://api.checklistbank.org/vocab/taxgroup/Insects/icon.svg");
    JsonNode algae = null;
    for (JsonNode g : r) {
      if ("Algae".equals(g.path("name").asString())) {
        algae = g;
      }
    }
    assertThat(algae.path("parents")).hasSize(2);
    JsonNode animals = null;
    for (JsonNode g : r) {
      if ("Animals".equals(g.path("name").asString())) {
        animals = g;
      }
    }
    assertThat(animals.path("codes").get(0).asString()).isEqualTo("ZOOLOGICAL");
  }
}
