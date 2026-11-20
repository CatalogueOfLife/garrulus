package org.catalogueoflife.nametracker.web.mail;

import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.notice.AdminNotice;
import org.catalogueoflife.nametracker.store.notice.AdminNoticeMapper;
import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mails the {@code admin_notice} rows the indexer wrote ({@code nametracker.admin.mail-cron}, one instance via
 * ShedLock): up to 20 per run, each as one mail to every verified address of an account whose ORCID iD is in
 * {@link AdminOrcids}. The outcome is SENT, FAILED (the mail host refused every address) or SUPPRESSED (no mail host,
 * or no admin address; logged with the notice id, never an address). At most once: a notice is marked as mailed when it
 * is claimed and a failed send is not retried (the weekly reminder of a failing layer repeats it).
 */
@Component
public class AdminMailJob {

  private static final Logger LOG = LoggerFactory.getLogger(AdminMailJob.class);
  static final int BATCH = 20;

  private final AdminNoticeMapper notices;
  private final AccountMapper accounts;
  private final AdminOrcids orcids;
  private final MailService mail;

  public AdminMailJob(AdminNoticeMapper notices, AccountMapper accounts, AdminOrcids orcids, MailService mail) {
    this.notices = notices;
    this.accounts = accounts;
    this.orcids = orcids;
    this.mail = mail;
  }

  @Scheduled(cron = "${nametracker.admin.mail-cron:0 */5 * * * *}", zone = "UTC")
  @SchedulerLock(name = "admin-mail", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
  public void run() {
    List<AdminNotice> claimed = notices.claimUnmailed(BATCH);
    if (claimed.isEmpty()) {
      return;
    }
    List<AppUser> admins = accounts.withOrcidAndVerifiedEmail().stream().filter(u -> orcids.contains(u.orcid()))
        .toList();
    for (AdminNotice n : claimed) {
      notices.setOutcome(n.id(), deliver(n, admins).name());
    }
  }

  private MailService.Outcome deliver(AdminNotice n, List<AppUser> admins) {
    if (admins.isEmpty() || !mail.configured()) {
      LOG.info("admin mail suppressed: notice {} ({}), {}", n.id(), n.kind(),
          admins.isEmpty() ? "no admin address" : "no spring.mail.host");
      return MailService.Outcome.SUPPRESSED;
    }
    boolean sent = false;
    for (AppUser admin : admins) {
      sent |= mail.sendAdminNotice(admin.email(), admin.id(), n.subject(), n.body()) == MailService.Outcome.SENT;
    }
    return sent ? MailService.Outcome.SENT : MailService.Outcome.FAILED;
  }
}
