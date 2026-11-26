package org.catalogueoflife.garrulus.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.catalogueoflife.garrulus.web.auth.CapturingMailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(OutputCaptureExtension.class)
class MailServiceTest {

  @Test
  void withoutMailHostNothingIsSentAndTheAddressIsNotLogged(CapturedOutput output) {
    MailService mail = new MailService(
        new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class), "Garrulus <noreply@localhost>");

    assertThat(mail.sendSignInLink("secret.person@example.org", 42L, "http://localhost:5173/signin/verify?token=t1"))
        .isFalse();
    assertThat(mail.sendSignInLink("other.person@example.org", null, "http://localhost:5173/signin/verify?token=t2"))
        .isFalse();

    assertThat(output.getOut()).contains("mail suppressed").contains("user 42").contains("new address")
        .doesNotContain("example.org").doesNotContain("token=");
  }

  @Test
  void deliverSetsExtraHeadersAndReportsTheOutcome(CapturedOutput output) throws Exception {
    CapturingMailSender sender = new CapturingMailSender();
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("sender", sender);
    MailService mail = new MailService(beans.getBeanProvider(JavaMailSender.class), "Garrulus <noreply@localhost>");
    assertThat(mail.configured()).isTrue();

    assertThat(mail.deliver("secret.person@example.org", 7L, "Subject", "text", "<p>html</p>",
        Map.of("List-Unsubscribe", "<https://g.example/api/unsubscribe?t=7.x>",
            "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"))).isEqualTo(MailService.Outcome.SENT);
    MimeMessage m = sender.sent().getFirst();
    assertThat(m.getHeader("List-Unsubscribe", null)).isEqualTo("<https://g.example/api/unsubscribe?t=7.x>");
    assertThat(m.getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");

    sender.failing(true);
    assertThat(mail.deliver("secret.person@example.org", 7L, "Subject", "text", "<p>html</p>", Map.of()))
        .isEqualTo(MailService.Outcome.FAILED);
    assertThat(output.getOut()).contains("user 7").doesNotContain("secret.person");

    MailService off = new MailService(
        new StaticListableBeanFactory().getBeanProvider(JavaMailSender.class), "Garrulus <noreply@localhost>");
    assertThat(off.configured()).isFalse();
    assertThat(off.deliver("secret.person@example.org", 7L, "Subject", "text", "<p>html</p>", Map.of()))
        .isEqualTo(MailService.Outcome.SUPPRESSED);
  }
}
