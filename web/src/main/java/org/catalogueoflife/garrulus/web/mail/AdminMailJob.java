package org.catalogueoflife.garrulus.web.mail;

import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.garrulus.store.account.AccountMapper;
import org.catalogueoflife.garrulus.store.account.AppUser;
import org.catalogueoflife.garrulus.store.notice.AdminNotice;
import org.catalogueoflife.garrulus.store.notice.AdminNoticeMapper;
import org.catalogueoflife.garrulus.web.auth.AdminOrcids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mails the {@code admin_notice} rows the indexer wrote ({@code garrulus.admin.mail-cron}, one instance via
 * ShedLock): up to 20 per run, each as one mail to every verified address of an account whose ORCID iD is in
 * {@link AdminOrcids}. The outcome is SENT, FAILED (the mail host refused every address) or SUPPRESSED (no mail host,
 * or no admin address; logged with the notice id, never an address). A notice is marked as mailed when it is claimed
 * and a failed send is not retried (the weekly reminder of a failing layer repeats it); a claim that got no outcome
 * within the lock time (the run died between claim and send) is claimed again, so a crash does not lose the mail (it
 * may then be sent twice if the run died after sending).
 */
@Component
public class AdminMailJob {

  private static final Logger LOG = LoggerFactory.getLogger(AdminMailJob.class);
  static final int BATCH = 20;
  /** lockAtMostFor of the job: after that, a run that claimed notices is gone. */
  static final java.time.Duration CLAIM_TIMEOUT = java.time.Duration.ofMinutes(10);

  private final AdminNoticeMapper notices;
  private final AccountMapper accounts;
  private final AdminOrcids orcids;
  private final MailService mail;
  private final java.time.Clock clock;

  public AdminMailJob(AdminNoticeMapper notices, AccountMapper accounts, AdminOrcids orcids, MailService mail,
      java.time.Clock clock) {
    this.clock = clock;
    this.notices = notices;
    this.accounts = accounts;
    this.orcids = orcids;
    this.mail = mail;
  }

  @Scheduled(cron = "${garrulus.admin.mail-cron:0 */5 * * * *}", zone = "UTC")
  @SchedulerLock(name = "admin-mail", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
  public void run() {
    // a notice claimed longer ago than the lock lasts, without an outcome, was lost by a run that died: claim it again
    List<AdminNotice> claimed = notices.claimUnmailed(BATCH, clock.instant().minus(CLAIM_TIMEOUT));
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
