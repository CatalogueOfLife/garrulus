package org.catalogueoflife.garrulus.web.me;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.web.auth.AbstractAuthIT;
import org.catalogueoflife.garrulus.web.auth.Browser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HitControllerIT extends AbstractAuthIT {

  static final ObjectMapper JSON = new ObjectMapper();
  static final AtomicInteger SEQ = new AtomicInteger();

  long work(String title) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, publication_date, year, state, origin, text_level, authors) "
        + "VALUES (?, ?, '2026-02-03', 2026, 'INDEXED', 'LIVE', 'ABSTRACT_ONLY', '[{\"name\":\"A One\"}]'::jsonb) RETURNING id",
        Long.class, "10.1/h" + SEQ.incrementAndGet(), title);
  }

  long sub(long user, String target, String label) {
    return jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label, feed_token) "
        + "VALUES (?, 'TAXON', ?, ?, ?) RETURNING id", Long.class, user, target, label, "t" + SEQ.incrementAndGet() + target);
  }

  void hit(long sub, long work, String created) {
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, 'ARTICLE', ?::timestamptz, "
        + "'[{\"name\":\"Carabus xus\",\"nameStringId\":1}]'::jsonb)", sub, work, created);
  }

  @Test
  void hitsPagedNewestFirstWithWork() throws Exception {
    long u = emailUser("hits" + SEQ.incrementAndGet() + "@example.org", null);
    long other = emailUser("hits" + SEQ.incrementAndGet() + "@example.org", null);
    long s1 = sub(u, "H1", "First");
    long s2 = sub(u, "H2", "Second");
    long so = sub(other, "H1", "Other");
    long w1 = work("W1");
    long w2 = work("W2");
    long w3 = work("W3");
    hit(s1, w1, "2026-11-01T10:00:00Z");
    hit(s2, w2, "2026-11-03T10:00:00Z");
    hit(s1, w3, "2026-11-02T10:00:00Z");
    hit(so, w1, "2026-11-04T10:00:00Z");
    Browser b = signedInAs(u);

    JsonNode all = JSON.readTree(b.get("/api/me/hits").body());
    assertThat(all.get("results")).extracting(n -> n.get("work").get("id").asLong()).containsExactly(w2, w3, w1);
    JsonNode first = all.get("results").get(0);
    assertThat(first.get("kind").asString()).isEqualTo("ARTICLE");
    assertThat(first.get("subscriptionId").asLong()).isEqualTo(s2);
    assertThat(first.get("subscriptionLabel").asString()).isEqualTo("Second");
    assertThat(first.get("names").get(0).get("name").asString()).isEqualTo("Carabus xus");
    assertThat(first.get("created").asString()).startsWith("2026-11-03");
    assertThat(first.get("work").get("title").asString()).isEqualTo("W2");
    assertThat(first.get("work").get("authors").get(0).asString()).isEqualTo("A One");

    JsonNode page = JSON.readTree(b.get("/api/me/hits?limit=1&offset=1").body());
    assertThat(page.get("results")).hasSize(1);
    assertThat(page.get("results").get(0).get("work").get("id").asLong()).isEqualTo(w3);
    assertThat(page.get("limit").asInt()).isEqualTo(1);
    assertThat(page.get("offset").asInt()).isEqualTo(1);

    JsonNode one = JSON.readTree(b.get("/api/me/hits?subscription=" + s1).body());
    assertThat(one.get("results")).hasSize(2);
    // another user's subscription id gives nothing
    assertThat(JSON.readTree(b.get("/api/me/hits?subscription=" + so).body()).get("results")).isEmpty();

    assertThat(b.get("/api/me/hits?limit=101").statusCode()).isEqualTo(400);
    assertThat(b.get("/api/me/hits?limit=0").statusCode()).isEqualTo(400);
    assertThat(b.get("/api/me/hits?offset=10001").statusCode()).isEqualTo(400);
    assertThat(browser().get("/api/me/hits").statusCode()).isEqualTo(401);
  }
}
