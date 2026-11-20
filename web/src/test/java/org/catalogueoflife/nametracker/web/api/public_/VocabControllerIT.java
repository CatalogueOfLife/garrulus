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

  @Test
  void disciplinesServeTheNineCodes() throws Exception {
    HttpResponse<String> res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/vocab/discipline")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(200);
    JsonNode r = new ObjectMapper().readTree(res.body());
    java.util.Map<String, String> byCode = new java.util.LinkedHashMap<>();
    for (JsonNode d : r) {
      byCode.put(d.path("code").asString(), d.path("label").asString());
      assertThat(d.path("replacedBy").isNull()).isTrue();
    }
    // the same codes and labels as frontend/src/subfields.ts
    assertThat(byCode).containsOnly(
        java.util.Map.entry("1103", "Animal Science and Zoology"),
        java.util.Map.entry("1104", "Aquatic Science"),
        java.util.Map.entry("1105", "Ecology, Evolution, Behavior and Systematics"),
        java.util.Map.entry("1109", "Insect Science"),
        java.util.Map.entry("1110", "Plant Science"),
        java.util.Map.entry("1911", "Paleontology"),
        java.util.Map.entry("2303", "Ecology"),
        java.util.Map.entry("2404", "Microbiology"),
        java.util.Map.entry("2405", "Parasitology"));
  }
}
