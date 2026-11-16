package org.catalogueoflife.nametracker.web.auth;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Keeps every sent message in memory instead of talking to an SMTP host. Implements the interface rather than
 * extending JavaMailSenderImpl, so that no mail health indicator probes a real host.
 */
class CapturingMailSender implements JavaMailSender {

  private static final Pattern TOKEN = Pattern.compile("token=([0-9A-Za-z_-]+)");

  private final List<MimeMessage> sent = new ArrayList<>();

  private final Session session = Session.getInstance(new Properties());

  @Override
  public MimeMessage createMimeMessage() {
    return new MimeMessage(session);
  }

  @Override
  public MimeMessage createMimeMessage(InputStream contentStream) {
    try {
      return new MimeMessage(session, contentStream);
    } catch (MessagingException e) {
      throw new MailParseException(e);
    }
  }

  @Override
  public synchronized void send(MimeMessage... mimeMessages) {
    for (MimeMessage m : mimeMessages) {
      try {
        // as a transport does: sets the Content-Type headers from the content
        m.saveChanges();
      } catch (MessagingException e) {
        throw new MailParseException(e);
      }
      sent.add(m);
    }
  }

  @Override
  public void send(SimpleMailMessage... simpleMessages) {
    throw new UnsupportedOperationException("web sends MIME messages only");
  }

  synchronized List<MimeMessage> sent() {
    return List.copyOf(sent);
  }

  synchronized void clear() {
    sent.clear();
  }

  /** The messages sent to this address. */
  List<MimeMessage> to(String address) {
    return sent().stream().filter(m -> recipients(m).contains(address)).toList();
  }

  static List<String> recipients(MimeMessage m) {
    try {
      Address[] all = m.getAllRecipients();
      return all == null ? List.of() : Arrays.stream(all).map(Address::toString).toList();
    } catch (MessagingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The text/plain part of a message (or "" if it has none). */
  static String plainText(Part part) {
    try {
      if (part.isMimeType("text/plain")) {
        return (String) part.getContent();
      }
      if (part.getContent() instanceof Multipart mp) {
        for (int i = 0; i < mp.getCount(); i++) {
          String text = plainText(mp.getBodyPart(i));
          if (!text.isEmpty()) {
            return text;
          }
        }
      }
      return "";
    } catch (MessagingException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The text/html part of a message (or "" if it has none). */
  static String html(Part part) {
    try {
      if (part.isMimeType("text/html")) {
        return (String) part.getContent();
      }
      if (part.getContent() instanceof Multipart mp) {
        for (int i = 0; i < mp.getCount(); i++) {
          String html = html(mp.getBodyPart(i));
          if (!html.isEmpty()) {
            return html;
          }
        }
      }
      return "";
    } catch (MessagingException | IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The token in the link (sign-in or verify-email) of the newest message to this address. */
  String lastTokenFor(String address) {
    List<MimeMessage> mails = to(address);
    if (mails.isEmpty()) {
      throw new AssertionError("no mail to " + address);
    }
    Matcher m = TOKEN.matcher(plainText(mails.getLast()));
    if (!m.find()) {
      throw new AssertionError("no token in the mail to " + address);
    }
    return m.group(1);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    CapturingMailSender capturingMailSender() {
      return new CapturingMailSender();
    }
  }
}
