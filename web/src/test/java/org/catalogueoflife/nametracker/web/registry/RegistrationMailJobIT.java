package org.catalogueoflife.nametracker.web.registry;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.web.auth.AbstractAuthIT;
import org.catalogueoflife.nametracker.web.auth.CapturingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

class RegistrationMailJobIT extends AbstractAuthIT {

  /** The bean behind its ShedLock proxy: direct calls are locked for lockAtLeastFor otherwise. */
  @Autowired RegistrationMailJob proxied;
  RegistrationMailJob job;

  @BeforeEach
  void clean() {
    job = AopTestUtils.getTargetObject(proxied);
    jdbc.update("DELETE FROM admin_notice");
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM app_user WHERE email LIKE '%@reg-mail.test' OR email IS NULL AND display_name = 'Ada'");
  }

  long source(String title, String tier) {
    return jdbc.queryForObject("INSERT INTO source (title, origin, tier, issns) VALUES (?, 'REGISTERED', ?, "
        + "'{1175-5326}') RETURNING id", Long.class, title, tier);
  }

  long registration(long userId, String input, long sourceId, String tierAtIdentification, String state) {
    return jdbc.queryForObject("INSERT INTO source_registration (user_id, kind, input, state, outcome, source_id, "
        + "tier_at_identification, identified_at) VALUES (?, 'URL', ?, ?, 'NEW_CANDIDATE', ?, ?, now()) RETURNING id",
        Long.class, userId, input, state, sourceId, tierAtIdentification);
  }

  void tier(long sourceId, String tier) {
    jdbc.update("UPDATE source SET tier = ? WHERE id = ?", tier, sourceId);
  }

  String decision(long regId) {
    return jdbc.queryForObject("SELECT decision FROM source_registration WHERE id = ?", String.class, regId);
  }

  @Test
  void acceptedMailsOnce() throws Exception {
    long u = emailUser("ada@reg-mail.test", null);
    long src = source("Acta <i>Zoologica</i>", "CANDIDATE");
    long reg = registration(u, "https://acta.example.org/?a=<b>", src, "CANDIDATE", "IDENTIFIED");
    job.decisions();
    assertThat(mail.sent()).isEmpty(); // still a candidate
    assertThat(decision(reg)).isNull();

    tier(src, "BROAD");
    job.decisions();
    job.decisions();
    List<MimeMessage> sent = mail.to("ada@reg-mail.test");
    assertThat(sent).hasSize(1);
    MimeMessage m = sent.getFirst();
    assertThat(m.getSubject()).isEqualTo("Your registered journal Acta <i>Zoologica</i> is now harvested");
    String text = CapturingMailSender.plainText(m);
    assertThat(text).contains("Acta <i>Zoologica</i>").contains("https://acta.example.org/?a=<b>")
        .contains("http://localhost:5173/source/" + src);
    String html = CapturingMailSender.html(m);
    assertThat(html).contains("Acta &lt;i&gt;Zoologica&lt;/i&gt;").contains("?a=&lt;b&gt;")
        .doesNotContain("<i>Zoologica").doesNotContain("<b>");
    assertThat(decision(reg)).isEqualTo("ACCEPTED");
    assertThat(jdbc.queryForObject("SELECT decision_at IS NOT NULL FROM source_registration WHERE id = ?",
        Boolean.class, reg)).isTrue();
    // the admin changes their mind: no second mail
    tier(src, "EXCLUDED");
    job.decisions();
    assertThat(mail.sent()).hasSize(1);
  }

  @Test
  void declinedMailsOnce() throws Exception {
    long u = emailUser("bob@reg-mail.test", null);
    long src = source("Journal of Nothing", "CANDIDATE");
    long reg = registration(u, "1175-5326", src, "CANDIDATE", "IDENTIFIED");
    tier(src, "EXCLUDED");
    job.decisions();
    job.decisions();
    List<MimeMessage> sent = mail.to("bob@reg-mail.test");
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().getSubject()).isEqualTo("Your registered journal Journal of Nothing will not be "
        + "harvested");
    assertThat(decision(reg)).isEqualTo("DECLINED");
  }

  @Test
  void coveredAtRegistrationNeverMails() {
    long u = emailUser("cy@reg-mail.test", null);
    long core = source("Zootaxa", "CORE");
    long covered = registration(u, "1175-5326", core, "CORE", "IDENTIFIED");
    // withdrawn before the decision: no mail either
    long src = source("Withdrawn Journal", "CANDIDATE");
    long withdrawn = registration(u, "1234-5679", src, "CANDIDATE", "WITHDRAWN");
    tier(src, "CORE");
    job.decisions();
    assertThat(mail.sent()).isEmpty();
    assertThat(decision(covered)).isNull();
    assertThat(decision(withdrawn)).isNull();

    // an account without a verified address: decided, mail suppressed
    long noAddress = orcidUser(orcid());
    long src2 = source("Quiet Journal", "CANDIDATE");
    long quiet = registration(noAddress, "1175-5326", src2, "CANDIDATE", "IDENTIFIED");
    tier(src2, "CORE");
    job.decisions();
    assertThat(mail.sent()).isEmpty();
    assertThat(decision(quiet)).isEqualTo("ACCEPTED");
  }

  @Test
  void dailyNoteListsNewCandidatesOnce() {
    long u = emailUser("dee@reg-mail.test", null);
    job.adminNote();
    assertThat(notices()).isEmpty(); // nothing waits

    long a = source("Acta <Waiting>", "CANDIDATE");
    long b = source("Journal Two", "CANDIDATE");
    long decided = source("Decided Journal", "CANDIDATE");
    registration(u, "https://acta.example.org/", a, "CANDIDATE", "IDENTIFIED");
    registration(u, "1175-5326", b, "CANDIDATE", "IDENTIFIED");
    registration(u, "1234-5679", decided, "CANDIDATE", "IDENTIFIED");
    tier(decided, "CORE"); // no longer waiting
    registration(u, "1175-5334", source("Covered", "CORE"), "CORE", "IDENTIFIED");
    job.adminNote();
    job.adminNote();
    List<Map<String, Object>> rows = notices();
    assertThat(rows).hasSize(1);
    Map<String, Object> n = rows.getFirst();
    assertThat(n.get("kind")).isEqualTo("REGISTRATIONS_WAITING");
    assertThat((String) n.get("dedup_key")).matches("registrations:\\d{4}-\\d{2}-\\d{2}");
    assertThat((String) n.get("subject")).isEqualTo("Garrulus: 2 registered journals wait for a decision");
    String body = (String) n.get("body");
    assertThat(body).contains("Acta <Waiting>").contains("https://acta.example.org/")
        .contains("http://localhost:5173/admin/journals/" + a).contains("Journal Two").contains("1175-5326")
        .contains("http://localhost:5173/admin/journals/" + b).doesNotContain("Decided Journal")
        .doesNotContain("Covered");

    // the next day: only registrations identified since that note count
    jdbc.update("UPDATE admin_notice SET dedup_key = 'registrations:2000-01-01', created = now() - interval '1 day'");
    jdbc.update("UPDATE source_registration SET identified_at = now() - interval '2 days'");
    job.adminNote();
    assertThat(notices()).hasSize(1);
    long c = source("Journal Three", "CANDIDATE");
    registration(u, "0002-936X", c, "CANDIDATE", "IDENTIFIED");
    job.adminNote();
    rows = notices();
    assertThat(rows).hasSize(2);
    assertThat((String) rows.get(1).get("subject")).isEqualTo("Garrulus: 1 registered journal waits for a decision");
    assertThat((String) rows.get(1).get("body")).contains("Journal Three").doesNotContain("Journal Two");
  }

  List<Map<String, Object>> notices() {
    return jdbc.queryForList("SELECT kind, dedup_key, subject, body FROM admin_notice ORDER BY id");
  }
}
