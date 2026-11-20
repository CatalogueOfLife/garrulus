package org.catalogueoflife.nametracker.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AdminRegistrationControllerIT extends AbstractPostgresIT {

  static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM app_user WHERE email LIKE 't12r%@example.org'");
    jdbc.update("DELETE FROM source");
  }

  HttpResponse<String> get(String path, String token) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (token != null) b.header("X-Admin-Token", token);
    return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void listsByState() throws Exception {
    long source = jdbc.queryForObject("INSERT INTO source (title, tier, origin) VALUES ('Zeta Journal', 'CORE', "
        + "'REGISTERED') RETURNING id", Long.class);
    long user = jdbc.queryForObject("INSERT INTO app_user (email, display_name) VALUES ('t12r@example.org', 'Grace') "
        + "RETURNING id", Long.class);
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, note, state, outcome, source_id, "
        + "tier_at_identification, created) VALUES (?, 'ISSN', '1234-5679', 'n1', 'IDENTIFIED', 'NEW_CANDIDATE', ?, "
        + "'CANDIDATE', now() - interval '2 hours')", user, source);
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, state, reason) VALUES "
        + "(?, 'URL', 'https://x.example.org', 'NOT_IDENTIFIED', 'no ISSN found')", user);
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, state) VALUES (?, 'DOI', '10.1/x', 'WITHDRAWN')",
        user);

    HttpResponse<String> r = get("/api/admin/registrations", "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
    JsonNode all = JSON.readTree(r.body());
    assertThat(all.size()).isEqualTo(3);
    assertThat(all.get(0).path("kind").asString()).isEqualTo("DOI"); // newest first

    JsonNode identified = JSON.readTree(get("/api/admin/registrations?state=IDENTIFIED", "test-token").body());
    assertThat(identified.size()).isEqualTo(1);
    JsonNode v = identified.get(0);
    assertThat(v.path("input").asString()).isEqualTo("1234-5679");
    assertThat(v.path("note").asString()).isEqualTo("n1");
    assertThat(v.path("outcome").asString()).isEqualTo("NEW_CANDIDATE");
    assertThat(v.path("sourceId").asLong()).isEqualTo(source);
    assertThat(v.path("sourceTitle").asString()).isEqualTo("Zeta Journal");
    assertThat(v.path("userDisplayName").asString()).isEqualTo("Grace");
    // the CURRENT tier of the source, not the one at identification
    assertThat(v.path("sourceTier").asString()).isEqualTo("CORE");
    assertThat(v.path("sourcePublicationStatus").asString()).isEqualTo("ACTIVE");

    JsonNode failed = JSON.readTree(get("/api/admin/registrations?state=NOT_IDENTIFIED", "test-token").body());
    assertThat(failed.size()).isEqualTo(1);
    assertThat(failed.get(0).path("reason").asString()).isEqualTo("no ISSN found");
    assertThat(failed.get(0).path("sourceId").isNull()).isTrue();

    assertThat(JSON.readTree(get("/api/admin/registrations?limit=1&offset=1", "test-token").body()).size()).isEqualTo(1);
    assertThat(get("/api/admin/registrations?state=BOGUS", "test-token").statusCode()).isEqualTo(400);
    assertThat(get("/api/admin/registrations?limit=0", "test-token").statusCode()).isEqualTo(400);
    assertThat(get("/api/admin/registrations", null).statusCode()).isEqualTo(401);
    assertThat(get("/api/admin/registrations", "wrong").statusCode()).isEqualTo(401);
  }
}
