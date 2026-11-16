package org.catalogueoflife.nametracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountMergerIT extends AbstractAuthIT {

  long sub(long userId, String type, String target, String label, String created) {
    return jdbc.queryForObject("insert into subscription (user_id, type, target, label, created) "
        + "values (?, ?, ?, ?, ?::timestamptz) returning id", Long.class, userId, type, target, label, created);
  }

  long work() {
    return jdbc.queryForObject("insert into work (doi, origin, state) values (?, 'LIVE', 'INDEXED') returning id",
        Long.class, "10.1/merge" + System.nanoTime());
  }

  void hit(long subscriptionId, long workId, String kind) {
    jdbc.update("insert into hit (subscription_id, work_id, kind) values (?, ?, ?)", subscriptionId, workId, kind);
  }

  /** The requester asks for the address and uses the link; returns the verify-email answer. */
  HttpResponse<String> claim(Browser b, String email) throws Exception {
    HttpResponse<String> requested = b.json("POST", "/api/me/email", "{\"email\":\"" + email + "\"}", true);
    assertThat(requested.statusCode()).isEqualTo(202);
    return b.json("POST", "/api/auth/verify-email", "{\"token\":\"" + mail.lastTokenFor(email) + "\"}", true);
  }

  long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  @Test
  void mergesEmailOnlyAccountIntoOrcidSession() throws Exception {
    String email = SignInLinkIT.address("holder");
    long holder = emailUser(email, null);
    String orcid = orcid();
    long requester = orcidUser(orcid);
    sub(holder, "TAXON", "T1", "holder's", "2026-01-01T00:00:00Z");
    long moved = sub(requester, "SOURCE", "S1", "requester's", "2026-02-01T00:00:00Z");
    Browser b = signedInAs(requester);
    Browser otherDevice = signedInAs(requester);
    String sessionBefore = b.cookie("SESSION").orElseThrow();

    HttpResponse<String> verified = claim(b, email);
    assertThat(verified.statusCode()).isEqualTo(200);
    assertThat(verified.body()).isEqualTo("{\"outcome\":\"MERGED\"}");

    // the survivor holds the address and keeps its id; it gains the ORCID iD and the requester's follows
    Map<String, Object> survivor = jdbc.queryForMap("select email, orcid, display_name from app_user where id = ?",
        holder);
    assertThat(survivor.get("email")).isEqualTo(email);
    assertThat(survivor.get("orcid")).isEqualTo(orcid);
    assertThat(survivor.get("display_name")).isEqualTo("Ada");
    assertThat(count("select count(*) from app_user where id = ?", requester)).isZero();
    assertThat(jdbc.queryForList("select id from subscription where user_id = ?", Long.class, holder))
        .hasSize(2).contains(moved);

    // this session now belongs to the survivor, under a new session id; the requester's other sessions are gone
    HttpResponse<String> me = b.get("/api/me");
    assertThat(me.statusCode()).isEqualTo(200);
    assertThat(me.body()).contains("\"id\":" + holder).contains("\"orcid\":\"" + orcid + "\"");
    assertThat(b.cookie("SESSION").orElseThrow()).isNotEqualTo(sessionBefore);
    assertThat(otherDevice.get("/api/me").statusCode()).isEqualTo(401);
    assertThat(count("select count(*) from spring_session where principal_name = ?", String.valueOf(requester)))
        .isZero();
    assertThat(count("select count(*) from spring_session where principal_name = ?", String.valueOf(holder)))
        .isEqualTo(1);
  }

  @Test
  void mergeKeepsOlderDuplicateSubscription() throws Exception {
    String email = SignInLinkIT.address("dup");
    long holder = emailUser(email, null);
    long requester = orcidUser(orcid());
    // the requester's (the loser's) follow is the older one: it stays, with its label, on the survivor
    long newer = sub(holder, "TAXON", "T1", "newer", "2026-03-01T00:00:00Z");
    long older = sub(requester, "TAXON", "T1", "older", "2026-01-01T00:00:00Z");
    jdbc.update("update subscription set new_names_only = true where id = ?", older);

    assertThat(claim(signedInAs(requester), email).body()).isEqualTo("{\"outcome\":\"MERGED\"}");

    List<Map<String, Object>> subs = jdbc.queryForList(
        "select id, label, new_names_only from subscription where user_id = ?", holder);
    assertThat(subs).hasSize(1);
    assertThat(subs.getFirst().get("id")).isEqualTo(older);
    assertThat(subs.getFirst().get("label")).isEqualTo("older");
    assertThat(subs.getFirst().get("new_names_only")).isEqualTo(true);
    assertThat(count("select count(*) from subscription where id = ?", newer)).isZero();
  }

  @Test
  void mergeMovesHitsWithoutClash() throws Exception {
    String email = SignInLinkIT.address("hits");
    long holder = emailUser(email, null);
    long requester = orcidUser(orcid());
    long keep = sub(holder, "TAXON", "T1", "older", "2026-01-01T00:00:00Z");
    long drop = sub(requester, "TAXON", "T1", "newer", "2026-02-01T00:00:00Z");
    long w1 = work(), w2 = work();
    hit(keep, w1, "ARTICLE");
    hit(drop, w1, "ARTICLE");
    hit(drop, w1, "NEW_NAMES");
    hit(drop, w2, "ARTICLE");

    assertThat(claim(signedInAs(requester), email).body()).isEqualTo("{\"outcome\":\"MERGED\"}");

    assertThat(jdbc.queryForList("select work_id || ':' || kind from hit where subscription_id = ? order by 1",
        String.class, keep)).containsExactlyInAnyOrder(w1 + ":ARTICLE", w1 + ":NEW_NAMES", w2 + ":ARTICLE");
    assertThat(count("select count(*) from hit where subscription_id = ?", drop)).isZero();
  }

  @Test
  void refusesWhenOtherAccountHasDifferentOrcid() throws Exception {
    String email = SignInLinkIT.address("taken");
    String holderOrcid = orcid();
    long holder = emailUser(email, holderOrcid);
    String requesterOrcid = orcid();
    long requester = orcidUser(requesterOrcid);
    sub(requester, "TAXON", "T1", "mine", "2026-01-01T00:00:00Z");
    Browser b = signedInAs(requester);

    HttpResponse<String> verified = claim(b, email);
    assertThat(verified.statusCode()).isEqualTo(200);
    assertThat(verified.body()).isEqualTo("{\"outcome\":\"REFUSED_OTHER_ORCID\"}");

    // nothing changes
    assertThat(jdbc.queryForMap("select email, orcid from app_user where id = ?", holder))
        .containsEntry("email", email).containsEntry("orcid", holderOrcid);
    assertThat(jdbc.queryForMap("select email, orcid from app_user where id = ?", requester))
        .containsEntry("email", null).containsEntry("orcid", requesterOrcid);
    assertThat(count("select count(*) from subscription where user_id = ?", requester)).isEqualTo(1);
    assertThat(b.get("/api/me").body()).contains("\"id\":" + requester);
  }

  @Test
  void mergeIgnoresCap() throws Exception {
    String email = SignInLinkIT.address("cap");
    long holder = emailUser(email, null);
    long requester = orcidUser(orcid());
    for (int i = 0; i < 60; i++) {
      sub(holder, "TAXON", "H" + i, null, "2026-01-01T00:00:00Z");
      sub(requester, "TAXON", "R" + i, null, "2026-01-01T00:00:00Z");
    }

    assertThat(claim(signedInAs(requester), email).body()).isEqualTo("{\"outcome\":\"MERGED\"}");

    assertThat(count("select count(*) from subscription where user_id = ?", holder)).isEqualTo(120);
  }
}
