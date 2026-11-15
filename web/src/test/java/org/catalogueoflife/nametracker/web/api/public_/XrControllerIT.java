package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class XrControllerIT extends AbstractPostgresIT {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
  }

  /** Other ITs insert their own current release: leave none behind. */
  @AfterEach
  void dropReleases() {
    jdbc.update("DELETE FROM name_match");
    jdbc.update("DELETE FROM xr_release");
  }

  HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void xr404BeforeFirstCheck() throws Exception {
    HttpResponse<String> r = get("/api/xr");
    assertThat(r.statusCode()).isEqualTo(404);
    assertThat(new ObjectMapper().readTree(r.body()).path("status").asInt()).isEqualTo(404);
  }

  @Test
  void xrIsTheCurrentRelease() throws Exception {
    jdbc.update("INSERT INTO xr_release (key, alias, current) VALUES (315000, 'COL26.9 XR', false), (316691, 'COL26.10 XR', true)");
    JsonNode r = new ObjectMapper().readTree(get("/api/xr").body());
    assertThat(r.path("key").asInt()).isEqualTo(316691);
    assertThat(r.path("alias").asString()).isEqualTo("COL26.10 XR");
  }
}
