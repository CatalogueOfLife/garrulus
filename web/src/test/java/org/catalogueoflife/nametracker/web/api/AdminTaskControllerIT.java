package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.catalogueoflife.nametracker.web.WebApplication;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminTaskControllerIT extends AbstractPostgresIT {

  static final String BODY = "{\"type\":\"PING\",\"payload\":{},\"priority\":\"LIVE\",\"dedupKey\":null}";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  static HttpResponse<String> post(int port, String token) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/tasks"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(BODY));
    if (token != null) {
      b.header("X-Admin-Token", token);
    }
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void ping() throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/ping")).build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("pong");
  }

  @Test
  void enqueuesWithValidToken() throws Exception {
    HttpResponse<String> r = post(port, "test-token");
    assertThat(r.statusCode()).isEqualTo(202);
    assertThat(r.body()).matches("\\{\\s*\"id\"\\s*:\\s*\\d+\\s*}");
    long id = Long.parseLong(r.body().replaceAll("\\D", ""));
    List<Map<String, Object>> rows = jdbc.queryForList("select state, type from task where id = ?", id);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("state", "QUEUED").containsEntry("type", "PING");
  }

  @Test
  void rejectsWrongToken() throws Exception {
    assertThat(post(port, "nope").statusCode()).isEqualTo(401);
    assertThat(post(port, null).statusCode()).isEqualTo(401);
  }

  @Nested
  @SpringBootTest(
      classes = WebApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "nametracker.admin.token=")
  class WithoutToken {
    @LocalServerPort int port;

    @Test
    void adminEndpointHiddenWithoutToken() throws Exception {
      assertThat(post(port, "anything").statusCode()).isEqualTo(404);
      assertThat(post(port, "").statusCode()).isEqualTo(404);
    }
  }
}
