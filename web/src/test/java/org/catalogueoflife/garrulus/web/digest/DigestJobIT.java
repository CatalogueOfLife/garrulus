package org.catalogueoflife.garrulus.web.digest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.catalogueoflife.garrulus.web.auth.AbstractAuthIT;
import org.catalogueoflife.garrulus.web.auth.CapturingMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.AopTestUtils;

class DigestJobIT extends AbstractAuthIT {

  static final AtomicInteger SEQ = new AtomicInteger();

  @Autowired DigestJob job;
  @Autowired UnsubscribeTokens tokens;
  @Value("${garrulus.public-url}") String publicUrl;

  String address() {
    return "digest" + SEQ.incrementAndGet() + "@example.org";
  }

  /** A verified DAILY user, due every hour of the day (digest_hour 0, UTC) until a digest went out. */
  long user(String email, String frequency, Instant digestSince) {
    return jdbc.queryForObject("INSERT INTO app_user (email, email_verified, digest_since, digest_frequency, "
        + "digest_hour, time_zone) VALUES (?, now(), ?, ?, 0, 'UTC') RETURNING id", Long.class, email,
        Timestamp.from(digestSince), frequency);
  }

  long subscription(long user, String label) {
    return jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label) VALUES (?, 'TAXON', ?, ?) "
        + "RETURNING id", Long.class, user, "T" + SEQ.incrementAndGet(), label);
  }

  long work(String title) {
    long source = jdbc.queryForObject("INSERT INTO source (title, tier, origin, active) VALUES ('Zootaxa', 'CORE', "
        + "'MANUAL', true) RETURNING id", Long.class);
    return jdbc.queryForObject("INSERT INTO work (doi, title, publication_date, year, state, origin, text_level, "
        + "source_id, first_indexed_at) VALUES (?, ?, current_date, 2026, 'INDEXED', 'LIVE', 'ABSTRACT_ONLY', ?, now()) "
        + "RETURNING id", Long.class, "10.1/d" + SEQ.incrementAndGet(), title, source);
  }

  long hit(long subscription, long work, String kind, Instant created, String name) {
    return jdbc.queryForObject("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, ?, ?, "
        + "?::jsonb) RETURNING id", Long.class, subscription, work, kind, Timestamp.from(created),
        "[{\"nameStringId\":1,\"name\":\"" + name + "\",\"actTypes\":[]}]");
  }

  Long digestOf(long hit) {
    return jdbc.queryForObject("SELECT digest_id FROM hit WHERE id = ?", Long.class, hit);
  }

  List<String> digestRows(long user) {
    return jdbc.queryForList("SELECT status || ':' || hits || ':' || coalesce(error, '') FROM digest WHERE user_id = ? "
        + "ORDER BY id", String.class, user);
  }

  static Instant ago(long hours) {
    return Instant.now().minus(hours, ChronoUnit.HOURS);
  }

  @Test
  void sendsUndeliveredHitsOnceAndMarksThem() throws Exception {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 30));
    long s = subscription(u, "Carabidae");
    long h1 = hit(s, work("First beetle paper"), "ARTICLE", ago(2), "Carabus xus");
    long h2 = hit(s, work("Second beetle paper"), "ARTICLE", ago(1), "Carabus yus");

    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(true, 2));
    List<MimeMessage> mails = mail.to(email);
    assertThat(mails).hasSize(1);
    MimeMessage m = mails.getFirst();
    assertThat(m.getSubject()).isEqualTo("Garrulus: 2 new papers");
    String text = CapturingMailSender.plainText(m);
    assertThat(text).contains("First beetle paper").contains("Second beetle paper").contains("Carabus xus")
        .contains(publicUrl + "/unsubscribe?t=" + tokens.create(u));
    assertThat(CapturingMailSender.html(m)).contains("Second beetle paper");
    assertThat(m.getHeader("List-Unsubscribe", null))
        .isEqualTo("<" + publicUrl + "/api/unsubscribe?t=" + tokens.create(u) + ">");
    assertThat(m.getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
    assertThat(digestRows(u)).containsExactly("SENT:2:");
    assertThat(digestOf(h1)).isNotNull().isEqualTo(digestOf(h2));

    // not due again today, and nothing left anyway
    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(false, 0));
    // a new hit goes out with the next digest only (forced here), never the old ones again
    long h3 = hit(s, work("Third beetle paper"), "ARTICLE", Instant.now(), "Carabus zus");
    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(false, 0));
    assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(true, 1));
    assertThat(mail.to(email)).hasSize(2);
    assertThat(CapturingMailSender.plainText(mail.to(email).getLast())).contains("Third beetle paper")
        .doesNotContain("First beetle paper");
    assertThat(digestOf(h3)).isNotNull().isNotEqualTo(digestOf(h1));
  }

  @Test
  void noMailWithoutHits() {
    String email = address();
    long u = user(email, "DAILY", ago(24));
    subscription(u, "Nothing here");
    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(false, 0));
    assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(false, 0));
    assertThat(mail.to(email)).isEmpty();
    assertThat(digestRows(u)).isEmpty();
  }

  @Test
  void hitsBeforeDigestSinceNeverMailed() {
    String email = address();
    long u = user(email, "DAILY", ago(1));
    long s = subscription(u, "Late verifier");
    long h = hit(s, work("Found before verifying"), "ARTICLE", ago(3), "Aus bus");
    assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(false, 0));
    assertThat(mail.to(email)).isEmpty();
    assertThat(digestRows(u)).isEmpty();
    assertThat(digestOf(h)).isNull();
  }

  @Test
  void hitsOlderThan14DaysNotMailedButListed() throws Exception {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 60));
    long s = subscription(u, "Paused a while");
    long old = hit(s, work("Fifteen days old"), "ARTICLE", ago(24 * 15), "Aus bus");
    assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(false, 0));
    assertThat(mail.to(email)).isEmpty();
    assertThat(digestOf(old)).isNull();
    // still on "My hits"
    HttpResponse<String> r = signedInAs(u).get("/api/me/hits");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("Fifteen days old");

    // a weekly digest reaches back 21 days
    String weekly = address();
    long w = user(weekly, "WEEKLY", ago(24 * 60));
    long ws = subscription(w, "Weekly");
    hit(ws, work("Fifteen days, weekly"), "ARTICLE", ago(24 * 15), "Aus bus");
    hit(ws, work("Twenty-two days, weekly"), "ARTICLE", ago(24 * 22), "Aus bus");
    assertThat(job.runFor(w, true)).isEqualTo(new DigestJob.Result(true, 1));
    assertThat(CapturingMailSender.plainText(mail.to(weekly).getFirst())).contains("Fifteen days, weekly")
        .doesNotContain("Twenty-two days");
  }

  @Test
  void sendFailureUnmarksAndRecordsFailed() throws Exception {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 30));
    long s = subscription(u, "Flaky host");
    long h = hit(s, work("Waiting for the mail host"), "ARTICLE", ago(1), "Aus bus");
    mail.failing(true);
    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(false, 1));
    assertThat(digestOf(h)).isNull();
    assertThat(digestRows(u)).hasSize(1).allMatch(r -> r.startsWith("FAILED:1:") && !r.contains("@"));
    // the next run tries again: a FAILED digest does not count as sent
    mail.failing(false);
    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(true, 1));
    assertThat(mail.to(email)).hasSize(1);
    assertThat(digestOf(h)).isNotNull();
    assertThat(digestRows(u)).hasSize(2).last().asString().startsWith("SENT:1:");
  }

  @Test
  void adminRunForcesSend() throws Exception {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 30));
    long s = subscription(u, "Admin test");
    hit(s, work("Forced paper"), "ARTICLE", ago(1), "Aus bus");
    // a digest went out today already, so the user is not due
    jdbc.update("INSERT INTO digest (user_id, hits, status) VALUES (?, 0, 'SENT')", u);
    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(false, 0));

    assertThat(admin("{\"userId\":" + u + "}", "wrong").statusCode()).isEqualTo(401);
    HttpResponse<String> r = admin("{\"userId\":" + u + "}", "test-token");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).isEqualTo("{\"sent\":true,\"hits\":1}");
    assertThat(mail.to(email)).hasSize(1);
    r = admin("{\"userId\":" + u + "}", "test-token");
    assertThat(r.body()).isEqualTo("{\"sent\":false,\"hits\":0}");
    assertThat(admin("{\"userId\":999999999}", "test-token").statusCode()).isEqualTo(404);
  }

  @Test
  void concurrentRunKeepsItsHitsAndNothingIsSent() {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 30));
    long s = subscription(u, "Raced");
    long taken = hit(s, work("Taken by the other run"), "ARTICLE", ago(2), "Aus bus");
    long left = hit(s, work("Left for the other run"), "ARTICLE", ago(1), "Aus cus");
    long other = jdbc.queryForObject("INSERT INTO digest (user_id, hits, status) VALUES (?, 1, 'SENT') RETURNING id",
        Long.class, u);
    // another run marks one of the collected hits between collect and mark; the seam is a field of the bean
    // itself, not of the ShedLock proxy
    DigestJob target = AopTestUtils.getUltimateTargetObject(job);
    target.beforeMark = ids -> jdbc.update("UPDATE hit SET digest_id = ? WHERE id = ?", other, taken);
    try {
      assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(false, 0));
    } finally {
      target.beforeMark = ids -> {};
    }
    assertThat(mail.to(email)).isEmpty();
    // the whole transaction rolled back: no digest row of this run, the other hit untouched
    assertThat(digestRows(u)).containsExactly("SENT:1:");
    assertThat(digestOf(taken)).isEqualTo(other);
    assertThat(digestOf(left)).isNull();
  }

  @Test
  void broadFollowSpansSeveralSummaryChunks() throws Exception {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 30));
    long s = subscription(u, "Broad");
    int n = DigestJob.SUMMARY_CHUNK + 1;
    String prefix = "10.1/broad" + SEQ.incrementAndGet() + "-";
    jdbc.update("INSERT INTO work (doi, title, publication_date, year, state, origin, text_level, first_indexed_at) "
        + "SELECT ? || g, 'Broad paper ' || g, current_date, 2026, 'INDEXED', 'LIVE', 'ABSTRACT_ONLY', now() "
        + "FROM generate_series(1, ?) g", prefix, n);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, created, names) SELECT ?, id, 'ARTICLE', "
        + "now() - interval '1 hour', '[]'::jsonb FROM work WHERE doi LIKE ?", s, prefix + "%");
    assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(true, n));
    assertThat(CapturingMailSender.plainText(mail.to(email).getFirst())).contains("and " + (n - 20) + " more");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit WHERE subscription_id = ? AND digest_id IS NULL",
        Long.class, s)).isZero();
  }

  @Test
  void scheduledRunPurgesExpiredTokens() {
    long u = user(address(), "OFF", ago(1));
    jdbc.update("INSERT INTO email_token (user_id, email, token_hash, expires) VALUES (?, 'x@example.org', ?, "
        + "now() - interval '1 hour')", u, "expired" + SEQ.incrementAndGet());
    jdbc.update("INSERT INTO one_time_tokens (token_value, username, expires_at) VALUES (?, 'x@example.org', ?)",
        "ott" + SEQ.incrementAndGet(), Timestamp.from(ago(1)));
    job.purgeTokens();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM email_token WHERE expires < now()", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM one_time_tokens WHERE expires_at < ?", Long.class,
        Timestamp.from(Instant.now()))).isZero();
  }

  HttpResponse<String> admin(String body, String token) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/api/admin/digests/run"))
        .header("Content-Type", "application/json").header("X-Admin-Token", token)
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }

  @Autowired org.catalogueoflife.garrulus.store.account.SubscriptionMapper subscriptionMapper;

  @Test
  void ceasedNoteAppearsOnce() {
    String email = address();
    long u = user(email, "DAILY", ago(24 * 30));
    long s = subscription(u, "Carabidae");
    long journal = jdbc.queryForObject("INSERT INTO source (title, tier, origin, active) VALUES ('Old Journal', 'CORE', "
        + "'MANUAL', true) RETURNING id", Long.class);
    jdbc.update("INSERT INTO subscription (user_id, type, target, label) VALUES (?, 'SOURCE', ?, 'Old Journal')", u,
        String.valueOf(journal));
    hit(s, work("A beetle paper"), "ARTICLE", ago(2), "Carabus xus");
    subscriptionMapper.noteSourceFollows(journal, "Ceased publication · last issue 2019. Continued as New Journal.");

    assertThat(job.runFor(u, false)).isEqualTo(new DigestJob.Result(true, 1));
    assertThat(CapturingMailSender.plainText(mail.to(email).getFirst()))
        .contains("Ceased publication · last issue 2019. Continued as New Journal.");

    hit(s, work("Another beetle paper"), "ARTICLE", Instant.now(), "Carabus yus");
    assertThat(job.runFor(u, true)).isEqualTo(new DigestJob.Result(true, 1));
    assertThat(CapturingMailSender.plainText(mail.to(email).getLast())).doesNotContain("Ceased publication");
  }
}
