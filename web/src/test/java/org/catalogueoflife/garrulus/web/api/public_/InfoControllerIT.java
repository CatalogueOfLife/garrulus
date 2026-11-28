package org.catalogueoflife.garrulus.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.garrulus.web.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class InfoControllerIT extends AbstractPostgresIT {

  @LocalServerPort int port;

  @Test
  void servesTheFirstYear() throws Exception {
    HttpResponse<String> r = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/info")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode body = new ObjectMapper().readTree(r.body());
    assertThat(body.path("firstYear").asInt()).isEqualTo(2000); // the default of garrulus.first-year
  }
}
