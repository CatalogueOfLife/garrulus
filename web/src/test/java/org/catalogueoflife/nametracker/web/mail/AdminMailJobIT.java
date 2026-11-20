package org.catalogueoflife.nametracker.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.nametracker.web.auth.AbstractAuthIT;
import org.catalogueoflife.nametracker.web.auth.CapturingMailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;

@TestPropertySource(properties = "nametracker.admin.orcids=0000-0002-1825-0097,0000-0001-5109-3700")
class AdminMailJobIT extends AbstractAuthIT {

  static final String ADMIN_A = "0000-0002-1825-0097";
  static final String ADMIN_B = "0000-0001-5109-3700";

  /** The bean behind its ShedLock proxy: direct calls are locked for lockAtLeastFor otherwise. */
  @Autowired AdminMailJob proxied;
  AdminMailJob job;

  @BeforeEach
  void clean() {
    job = AopTestUtils.getTargetObject(proxied);
    jdbc.update("DELETE FROM admin_notice");
    jdbc.update("DELETE FROM app_user");
  }

  void notice(String key) {
    jdbc.update("INSERT INTO admin_notice (kind, dedup_key, subject, body) VALUES ('LAYER_FAILING', ?, ?, 'Layer OAI "
        + "is FAILING\nhttp://localhost:5173/admin/layers')", key, "Garrulus: layer OAI is failing " + key);
  }

  List<String> outcomes() {
    return jdbc.queryForList("SELECT coalesce(outcome, 'none') FROM admin_notice ORDER BY id", String.class);
  }

  @Test
  void mailsEveryAdminOnce() throws Exception {
    emailUser("a@example.org", ADMIN_A);
    emailUser("b@example.org", ADMIN_B);
    notice("k1");
    job.run();
    assertThat(mail.to("a@example.org")).hasSize(1);
    assertThat(mail.to("b@example.org")).hasSize(1);
    var m = mail.to("a@example.org").getFirst();
    assertThat(m.getSubject()).isEqualTo("Garrulus: layer OAI is failing k1");
    assertThat(CapturingMailSender.plainText(m)).contains("http://localhost:5173/admin/layers");
    assertThat(outcomes()).containsExactly("SENT");
  }

  @Test
  void noAdminAddressIsSuppressed() {
    orcidUser(ADMIN_A); // an admin without a verified address
    notice("k1");
    job.run();
    assertThat(mail.sent()).isEmpty();
    assertThat(outcomes()).containsExactly("SUPPRESSED");
  }

  @Test
  void nonAdminVerifiedAccountsGetNothing() {
    emailUser("plain@example.org", null);
    emailUser("other@example.org", orcid());
    emailUser("a@example.org", ADMIN_A);
    notice("k1");
    job.run();
    assertThat(mail.sent()).hasSize(1);
    assertThat(mail.to("a@example.org")).hasSize(1);
  }

  @Test
  void secondRunSendsNothing() {
    emailUser("a@example.org", ADMIN_A);
    notice("k1");
    job.run();
    job.run();
    assertThat(mail.sent()).hasSize(1);
    notice("k2");
    job.run();
    assertThat(mail.sent()).hasSize(2);
  }

  @Test
  void aClaimThatNeverGotItsOutcomeIsMailedAgain() throws Exception {
    emailUser("a@example.org", ADMIN_A);
    notice("crashed"); // claimed by a run that died before sending: mailed_at set, no outcome
    jdbc.update("UPDATE admin_notice SET mailed_at = now() - interval '11 minutes'");
    notice("recent"); // claimed a minute ago by a run that may still be sending
    jdbc.update("UPDATE admin_notice SET mailed_at = now() - interval '1 minute' WHERE dedup_key = 'recent'");
    job.run();
    assertThat(mail.sent()).hasSize(1);
    assertThat(mail.to("a@example.org").getFirst().getSubject()).endsWith("crashed");
    assertThat(outcomes()).containsExactly("SENT", "none");
  }

  @Test
  void aFailedSendIsNotRetried() {
    emailUser("a@example.org", ADMIN_A);
    notice("k1");
    mail.failing(true);
    job.run();
    mail.failing(false);
    job.run();
    assertThat(mail.sent()).isEmpty();
    assertThat(outcomes()).containsExactly("FAILED");
  }
}
