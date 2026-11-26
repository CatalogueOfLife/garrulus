package org.catalogueoflife.garrulus.web.me;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.web.auth.AbstractAuthIT;
import org.catalogueoflife.garrulus.web.auth.Browser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AccountDataControllerIT extends AbstractAuthIT {

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** A user with one subscription, one hit and one digest. */
  long fullUser(String email) {
    long u = emailUser(email, null);
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, state, origin) VALUES (?, 'T', 'INDEXED', 'LIVE') RETURNING id",
        Long.class, "10.2/a" + SEQ.incrementAndGet());
    long s = jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label, feed_token) "
        + "VALUES (?, 'TAXON', 'E1', 'Export', ?) RETURNING id", Long.class, u, "exptoken" + SEQ.incrementAndGet());
    long d = jdbc.queryForObject("INSERT INTO digest (user_id, hits, status) VALUES (?, 1, 'SENT') RETURNING id", Long.class, u);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, names, digest_id) VALUES (?, ?, 'ARTICLE', '[]'::jsonb, ?)", s, w, d);
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, note) VALUES (?, 'ISSN', '1175-5326', 'my note')", u);
    return u;
  }

  @Test
  void exportContainsEverything() throws Exception {
    String email = "exp" + SEQ.incrementAndGet() + "@example.org";
    long u = fullUser(email);
    Browser b = signedInAs(u);
    HttpResponse<String> r = b.get("/api/me/export");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
    JsonNode j = JSON.readTree(r.body());
    assertThat(j.get("user").get("id").asLong()).isEqualTo(u);
    assertThat(j.get("user").get("email").asString()).isEqualTo(email);
    assertThat(j.get("user").get("digestFrequency").asString()).isEqualTo("DAILY");
    assertThat(j.get("subscriptions")).hasSize(1);
    assertThat(j.get("subscriptions").get(0).get("feedToken").asString()).startsWith("exptoken");
    assertThat(j.get("subscriptions").get(0).get("label").asString()).isEqualTo("Export");
    assertThat(j.get("hits")).hasSize(1);
    assertThat(j.get("hits").get(0).get("kind").asString()).isEqualTo("ARTICLE");
    assertThat(j.get("hits").get(0).get("digestId").isNull()).isFalse();
    assertThat(j.get("digests")).hasSize(1);
    assertThat(j.get("digests").get(0).get("status").asString()).isEqualTo("SENT");
    assertThat(j.get("registrations")).hasSize(1);
    assertThat(j.get("registrations").get(0).get("input").asString()).isEqualTo("1175-5326");
    assertThat(j.get("registrations").get(0).get("note").asString()).isEqualTo("my note");
    assertThat(browser().get("/api/me/export").statusCode()).isEqualTo(401);
  }

  @Test
  void deleteRemovesUserAndSessions() throws Exception {
    long u = fullUser("del" + SEQ.incrementAndGet() + "@example.org");
    long keep = fullUser("keep" + SEQ.incrementAndGet() + "@example.org");
    Browser b = signedInAs(u);
    Browser b2 = signedInAs(u);
    Browser k = signedInAs(keep);
    // a registration of u and one of keep, both identified on one journal with their REGISTERED signals
    long journal = jdbc.queryForObject("INSERT INTO source (title, origin) VALUES ('Registered', 'REGISTERED') "
        + "RETURNING id", Long.class);
    long[] regs = new long[2];
    long[] users = {u, keep};
    for (int i = 0; i < 2; i++) {
      regs[i] = jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, state, source_id) VALUES "
          + "(?, 'ISSN', '1175-5326', 'IDENTIFIED', ?) RETURNING id", Long.class, users[i], journal);
      jdbc.update("INSERT INTO source_signal (source_id, origin, signal_key) VALUES (?, 'REGISTERED', ?)", journal,
          String.valueOf(regs[i]));
    }
    assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class, Long.toString(u)))
        .isEqualTo(2);
    assertThat(b.json("DELETE", "/api/me", "", false).statusCode()).isEqualTo(403);
    assertThat(b.json("DELETE", "/api/me", "", true).statusCode()).isEqualTo(204);
    assertThat(jdbc.queryForObject("select count(*) from app_user where id = ?", Integer.class, u)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from subscription where user_id = ?", Integer.class, u)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from digest where user_id = ?", Integer.class, u)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from spring_session where principal_name = ?", Integer.class, Long.toString(u)))
        .isZero();
    assertThat(b.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(b2.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(k.get("/api/me").statusCode()).isEqualTo(200);
    assertThat(jdbc.queryForObject("select count(*) from subscription where user_id = ?", Integer.class, keep)).isEqualTo(1);
    // the deleted user's REGISTERED signal goes with the account (no origin left counting in the score); keep's stays
    assertThat(jdbc.queryForList("select signal_key from source_signal where source_id = ? and origin = 'REGISTERED'",
        String.class, journal)).containsExactly(String.valueOf(regs[1]));
  }
}
