package org.catalogueoflife.garrulus.web.digest;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.catalogueoflife.garrulus.store.account.AccountMapper;
import org.catalogueoflife.garrulus.store.account.AppUser;
import org.catalogueoflife.garrulus.store.account.Digest;
import org.catalogueoflife.garrulus.store.account.DigestMapper;
import org.catalogueoflife.garrulus.store.account.EmailTokenMapper;
import org.catalogueoflife.garrulus.store.account.Hit;
import org.catalogueoflife.garrulus.store.account.HitMapper;
import org.catalogueoflife.garrulus.store.account.Subscription;
import org.catalogueoflife.garrulus.store.account.SubscriptionMapper;
import org.catalogueoflife.garrulus.web.api.public_.WorkController;
import org.catalogueoflife.garrulus.web.api.public_.dto.WorkSummary;
import org.catalogueoflife.garrulus.web.digest.DigestComposer.Mail;
import org.catalogueoflife.garrulus.web.digest.DigestComposer.Paper;
import org.catalogueoflife.garrulus.web.digest.DigestComposer.SubscriptionHits;
import org.catalogueoflife.garrulus.web.mail.MailService;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The email digests. Every hour ({@code garrulus.digest.cron}, one instance via ShedLock) each due user gets the
 * undelivered hits of their unpaused subscriptions that were created after {@code digest_since} and are at most 14
 * days (DAILY) or 21 days (WEEKLY) old; older ones stay on "My hits" but are never mailed. The hits are marked with a
 * new SENT digest row in one transaction, then the mail goes out; if it fails, the marks are cleared and the row is
 * kept as FAILED, so the next hour tries again. No hits, no mail and no row. The same run purges expired email and
 * sign-in tokens. Logs name the user id, never the address.
 *
 * <p>Delivery is at most once by design: the hits are marked (committed) before the mail is sent, so a crash between
 * the commit and the send loses that digest rather than sending it twice.
 */
@Component
public class DigestJob {

  private static final Logger LOG = LoggerFactory.getLogger(DigestJob.class);

  /** {@code hits} is the number of hits in the digest (also when sending failed). */
  public record Result(boolean sent, int hits) {}

  private static final Result NOTHING = new Result(false, 0);

  /** Work ids per summary query: those queries bind one parameter per id. */
  static final int SUMMARY_CHUNK = 1000;

  /** Test seam: runs between collecting the hits and marking them (a concurrent run's window). */
  volatile Consumer<List<Long>> beforeMark = ids -> {};

  private final AccountMapper accounts;
  private final SubscriptionMapper subscriptions;
  private final HitMapper hits;
  private final DigestMapper digests;
  private final EmailTokenMapper emailTokens;
  private final WorkController works;
  private final MailService mail;
  private final UnsubscribeTokens tokens;
  private final DigestComposer composer;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String publicUrl;

  public DigestJob(AccountMapper accounts, SubscriptionMapper subscriptions, HitMapper hits, DigestMapper digests,
      EmailTokenMapper emailTokens, WorkController works, MailService mail, UnsubscribeTokens tokens,
      PlatformTransactionManager transactions, Clock clock, @Value("${garrulus.public-url}") String publicUrl) {
    this.accounts = accounts;
    this.subscriptions = subscriptions;
    this.hits = hits;
    this.digests = digests;
    this.emailTokens = emailTokens;
    this.works = works;
    this.mail = mail;
    this.tokens = tokens;
    this.composer = new DigestComposer(tokens);
    this.tx = new TransactionTemplate(transactions);
    this.clock = clock;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
  }

  @Scheduled(cron = "${garrulus.digest.cron:0 5 * * * *}", zone = "UTC")
  @SchedulerLock(name = "digests", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
  public void run() {
    sendDue();
    purgeTokens();
  }

  /** Sends the digest of every due user; returns the number sent. Without a mail host it sends nothing. */
  public int sendDue() {
    if (!mail.configured()) {
      LOG.debug("Digests skipped: no mail host (spring.mail.host)");
      return 0;
    }
    int sent = 0;
    for (AppUser u : accounts.digestCandidates()) {
      try {
        if (runFor(u.id(), false) instanceof Result r && r.sent()) {
          sent++;
        }
      } catch (RuntimeException e) {
        LOG.error("Digest of user {} failed", u.id(), e);
      }
    }
    return sent;
  }

  /** Deletes expired email-change tokens and expired sign-in tokens. */
  public void purgeTokens() {
    Instant now = clock.instant();
    int email = emailTokens.purgeExpired(now);
    int signIn = emailTokens.purgeExpiredOneTimeTokens(now);
    if (email + signIn > 0) {
      LOG.info("Purged {} expired email tokens and {} expired sign-in tokens", email, signIn);
    }
  }

  /**
   * Sends the user's digest if it is due ({@code force} skips only that check); null for an unknown user. An account
   * without a verified address never gets one.
   */
  public @Nullable Result runFor(long userId, boolean force) {
    AppUser u = accounts.findById(userId);
    if (u == null) {
      return null;
    }
    Instant now = clock.instant();
    if (!force && !DigestSchedule.isDue(u, digests.lastSent(userId), now)) {
      return NOTHING;
    }
    if (u.email() == null || u.emailVerified() == null) {
      return NOTHING;
    }
    Instant since = u.digestSince() != null ? u.digestSince() : u.emailVerified();
    Instant previous = digests.lastSent(userId);
    List<Hit> rows = hits.undeliveredForDigest(userId, since, now.minus(DigestSchedule.maxAge(u)));
    Map<Long, WorkSummary> listed = summaries(rows.stream().map(Hit::workId).distinct().toList());
    // a work that is no longer listed cannot be shown: its hit stays undelivered
    List<Hit> mailed = rows.stream().filter(h -> listed.containsKey(h.workId())).toList();
    if (mailed.isEmpty()) {
      return NOTHING;
    }
    // a note counts once: those written after the previous sent digest (else after digest_since)
    List<DigestComposer.XrNote> notes =
        DigestComposer.newNotes(subscriptions.listByUser(userId), previous != null ? previous : since);
    Mail m = composer.compose(u, groups(userId, mailed, listed), notes, publicUrl);
    List<Long> ids = mailed.stream().map(Hit::id).toList();

    beforeMark.accept(ids);
    Long digestId = tx.execute(status -> {
      long id = digests.insert(new Digest(null, userId, null, ids.size(), "SENT", null));
      if (hits.markDelivered(ids, id) != ids.size()) {
        // another run took some of these hits meanwhile: leave them to it
        status.setRollbackOnly();
        return null;
      }
      return id;
    });
    if (digestId == null) {
      LOG.warn("Digest of user {} skipped: its hits were taken by a concurrent run", userId);
      return NOTHING;
    }

    String unsubscribe = publicUrl + "/api/unsubscribe?t=" + tokens.create(userId);
    MailService.Outcome outcome;
    try {
      outcome = mail.deliver(u.email(), userId, m.subject(), m.text(), m.html(), Map.of(
          "List-Unsubscribe", "<" + unsubscribe + ">",
          "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
    } catch (RuntimeException e) {
      LOG.warn("Digest mail of user {} failed: {}", userId, e.getClass().getSimpleName());
      outcome = MailService.Outcome.FAILED;
    }
    if (outcome != MailService.Outcome.SENT) {
      String error = outcome == MailService.Outcome.SUPPRESSED ? "mail suppressed: no mail host" : "send failed";
      tx.executeWithoutResult(status -> {
        hits.unmark(digestId);
        digests.setStatus(digestId, "FAILED", error);
      });
      LOG.info("Digest {} of user {}: {} hits, FAILED ({})", digestId, userId, ids.size(), error);
      return new Result(false, ids.size());
    }
    LOG.info("Digest {} of user {}: {} hits, SENT", digestId, userId, ids.size());
    return new Result(true, ids.size());
  }

  /** The listed works among the ids, in chunks: a broad follow may hold more hits than one query can bind. */
  private Map<Long, WorkSummary> summaries(List<Long> workIds) {
    Map<Long, WorkSummary> out = new java.util.HashMap<>();
    for (int i = 0; i < workIds.size(); i += SUMMARY_CHUNK) {
      out.putAll(works.summaries(workIds.subList(i, Math.min(workIds.size(), i + SUMMARY_CHUNK))));
    }
    return out;
  }

  private List<SubscriptionHits> groups(long userId, List<Hit> mailed, Map<Long, WorkSummary> listed) {
    Map<Long, String> labels = subscriptions.listByUser(userId).stream()
        .collect(Collectors.toMap(Subscription::id, s -> s.label() == null ? "" : s.label()));
    Map<Long, List<Paper>> bySubscription = new LinkedHashMap<>();
    for (Hit h : mailed) {
      bySubscription.computeIfAbsent(h.subscriptionId(), k -> new ArrayList<>())
          .add(new Paper(h, listed.get(h.workId())));
    }
    return bySubscription.entrySet().stream()
        .map(e -> new SubscriptionHits(e.getKey(), labels.get(e.getKey()), e.getValue()))
        .toList();
  }
}
