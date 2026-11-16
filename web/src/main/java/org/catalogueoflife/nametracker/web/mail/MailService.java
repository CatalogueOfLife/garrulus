package org.catalogueoflife.nametracker.web.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/**
 * Best-effort outgoing mail (adapted from Blixa's EmailService), multipart text and HTML. Sends only when a
 * JavaMailSender is configured ({@code spring.mail.host}); otherwise it logs "mail suppressed". Logs name the
 * recipient by account id ("new address" before there is one), never by address, and never throw.
 */
@Service
public class MailService {

  private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

  private final ObjectProvider<JavaMailSender> sender;
  private final String from;

  public MailService(ObjectProvider<JavaMailSender> sender, @Value("${nametracker.mail.from}") String from) {
    this.sender = sender;
    this.from = from;
  }

  /** What became of a message. */
  public enum Outcome {
    /** Handed to the mail host. */
    SENT,
    /** Not sent: no mail host is configured ({@code spring.mail.host}). */
    SUPPRESSED,
    /** The mail host refused it or could not be reached. */
    FAILED
  }

  /** True when a mail host is configured, so mails can go out at all. */
  public boolean configured() {
    return sender.getIfAvailable() != null;
  }

  /** True if the message was handed to the mail host. */
  public boolean send(String to, @Nullable Long userId, String subject, String text, String html) {
    return deliver(to, userId, subject, text, html, Map.of()) == Outcome.SENT;
  }

  /** Sends a multipart text and HTML message with extra headers (e.g. List-Unsubscribe); never throws. */
  public Outcome deliver(String to, @Nullable Long userId, String subject, String text, String html,
      Map<String, String> headers) {
    String recipient = userId == null ? "new address" : "user " + userId;
    JavaMailSender s = sender.getIfAvailable();
    if (s == null) {
      LOG.info("mail suppressed (no spring.mail.host): \"{}\" to {}", subject, recipient);
      return Outcome.SUPPRESSED;
    }
    try {
      MimeMessage message = s.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from);
      helper.setTo(to);
      helper.setSubject(subject);
      helper.setText(text, html);
      for (Map.Entry<String, String> h : headers.entrySet()) {
        message.setHeader(h.getKey(), h.getValue());
      }
      s.send(message);
      LOG.info("mail \"{}\" sent to {}", subject, recipient);
      return Outcome.SENT;
    } catch (MessagingException | MailException e) {
      // the exception message may quote the address: log its type only
      LOG.warn("mail \"{}\" to {} failed: {}", subject, recipient, e.getClass().getSimpleName());
      return Outcome.FAILED;
    }
  }

  /** The email sign-in link: works once, for 15 minutes. */
  public boolean sendSignInLink(String to, @Nullable Long userId, String link) {
    String text = """
        Hello,

        use this link to sign in to Garrulus. It works once and expires in 15 minutes:

        %s

        If you did not ask to sign in, you can ignore this email.

        Garrulus, the name-literature index of the Catalogue of Life
        """.formatted(link);
    String href = HtmlUtils.htmlEscape(link);
    String html = """
        <!doctype html>
        <html><body style="font-family: sans-serif; line-height: 1.5">
        <p>Hello,</p>
        <p>use this link to sign in to Garrulus. It works once and expires in 15 minutes:</p>
        <p><a href="%s">Sign in to Garrulus</a></p>
        <p>If you did not ask to sign in, you can ignore this email.</p>
        <p>Garrulus, the name-literature index of the Catalogue of Life</p>
        </body></html>
        """.formatted(href);
    return send(to, userId, "Your Garrulus sign-in link", text, html);
  }

  /** The link that confirms a new address of an account: goes to the new address, expires in 24 hours. */
  public boolean sendVerifyEmail(String to, long userId, String link) {
    String text = """
        Hello,

        use this link to confirm this address for your Garrulus account. It expires in 24 hours:

        %s

        Open it in the browser you are signed in with. If you did not ask for this, you can ignore this email.

        Garrulus, the name-literature index of the Catalogue of Life
        """.formatted(link);
    String href = HtmlUtils.htmlEscape(link);
    String html = """
        <!doctype html>
        <html><body style="font-family: sans-serif; line-height: 1.5">
        <p>Hello,</p>
        <p>use this link to confirm this address for your Garrulus account. It expires in 24 hours:</p>
        <p><a href="%s">Confirm my address</a></p>
        <p>Open it in the browser you are signed in with. If you did not ask for this, you can ignore this email.</p>
        <p>Garrulus, the name-literature index of the Catalogue of Life</p>
        </body></html>
        """.formatted(href);
    return send(to, userId, "Confirm your address for Garrulus", text, html);
  }
}
