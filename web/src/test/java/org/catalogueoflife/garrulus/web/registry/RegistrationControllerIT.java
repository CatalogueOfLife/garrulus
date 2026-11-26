package org.catalogueoflife.garrulus.web.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.web.auth.AbstractAuthIT;
import org.catalogueoflife.garrulus.web.auth.Browser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RegistrationControllerIT extends AbstractAuthIT {

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger SEQ = new AtomicInteger();
  static final String REGISTER = "/api/sources/register";

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task WHERE type = 'IDENTIFY_SOURCE'");
    jdbc.update("DELETE FROM source_registration");
  }

  long user() {
    return emailUser("reg" + SEQ.incrementAndGet() + "@example.org", null);
  }

  static JsonNode body(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  static HttpResponse<String> register(Browser b, String json) throws Exception {
    return b.json("POST", REGISTER, json, true);
  }

  long rows() {
    return jdbc.queryForObject("SELECT count(*) FROM source_registration", Long.class);
  }

  long source(String tier, String status) {
    return jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns, publisher, country, publication_status) "
        + "VALUES ('Zootaxa', 'MANUAL', ?, '{1175-5326,1175-5334}', 'Magnolia Press', 'NZ', ?) RETURNING id",
        Long.class, tier, status);
  }

  /** As the indexer leaves an identified registration. */
  void identified(long regId, long sourceId, String outcome, String tierAtIdentification) {
    jdbc.update("UPDATE source_registration SET state = 'IDENTIFIED', outcome = ?, source_id = ?, "
        + "tier_at_identification = ?, identified_at = now() WHERE id = ?", outcome, sourceId, tierAtIdentification,
        regId);
    jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key, detail) VALUES (?, 'REGISTERED', ?, "
        + "jsonb_build_object('registrationId', ?::bigint))", sourceId, String.valueOf(regId), regId);
  }

  @Test
  void registerByIssnStoresAndEnqueues() throws Exception {
    long u = user();
    Browser b = signedInAs(u);
    HttpResponse<String> r = register(b, "{\"issn\":\"ISSN 1175 5326\",\"note\":\"  <b>Zootaxa</b>, please  \"}");
    assertThat(r.statusCode()).isEqualTo(202);
    long id = body(r).get("id").asLong();

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM source_registration WHERE id = ?", id);
    assertThat(row).containsEntry("user_id", u).containsEntry("kind", "ISSN").containsEntry("input", "1175-5326")
        .containsEntry("note", "<b>Zootaxa</b>, please").containsEntry("state", "IDENTIFYING");
    List<Map<String, Object>> tasks = jdbc.queryForList(
        "SELECT payload::text AS payload, priority, dedup_key, state FROM task WHERE type = 'IDENTIFY_SOURCE'");
    assertThat(tasks).hasSize(1);
    assertThat(JSON.readTree((String) tasks.get(0).get("payload")).get("registrationId").asLong()).isEqualTo(id);
    assertThat(((Number) tasks.get(0).get("priority")).intValue()).isZero(); // LIVE
    assertThat(tasks.get(0)).containsEntry("dedup_key", "identify:r" + id).containsEntry("state", "QUEUED");

    JsonNode one = body(b.get("/api/me/registrations/" + id));
    assertThat(one.get("kind").asString()).isEqualTo("ISSN");
    assertThat(one.get("input").asString()).isEqualTo("1175-5326");
    assertThat(one.get("state").asString()).isEqualTo("IDENTIFYING");
    assertThat(one.get("status").asString()).isEqualTo("IDENTIFYING");
    assertThat(one.get("journal").isNull()).isTrue();

    // DOI and address forms
    HttpResponse<String> doi = register(b, "{\"doi\":\"https://doi.org/10.11646/ZOOTAXA.5200.1.1\"}");
    assertThat(doi.statusCode()).isEqualTo(202);
    assertThat(jdbc.queryForObject("SELECT input FROM source_registration WHERE id = ?", String.class,
        body(doi).get("id").asLong())).isEqualTo("10.11646/zootaxa.5200.1.1");
    HttpResponse<String> url = register(b, "{\"url\":\" https://www.biodiversity-science.net/EN/ \",\"note\":\"\"}");
    assertThat(url.statusCode()).isEqualTo(202);
    Map<String, Object> u2 = jdbc.queryForMap("SELECT kind, input, note FROM source_registration WHERE id = ?",
        body(url).get("id").asLong());
    assertThat(u2).containsEntry("kind", "URL").containsEntry("input", "https://www.biodiversity-science.net/EN/");
    assertThat(u2.get("note")).isNull();
  }

  @Test
  void registerRejectsBadCheckDigit() throws Exception {
    Browser b = signedInAs(user());
    HttpResponse<String> r = register(b, "{\"issn\":\"1175-5327\"}");
    assertThat(r.statusCode()).isEqualTo(400);
    assertThat(body(r).get("error").asString()).contains("ISSN");
    assertThat(rows()).isZero();
    assertThat(register(b, "{\"doi\":\"not a doi\"}").statusCode()).isEqualTo(400);
    for (String url : new String[] {"ftp://example.org/", "https://user:pw@example.org/", "http:///nohost",
        "example.org", "https://example.org/" + "x".repeat(2000)}) {
      assertThat(register(b, "{\"url\":\"" + url + "\"}").statusCode()).as(url).isEqualTo(400);
    }
    assertThat(rows()).isZero();
  }

  @Test
  void exactlyOneInputRequired() throws Exception {
    Browser b = signedInAs(user());
    assertThat(register(b, "{}").statusCode()).isEqualTo(400);
    assertThat(register(b, "{\"note\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(register(b, "{\"issn\":\"1175-5326\",\"doi\":\"10.11646/zootaxa.5200.1.1\"}").statusCode())
        .isEqualTo(400);
    assertThat(register(b, "{\"issn\":\"1175-5326\",\"url\":\"https://www.mapress.com/zt\"}").statusCode())
        .isEqualTo(400);
    assertThat(register(b, "{\"issn\":\"  \"}").statusCode()).isEqualTo(400);
    assertThat(register(b, "{\"issn\":1175}").statusCode()).isEqualTo(400);
    assertThat(register(b, "{\"issn\":\"1175-5326\",\"surprise\":1}").statusCode()).isEqualTo(400);
    assertThat(register(b, "{\"issn\":\"1175-5326\",\"note\":\"" + "x".repeat(501) + "\"}").statusCode())
        .isEqualTo(400);
    assertThat(rows()).isZero();
    assertThat(register(b, "{\"issn\":\"1175-5326\",\"note\":\"" + "x".repeat(500) + "\"}").statusCode())
        .isEqualTo(202);
  }

  @Test
  void signedOutIs401() throws Exception {
    Browser b = browser();
    assertThat(register(b, "{\"issn\":\"1175-5326\"}").statusCode()).isEqualTo(401);
    assertThat(b.get("/api/me/registrations").statusCode()).isEqualTo(401);
    assertThat(rows()).isZero();
  }

  @Test
  void withoutXsrfIs403() throws Exception {
    Browser b = signedInAs(user());
    assertThat(b.json("POST", REGISTER, "{\"issn\":\"1175-5326\"}", false).statusCode()).isEqualTo(403);
    assertThat(rows()).isZero();
  }

  @Test
  void twentyFirstPerDayIs429() throws Exception {
    long u = user();
    // older than a day: not counted
    for (int i = 0; i < 5; i++) {
      jdbc.update("INSERT INTO source_registration (user_id, kind, input, created) VALUES (?, 'ISSN', '1175-5326', "
          + "now() - interval '25 hours')", u);
    }
    Browser b = signedInAs(u);
    for (int i = 0; i < 20; i++) {
      assertThat(register(b, "{\"url\":\"https://j" + i + ".example.org/\"}").statusCode())
          .as("registration " + (i + 1)).isEqualTo(202);
    }
    HttpResponse<String> r = register(b, "{\"url\":\"https://j20.example.org/\"}");
    assertThat(r.statusCode()).isEqualTo(429);
    assertThat(body(r).get("status").asInt()).isEqualTo(429);
    assertThat(rows()).isEqualTo(25);
    // another user is not affected
    assertThat(register(signedInAs(user()), "{\"issn\":\"1175-5326\"}").statusCode()).isEqualTo(202);
  }

  @Test
  void theGlobalDailyCapStopsEveryone() throws Exception {
    long others = user();
    // 200 registrations of the last 24 hours, by others (older ones do not count)
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, created) SELECT ?, 'URL', "
        + "'https://g' || i || '.example.org/', now() - interval '1 hour' FROM generate_series(1, 200) i", others);
    jdbc.update("UPDATE source_registration SET created = now() - interval '25 hours' WHERE input = 'https://g1.example.org/'");
    assertThat(register(signedInAs(user()), "{\"issn\":\"1175-5326\"}").statusCode()).isEqualTo(202);
    HttpResponse<String> r = register(signedInAs(user()), "{\"issn\":\"1175-5334\"}");
    assertThat(r.statusCode()).isEqualTo(429);
    assertThat(body(r).get("error").asString()).contains("today");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'IDENTIFY_SOURCE'", Long.class)).isEqualTo(1);
  }

  @Test
  void otherUsersRegistrationIs404() throws Exception {
    Browser a = signedInAs(user());
    long id = body(register(a, "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    Browser other = signedInAs(user());
    assertThat(other.get("/api/me/registrations/" + id).statusCode()).isEqualTo(404);
    assertThat(other.json("DELETE", "/api/me/registrations/" + id, null, Map.of("X-XSRF-TOKEN", other.xsrf()))
        .statusCode()).isEqualTo(404);
    assertThat(body(other.get("/api/me/registrations")).size()).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM source_registration WHERE id = ?", String.class, id))
        .isEqualTo("IDENTIFYING");
    assertThat(a.get("/api/me/registrations/" + id).statusCode()).isEqualTo(200);
    assertThat(a.get("/api/me/registrations/999999999").statusCode()).isEqualTo(404);
  }

  @Test
  void withdrawRemovesSignal() throws Exception {
    Browser b = signedInAs(user());
    long id = body(register(b, "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    long src = source("CANDIDATE", "ACTIVE");
    identified(id, src, "NEW_CANDIDATE", "CANDIDATE");

    HttpResponse<String> r = b.json("DELETE", "/api/me/registrations/" + id, null, Map.of("X-XSRF-TOKEN", b.xsrf()));
    assertThat(r.statusCode()).isEqualTo(204);
    assertThat(jdbc.queryForObject("SELECT state FROM source_registration WHERE id = ?", String.class, id))
        .isEqualTo("WITHDRAWN");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source_signal WHERE source_id = ? AND origin = 'REGISTERED'",
        Long.class, src)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM source WHERE id = ?", Long.class, src)).isOne();
    assertThat(body(b.get("/api/me/registrations/" + id)).get("status").asString()).isEqualTo("WITHDRAWN");
    // a second withdrawal changes nothing
    assertThat(b.json("DELETE", "/api/me/registrations/" + id, null, Map.of("X-XSRF-TOKEN", b.xsrf())).statusCode())
        .isEqualTo(204);
    // without XSRF: refused
    long id2 = body(register(b, "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    assertThat(b.json("DELETE", "/api/me/registrations/" + id2, null, Map.of()).statusCode()).isEqualTo(403);
  }

  @Test
  void statusReadsTheCurrentSource() throws Exception {
    Browser b = signedInAs(user());
    long covered = body(register(b, "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    long accepted = body(register(b, "{\"issn\":\"1175-5334\"}")).get("id").asLong();
    long declined = body(register(b, "{\"issn\":\"2118-9773\"}")).get("id").asLong();
    long ceased = body(register(b, "{\"issn\":\"1313-2989\"}")).get("id").asLong();
    long waiting = body(register(b, "{\"issn\":\"1313-2970\"}")).get("id").asLong();
    long failed = body(register(b, "{\"url\":\"http://10.0.0.1/\"}")).get("id").asLong();
    long core = source("CORE", "ACTIVE");
    jdbc.update("INSERT INTO source_route (source_id, layer, config, status, origin) VALUES (?, 'OAI', "
        + "'{\"base\":\"https://www.mapress.com/zt/oai\"}', 'ACTIVE', 'ADMIN')", core);
    identified(covered, core, "EXISTING", "CORE");
    // a candidate that an admin promoted, or that was folded into a CORE source, reads as covered now
    identified(accepted, core, "NEW_CANDIDATE", "CANDIDATE");
    identified(declined, source("EXCLUDED", "ACTIVE"), "NEW_CANDIDATE", "CANDIDATE");
    identified(ceased, source("CORE", "CEASED"), "CEASED", "CORE");
    identified(waiting, source("CANDIDATE", "ACTIVE"), "NEW_CANDIDATE", "CANDIDATE");
    jdbc.update("UPDATE source_registration SET state = 'NOT_IDENTIFIED', reason = 'ADDRESS_NOT_PUBLIC', "
        + "identified_at = now() WHERE id = ?", failed);

    JsonNode list = body(b.get("/api/me/registrations"));
    assertThat(list.size()).isEqualTo(6);
    assertThat(list.get(0).get("id").asLong()).isEqualTo(failed); // newest first
    Map<Long, JsonNode> byId = new java.util.HashMap<>();
    list.forEach(n -> byId.put(n.get("id").asLong(), n));
    assertThat(byId.get(covered).get("status").asString()).isEqualTo("COVERED");
    assertThat(byId.get(accepted).get("status").asString()).isEqualTo("COVERED");
    assertThat(byId.get(accepted).get("outcome").asString()).isEqualTo("NEW_CANDIDATE");
    assertThat(byId.get(declined).get("status").asString()).isEqualTo("DECLINED");
    assertThat(byId.get(ceased).get("status").asString()).isEqualTo("CEASED");
    assertThat(byId.get(waiting).get("status").asString()).isEqualTo("CANDIDATE");
    assertThat(byId.get(failed).get("status").asString()).isEqualTo("NOT_IDENTIFIED");
    assertThat(byId.get(failed).get("reason").asString()).isEqualTo("ADDRESS_NOT_PUBLIC");
    assertThat(byId.get(failed).get("journal").isNull()).isTrue();

    JsonNode j = byId.get(covered).get("journal");
    assertThat(j.get("id").asLong()).isEqualTo(core);
    assertThat(j.get("title").asString()).isEqualTo("Zootaxa");
    assertThat(j.get("issns").toString()).isEqualTo("[\"1175-5326\",\"1175-5334\"]");
    assertThat(j.get("publisher").asString()).isEqualTo("Magnolia Press");
    assertThat(j.get("country").asString()).isEqualTo("NZ");
    assertThat(j.get("tier").asString()).isEqualTo("CORE");
    assertThat(j.get("publicationStatus").asString()).isEqualTo("ACTIVE");
    assertThat(j.get("routes").toString()).isEqualTo("[\"OAI\"]");
    assertThat(byId.get(covered).get("identifiedAt").isNull()).isFalse();
  }

  @Test
  void longDoiAndLargeBodiesAreRefused() throws Exception {
    Browser b = signedInAs(user());
    String doi = "10.1234/" + "x".repeat(293); // 301 characters
    assertThat(register(b, "{\"doi\":\"" + doi + "\"}").statusCode()).isEqualTo(400);
    assertThat(register(b, "{\"doi\":\"" + "10.1234/" + "x".repeat(292) + "\"}").statusCode()).isEqualTo(202);
    HttpResponse<String> big = register(b, "{\"issn\":\"1175-5326\",\"note\":\"" + "x".repeat(20_000) + "\"}");
    assertThat(big.statusCode()).isEqualTo(413);
    assertThat(rows()).isOne();
  }

  @Test
  void sameInputTwiceIsOneRegistration() throws Exception {
    long u = user();
    Browser b = signedInAs(u);
    long first = body(register(b, "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    HttpResponse<String> again = register(b, "{\"issn\":\"1175 5326\",\"note\":\"again\"}");
    assertThat(again.statusCode()).isEqualTo(202);
    assertThat(body(again).get("id").asLong()).isEqualTo(first);
    assertThat(rows()).isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'IDENTIFY_SOURCE'", Long.class)).isOne();
    // after a failure or a withdrawal the same input may be registered again
    jdbc.update("UPDATE source_registration SET state = 'NOT_IDENTIFIED', reason = 'LOOKUP_FAILED' WHERE id = ?", first);
    long second = body(register(b, "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    assertThat(second).isNotEqualTo(first);
    // another user registers the same journal: a registration of their own
    long other = body(register(signedInAs(user()), "{\"issn\":\"1175-5326\"}")).get("id").asLong();
    assertThat(other).isNotEqualTo(second);
  }
}
