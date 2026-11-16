package org.catalogueoflife.nametracker.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

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
}
