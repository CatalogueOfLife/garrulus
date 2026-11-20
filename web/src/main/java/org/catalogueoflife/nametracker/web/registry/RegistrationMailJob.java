package org.catalogueoflife.nametracker.web.registry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.notice.AdminNotice;
import org.catalogueoflife.nametracker.store.notice.AdminNoticeMapper;
import org.catalogueoflife.nametracker.store.registry.RegistrationMapper;
import org.catalogueoflife.nametracker.web.mail.MailService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The mails about registrations, one instance via ShedLock:
 * <ul>
 *   <li>{@link #decisions()} ({@code nametracker.registrations.mail-cron}): a registration identified while its journal
 *   was a CANDIDATE whose source is now CORE or BROAD (ACCEPTED) or EXCLUDED (DECLINED) gets its decision recorded and
 *   one mail to the user's verified address; without one the mail is logged as suppressed. At most once: the decision
 *   is recorded when it is claimed, a failed send is not retried, a later tier change sends nothing. Withdrawn
 *   registrations and those of journals covered or ceased at registration never mail.</li>
 *   <li>{@link #adminNote()} ({@code nametracker.registrations.admin-note-cron}, daily): an admin notice
 *   {@code REGISTRATIONS_WAITING} (key {@code registrations:<date>}, mailed by the admin mail job) when registrations
 *   were identified as candidates since the previous such notice and their journals still wait for a decision: the
 *   count and up to {@value #NOTE_LINES} lines with the journal, the input and a link to its admin page.</li>
 * </ul>
 */
@Component
public class RegistrationMailJob {

  private static final Logger LOG = LoggerFactory.getLogger(RegistrationMailJob.class);
  static final int BATCH = 50;
  static final int NOTE_LINES = 20;
  static final String NOTE_KIND = "REGISTRATIONS_WAITING";
  static final String NOTE_KEY = "registrations:";

  private final RegistrationMapper registrations;
  private final AccountMapper accounts;
  private final AdminNoticeMapper notices;
  private final MailService mail;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String publicUrl;

  public RegistrationMailJob(RegistrationMapper registrations, AccountMapper accounts, AdminNoticeMapper notices,
      MailService mail, TransactionTemplate tx, Clock clock, @Value("${nametracker.public-url}") String publicUrl) {
    this.registrations = registrations;
    this.accounts = accounts;
    this.notices = notices;
    this.mail = mail;
    this.tx = tx;
    this.clock = clock;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
  }

  @Scheduled(cron = "${nametracker.registrations.mail-cron:0 */5 * * * *}", zone = "UTC")
  @SchedulerLock(name = "registration-mail", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
  public void decisions() {
    List<RegistrationMapper.Decision> claimed;
    while (!(claimed = registrations.claimDecisions(BATCH)).isEmpty()) {
      for (RegistrationMapper.Decision d : claimed) {
        AppUser user = accounts.findById(d.userId());
        boolean accepted = d.decision().equals("ACCEPTED");
        if (user == null || user.email() == null || user.emailVerified() == null) {
          LOG.info("registration mail suppressed: registration {} ({}), user {} has no verified address", d.id(),
              d.decision(), d.userId());
          continue;
        }
        mail.sendRegistrationDecision(user.email(), user.id(), d.title(), d.input(), accepted,
            publicUrl + "/source/" + d.sourceId());
      }
    }
  }

  @Scheduled(cron = "${nametracker.registrations.admin-note-cron:0 0 7 * * *}", zone = "UTC")
  @SchedulerLock(name = "registration-admin-note", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
  public void adminNote() {
    tx.executeWithoutResult(status -> {
      AdminNotice previous = notices.latest(NOTE_KIND, NOTE_KEY);
      @Nullable Instant since = previous == null ? null : previous.created();
      int count = registrations.countWaitingSince(since);
      if (count == 0) return;
      List<RegistrationMapper.Waiting> lines = registrations.waitingSince(since, NOTE_LINES);
      String subject = count == 1 ? "Garrulus: 1 registered journal waits for a decision"
          : "Garrulus: " + count + " registered journals wait for a decision";
      StringBuilder body = new StringBuilder();
      body.append(count == 1 ? "1 journal" : count + " journals")
          .append(" registered by users since the last note ").append(count == 1 ? "is a candidate" : "are candidates")
          .append(" waiting for a decision (promote or exclude on its page):\n");
      for (RegistrationMapper.Waiting w : lines) {
        body.append("\n- ").append(w.title() == null || w.title().isBlank() ? "(no title)" : oneLine(w.title()))
            .append(" — registered as ").append(oneLine(w.input())).append('\n')
            .append("  ").append(publicUrl).append("/admin/journals/").append(w.sourceId()).append('\n');
      }
      if (count > lines.size()) body.append("\nand ").append(count - lines.size()).append(" more.\n");
      notices.insert(NOTE_KIND, NOTE_KEY + LocalDate.now(clock.withZone(ZoneOffset.UTC)), subject, body.toString());
    });
  }

  /** Line breaks of an external title or a typed address cannot add lines to the note. */
  private static String oneLine(String s) {
    return s.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
  }
}
