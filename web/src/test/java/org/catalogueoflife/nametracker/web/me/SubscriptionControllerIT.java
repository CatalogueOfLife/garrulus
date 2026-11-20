package org.catalogueoflife.nametracker.web.me;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Year;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.web.auth.AbstractAuthIT;
import org.catalogueoflife.nametracker.web.auth.Browser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SubscriptionControllerIT extends AbstractAuthIT {

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired SubscriptionMapper subscriptions;

  Browser newUser() throws Exception {
    return signedInAs(emailUser("sub" + SEQ.incrementAndGet() + "@example.org", null));
  }

  static JsonNode body(HttpResponse<String> r) {
    return JSON.readTree(r.body());
  }

  HttpResponse<String> create(Browser b, String json) throws Exception {
    return b.json("POST", "/api/me/subscriptions", json, true);
  }

  long source() {
    return jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('J', 'MANUAL') RETURNING id", Long.class);
  }

  @Test
  void createAppliesTypeDefaults() throws Exception {
    Browser b = newUser();
    HttpResponse<String> r = create(b, "{\"type\":\"TAXON\",\"target\":\" ABC \",\"label\":\" Insecta \"}");
    assertThat(r.statusCode()).isEqualTo(201);
    JsonNode s = body(r);
    assertThat(s.get("target").asString()).isEqualTo("ABC");
    assertThat(s.get("label").asString()).isEqualTo("Insecta");
    assertThat(s.get("mainSubjectOnly").asBoolean()).isTrue();
    assertThat(s.get("newNamesOnly").asBoolean()).isFalse();
    assertThat(s.get("includeAbstractOnly").asBoolean()).isTrue();
    assertThat(s.get("includeReferences").asBoolean()).isFalse();
    assertThat(s.get("paused").asBoolean()).isFalse();
    assertThat(s.get("sinceYear").isNull()).isTrue();
    assertThat(s.get("filters").size()).isZero();
    String token = subscriptions.listByUser(jdbcUser(b)).get(0).feedToken();
    assertThat(token).matches("[A-Za-z0-9_-]{22}");
    assertThat(s.get("feedUrl").asString()).isEqualTo("http://localhost:5173/feeds/s/" + token + ".atom");

    long src = source();
    JsonNode j = body(create(b, "{\"type\":\"SOURCE\",\"target\":\"" + src + "\",\"label\":\"J\"}"));
    assertThat(j.get("mainSubjectOnly").asBoolean()).isFalse();
    JsonNode g = body(create(b, "{\"type\":\"TAXGROUP\",\"target\":\"Plants\",\"label\":\"I\",\"newNamesOnly\":true,"
        + "\"includeAbstractOnly\":false,\"filters\":{\"oa\":\"GOLD\",\"country\":\"DE\"},\"sinceYear\":2020}"));
    assertThat(g.get("mainSubjectOnly").asBoolean()).isTrue();
    assertThat(g.get("newNamesOnly").asBoolean()).isTrue();
    assertThat(g.get("includeAbstractOnly").asBoolean()).isFalse();
    assertThat(g.get("sinceYear").asInt()).isEqualTo(2020);
    assertThat(g.get("filters").get("oa").asString()).isEqualTo("GOLD");

    // unknown targets and types
    assertThat(create(b, "{\"type\":\"SOURCE\",\"target\":\"99999999\",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"NAME\",\"target\":\"99999999\",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"NAME\",\"target\":\"abc\",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXGROUP\",\"target\":\"Nope\",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXON\",\"target\":\"" + "x".repeat(51) + "\",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXON\",\"target\":\"  \",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXON\",\"target\":\"A\",\"label\":\"  \"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXON\",\"target\":\"A\",\"label\":\"" + "x".repeat(201) + "\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"BOGUS\",\"target\":\"A\",\"label\":\"x\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXON\",\"target\":\"A\",\"label\":\"x\",\"mainSubjectOnly\":\"yes\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, "{\"type\":\"TAXON\",\"target\":\"A\",\"label\":\"x\",\"surprise\":1}").statusCode()).isEqualTo(400);
  }

  long jdbcUser(Browser b) throws Exception {
    return body(b.get("/api/me")).get("id").asLong();
  }

  @Test
  void capIs100() throws Exception {
    Browser b = newUser();
    for (int i = 0; i < 100; i++) {
      jdbc.update("INSERT INTO subscription (user_id, type, target, label, feed_token) VALUES (?, 'TAXON', ?, 'l', ?)",
          jdbcUser(b), "T" + i, "tok" + SEQ.incrementAndGet() + "x" + i);
    }
    HttpResponse<String> r = create(b, "{\"type\":\"TAXON\",\"target\":\"Z\",\"label\":\"z\"}");
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(body(r).get("error").asString()).isEqualTo("limit");
  }

  @Test
  void duplicateIs409WithId() throws Exception {
    Browser b = newUser();
    long id = body(create(b, "{\"type\":\"TAXON\",\"target\":\"D1\",\"label\":\"a\"}")).get("id").asLong();
    HttpResponse<String> r = create(b, "{\"type\":\"TAXON\",\"target\":\"D1\",\"label\":\"b\"}");
    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(body(r).get("error").asString()).isEqualTo("exists");
    assertThat(body(r).get("id").asLong()).isEqualTo(id);
  }

  @Test
  void otherUsersSubscriptionIs404() throws Exception {
    Browser a = newUser();
    Browser other = newUser();
    long id = body(create(a, "{\"type\":\"TAXON\",\"target\":\"O1\",\"label\":\"a\"}")).get("id").asLong();
    assertThat(other.json("PATCH", "/api/me/subscriptions/" + id, "{\"paused\":true}", true).statusCode()).isEqualTo(404);
    assertThat(other.json("DELETE", "/api/me/subscriptions/" + id, "", true).statusCode()).isEqualTo(404);
    assertThat(other.json("POST", "/api/me/subscriptions/" + id + "/feed-token", "", true).statusCode()).isEqualTo(404);
    // the owner patches
    JsonNode p = body(a.json("PATCH", "/api/me/subscriptions/" + id,
        "{\"paused\":true,\"label\":\" New \",\"includeReferences\":true,\"sinceYear\":2019,\"filters\":{\"code\":\"ZOOLOGICAL\"}}", true));
    assertThat(p.get("paused").asBoolean()).isTrue();
    assertThat(p.get("label").asString()).isEqualTo("New");
    assertThat(p.get("includeReferences").asBoolean()).isTrue();
    assertThat(p.get("sinceYear").asInt()).isEqualTo(2019);
    assertThat(p.get("filters").get("code").asString()).isEqualTo("ZOOLOGICAL");
    assertThat(p.get("mainSubjectOnly").asBoolean()).isTrue();
    // sinceYear null returns to the rolling window; type and target cannot change
    assertThat(body(a.json("PATCH", "/api/me/subscriptions/" + id, "{\"sinceYear\":null}", true)).get("sinceYear").isNull()).isTrue();
    assertThat(a.json("PATCH", "/api/me/subscriptions/" + id, "{\"target\":\"X\"}", true).statusCode()).isEqualTo(400);
    // list
    assertThat(body(a.get("/api/me/subscriptions")).size()).isEqualTo(1);
    assertThat(body(other.get("/api/me/subscriptions")).size()).isZero();
    // delete
    assertThat(a.json("DELETE", "/api/me/subscriptions/" + id, "", true).statusCode()).isEqualTo(204);
    assertThat(a.json("DELETE", "/api/me/subscriptions/" + id, "", true).statusCode()).isEqualTo(404);
  }

  @Test
  void sinceYearValidated() throws Exception {
    Browser b = newUser();
    String base = "{\"type\":\"TAXON\",\"target\":\"Y1\",\"label\":\"y\",\"sinceYear\":";
    assertThat(create(b, base + "1749}").statusCode()).isEqualTo(400);
    assertThat(create(b, base + (Year.now().getValue() + 1) + "}").statusCode()).isEqualTo(400);
    assertThat(create(b, base + "\"2020\"}").statusCode()).isEqualTo(400);
    assertThat(create(b, base + "1750}").statusCode()).isEqualTo(201);
    long id = body(create(b, "{\"type\":\"TAXON\",\"target\":\"Y2\",\"label\":\"y\"}")).get("id").asLong();
    assertThat(b.json("PATCH", "/api/me/subscriptions/" + id, "{\"sinceYear\":1700}", true).statusCode()).isEqualTo(400);
    assertThat(b.json("PATCH", "/api/me/subscriptions/" + id, "{\"sinceYear\":" + Year.now().getValue() + "}", true).statusCode()).isEqualTo(200);
  }

  @Test
  void unknownFilterKeyIs400() throws Exception {
    Browser b = newUser();
    String head = "{\"type\":\"TAXON\",\"target\":\"F1\",\"label\":\"f\",\"filters\":";
    assertThat(create(b, head + "{\"yearFrom\":\"2000\"}}").statusCode()).isEqualTo(400);
    assertThat(create(b, head + "{\"code\":[\"ZOOLOGICAL\"]}}").statusCode()).isEqualTo(400);
    assertThat(create(b, head + "{\"code\":{\"a\":1}}}").statusCode()).isEqualTo(400);
    assertThat(create(b, head + "{\"code\":\"NOPE\"}}").statusCode()).isEqualTo(400);
    assertThat(create(b, head + "[]}").statusCode()).isEqualTo(400);
    assertThat(create(b, head + "{\"subfield\":\"Taxonomy\",\"journalType\":\"TAXONOMIC\",\"tier\":\"CORE\","
        + "\"taxgroup\":\"Plants\",\"oa\":\"GOLD\",\"country\":\"DE\",\"code\":\"ZOOLOGICAL\"}}").statusCode()).isEqualTo(201);
  }

  @Test
  void newFeedTokenRevokesOld() throws Exception {
    Browser b = newUser();
    JsonNode s = body(create(b, "{\"type\":\"TAXON\",\"target\":\"K1\",\"label\":\"k\"}"));
    long id = s.get("id").asLong();
    String old = subscriptions.listByUser(jdbcUser(b)).get(0).feedToken();
    HttpResponse<String> r = b.json("POST", "/api/me/subscriptions/" + id + "/feed-token", "", true);
    assertThat(r.statusCode()).isEqualTo(200);
    String fresh = subscriptions.listByUser(jdbcUser(b)).get(0).feedToken();
    assertThat(fresh).isNotEqualTo(old).matches("[A-Za-z0-9_-]{22}");
    assertThat(body(r).get("feedUrl").asString()).endsWith("/feeds/s/" + fresh + ".atom");
    assertThat(subscriptions.byFeedToken(old)).isNull();
    assertThat(subscriptions.byFeedToken(fresh)).isNotNull();
  }

  @Test
  void listShowsXrNote() throws Exception {
    Browser b = newUser();
    long moved = body(create(b, "{\"type\":\"TAXON\",\"target\":\"NEW1\",\"label\":\"moved\"}")).get("id").asLong();
    long missing = body(create(b, "{\"type\":\"TAXON\",\"target\":\"GONE\",\"label\":\"missing\"}")).get("id").asLong();
    body(create(b, "{\"type\":\"TAXON\",\"target\":\"FINE\",\"label\":\"fine\"}"));
    jdbc.update("UPDATE subscription SET target_status = 'MOVED', previous_target = 'OLD1', xr_note = 'moved to NEW1', "
        + "xr_note_at = now() WHERE id = ?", moved);
    jdbc.update("UPDATE subscription SET target_status = 'MISSING', xr_note = 'no longer in the XR', "
        + "xr_note_at = now() WHERE id = ?", missing);

    HttpResponse<String> r = b.get("/api/me/subscriptions");
    assertThat(r.statusCode()).isEqualTo(200);
    JsonNode list = body(r);
    assertThat(list.size()).isEqualTo(3);
    assertThat(list.get(0).get("targetStatus").asString()).isEqualTo("MOVED");
    assertThat(list.get(0).get("xrNote").asString()).isEqualTo("moved to NEW1");
    assertThat(list.get(0).get("previousTarget").asString()).isEqualTo("OLD1");
    assertThat(list.get(1).get("targetStatus").asString()).isEqualTo("MISSING");
    assertThat(list.get(1).get("xrNote").asString()).isEqualTo("no longer in the XR");
    assertThat(list.get(1).get("previousTarget").isNull()).isTrue();
    assertThat(list.get(2).get("targetStatus").asString()).isEqualTo("OK");
    assertThat(list.get(2).get("xrNote").isNull()).isTrue();
  }

  @Test
  void requiresSessionAndCsrf() throws Exception {
    assertThat(browser().get("/api/me/subscriptions").statusCode()).isEqualTo(401);
    Browser b = newUser();
    assertThat(b.send("POST", "/api/me/subscriptions", "application/json",
        "{\"type\":\"TAXON\",\"target\":\"C\",\"label\":\"c\"}", (String) null).statusCode()).isEqualTo(403);
  }

  @Test
  void sourceFollowShowsCeased() throws Exception {
    Browser b = newUser();
    long successor = jdbc.queryForObject("INSERT INTO source (title, tier, origin, active) VALUES ('New Journal', 'CORE', "
        + "'MANUAL', true) RETURNING id", Long.class);
    long src = jdbc.queryForObject("INSERT INTO source (title, origin, publication_status, ceased_reason, "
        + "last_issue_year, continued_by) VALUES ('Old', 'MANUAL', 'CEASED', 'TITLE_CHANGE', 2019, ?) RETURNING id",
        Long.class, successor);
    create(b, "{\"type\":\"SOURCE\",\"target\":\"" + src + "\",\"label\":\"Old\"}");
    create(b, "{\"type\":\"TAXON\",\"target\":\"ABC\",\"label\":\"Insecta\"}");
    JsonNode list = body(b.get("/api/me/subscriptions"));
    JsonNode follow = list.get(0).get("type").asString().equals("SOURCE") ? list.get(0) : list.get(1);
    JsonNode taxon = list.get(0) == follow ? list.get(1) : list.get(0);
    assertThat(follow.get("publicationStatus").asString()).isEqualTo("CEASED");
    assertThat(follow.get("lastIssueYear").asInt()).isEqualTo(2019);
    assertThat(follow.get("continuedBy").get("id").asLong()).isEqualTo(successor);
    assertThat(follow.get("continuedBy").get("title").asString()).isEqualTo("New Journal");
    assertThat(taxon.get("publicationStatus").isNull()).isTrue();
    assertThat(taxon.get("continuedBy").isNull()).isTrue();
  }
}
