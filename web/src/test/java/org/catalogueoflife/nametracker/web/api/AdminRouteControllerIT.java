package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AdminRouteControllerIT extends AbstractPostgresIT {

  /** No DNS in the tests: the route URLs' names resolve through the fake resolver. */
  @org.springframework.boot.test.context.TestConfiguration
  static class Resolver {
    @org.springframework.context.annotation.Bean
    RouteConfigCheck.HostResolver hostResolver() {
      return RouteConfigCheckTest.FAKE;
    }
  }

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  long source;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_route");
    jdbc.update("DELETE FROM source");
    source = jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin, active) "
        + "VALUES ('S1', 'Zootaxa', 'CORE', 'MANUAL', true) RETURNING id", Long.class);
  }

  HttpResponse<String> call(String method, String path, @Nullable String body, @Nullable String token)
      throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) b.header("X-Admin-Token", token);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static final String OAI = "{\"layer\":\"OAI\",\"config\":{\"base\":\"https://www.mapress.com/zt/oai\","
      + "\"set\":null,\"prefix\":\"oai_dc\"}}";

  @Test
  void adminPostQueuesVerification() throws Exception {
    HttpResponse<String> r = call("POST", "/api/admin/sources/" + source + "/routes", OAI, "test-token");
    assertThat(r.statusCode()).isEqualTo(202);
    long id = JSON.readTree(r.body()).path("id").asLong();
    var route = jdbc.queryForMap("SELECT source_id, layer, config::text AS config, status, origin FROM source_route "
        + "WHERE id = ?", id);
    assertThat(route).containsEntry("source_id", source).containsEntry("layer", "OAI")
        .containsEntry("status", "PENDING").containsEntry("origin", "ADMIN");
    assertThat((String) route.get("config")).contains("mapress.com/zt/oai");
    var task = jdbc.queryForMap("SELECT type, payload::text AS p, dedup_key FROM task");
    assertThat(task).containsEntry("type", "VERIFY_ROUTE").containsEntry("dedup_key", "verify-route:" + id);
    assertThat(JSON.readTree((String) task.get("p")).path("routeId").asLong()).isEqualTo(id);

    // the same route again is a conflict, not a second row
    assertThat(call("POST", "/api/admin/sources/" + source + "/routes", OAI, "test-token").statusCode())
        .isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_route", Long.class)).isEqualTo(1);

    JsonNode list = JSON.readTree(call("GET", "/api/admin/sources/" + source + "/routes", null, "test-token").body());
    assertThat(list).hasSize(1);
    assertThat(list.get(0).path("id").asLong()).isEqualTo(id);
    assertThat(list.get(0).path("status").asString()).isEqualTo("PENDING");
    assertThat(list.get(0).path("config").path("base").asString()).isEqualTo("https://www.mapress.com/zt/oai");
  }

  @Test
  void patchPausesAndResumes() throws Exception {
    long id = JSON.readTree(call("POST", "/api/admin/sources/" + source + "/routes", OAI, "test-token").body())
        .path("id").asLong();
    String path = "/api/admin/sources/" + source + "/routes/" + id;
    HttpResponse<String> r = call("PATCH", path, "{\"status\":\"PAUSED\"}", "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(r.body()).path("status").asString()).isEqualTo("PAUSED");
    assertThat(call("PATCH", path, "{\"status\":\"ACTIVE\"}", "test-token").statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT status FROM source_route WHERE id = ?", String.class, id))
        .isEqualTo("ACTIVE");
    assertThat(call("PATCH", path, "{\"status\":\"BROKEN\"}", "test-token").statusCode()).isEqualTo(400);
    long other = jdbc.queryForObject("INSERT INTO source (openalex_id, title, tier, origin, active) "
        + "VALUES ('S2', 'Other', 'CORE', 'MANUAL', true) RETURNING id", Long.class);
    assertThat(call("PATCH", "/api/admin/sources/" + other + "/routes/" + id, "{\"status\":\"PAUSED\"}",
        "test-token").statusCode()).isEqualTo(404);
  }

  @Test
  void badRequestsAndAuth() throws Exception {
    String base = "/api/admin/sources/" + source + "/routes";
    assertThat(call("POST", base, "{\"layer\":\"BLR\",\"config\":{}}", "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", base, "{\"layer\":\"OAI\",\"config\":\"x\"}", "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", base, "{\"layer\":\"OAI\"}", "test-token").statusCode()).isEqualTo(400);
    // the indexer fetches what the config names: http(s) only, no loopback, link-local or private hosts
    assertThat(call("POST", base, "{\"layer\":\"OAI\",\"config\":{\"base\":\"http://169.254.169.254/oai\"}}",
        "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", base, "{\"layer\":\"MAGTECH\",\"config\":{\"site\":\"file:///etc\",\"issn\":\"x\"}}",
        "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", base, "{\"layer\":\"OAI\",\"config\":{\"base\":\"http://127.1/oai\"}}",
        "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", base, "{\"layer\":\"OAI\",\"config\":{\"base\":\"http://10.1/oai\"}}",
        "test-token").statusCode()).isEqualTo(400);
    assertThat(call("POST", "/api/admin/sources/999999/routes", OAI, "test-token").statusCode()).isEqualTo(404);
    assertThat(call("GET", "/api/admin/sources/999999/routes", null, "test-token").statusCode()).isEqualTo(404);
    assertThat(call("GET", base, null, null).statusCode()).isEqualTo(401);
    assertThat(call("POST", base, OAI, "wrong").statusCode()).isEqualTo(401);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_route", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task", Long.class)).isZero();
  }
}
