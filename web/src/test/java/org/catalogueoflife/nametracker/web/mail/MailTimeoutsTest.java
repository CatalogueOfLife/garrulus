package org.catalogueoflife.nametracker.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** JavaMail waits forever by default: a stalled SMTP host would block the digest scheduler thread. */
class MailTimeoutsTest {

  @Test
  void applicationYmlSetsSmtpTimeouts() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
        .withInitializer(ctx -> {
          try {
            new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(ps -> ctx.getEnvironment().getPropertySources().addLast(ps));
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        })
        .withPropertyValues("spring.mail.host=localhost")
        .run(ctx -> {
          Properties p = ((JavaMailSenderImpl) ctx.getBean(JavaMailSender.class)).getJavaMailProperties();
          assertThat(p.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
          assertThat(p.getProperty("mail.smtp.timeout")).isEqualTo("30000");
          assertThat(p.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");
        });
  }
}
