package org.catalogueoflife.garrulus.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.web.AbstractPostgresIT;
import org.catalogueoflife.garrulus.web.WebApplication;
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

  /** Raw HTTP so that the request target stays exactly as written (HttpClient would normalise it). */
  static String rawPost(int port, String target, String token) throws Exception {
    try (java.net.Socket sock = new java.net.Socket("localhost", port)) {
      String req =
          "POST " + target + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
              + (token == null ? "" : "X-Admin-Token: " + token + "\r\n")
              + "Content-Length: " + BODY.length() + "\r\nConnection: close\r\n\r\n" + BODY;
      sock.getOutputStream().write(req.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return new String(sock.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void oddPathsCannotBypassTheToken() throws Exception {
    long before = jdbc.queryForObject("select count(*) from task", Long.class);
    for (String target :
        List.of(
            "/api/%61dmin/tasks",
            "/api//admin/tasks",
            "/api/./admin/tasks",
            "/api/admin/./tasks",
            "/api/admin//tasks",
            "/api/admin/tasks/",
            "/api/admin/tasks;x=1",
            "/api/%41dmin/tasks")) {
      String status = rawPost(port, target, null).lines().findFirst().orElse("");
      assertThat(status).as(target).doesNotContain(" 202").matches("HTTP/1.1 (401|404|400|403).*");
    }
    assertThat(jdbc.queryForObject("select count(*) from task", Long.class)).isEqualTo(before);
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
    // without the header a write is a session write, which needs XSRF first (ADR-0006, amendment M6b)
    assertThat(post(port, null).statusCode()).isEqualTo(403);
  }

  @Nested
  @SpringBootTest(
      classes = WebApplication.class,
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = {"garrulus.admin.token=", "garrulus.admin.orcids="})
  class WithoutToken {
    @LocalServerPort int port;

    @Test
    void adminEndpointHiddenWithoutToken() throws Exception {
      assertThat(post(port, "anything").statusCode()).isEqualTo(404);
      assertThat(post(port, "").statusCode()).isEqualTo(404);
    }
  }
}
