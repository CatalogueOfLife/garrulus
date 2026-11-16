package org.catalogueoflife.nametracker.web.me;

import java.util.List;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.account.Digest;
import org.catalogueoflife.nametracker.store.account.DigestMapper;
import org.catalogueoflife.nametracker.store.account.HitMapper;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.web.auth.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Everything Garrulus holds about the signed-in account, as a download; and the deletion of the account. */
@RestController
public class AccountDataController {

  public record ExportedSubscription(long id, String type, String target, String label, boolean mainSubjectOnly,
      boolean newNamesOnly, boolean includeAbstractOnly, boolean includeReferences, JsonNode filters,
      String feedToken, Integer sinceYear, boolean paused, java.time.Instant created) {}

  public record ExportedHit(long id, long subscriptionId, long workId, String kind, java.time.Instant created,
      JsonNode names, Long digestId) {}

  public record Export(AppUser user, List<ExportedSubscription> subscriptions, List<ExportedHit> hits,
      List<Digest> digests) {}

  private final AccountMapper accounts;
  private final SubscriptionMapper subscriptions;
  private final HitMapper hits;
  private final DigestMapper digests;
  private final CurrentUser currentUser;
  private final TransactionTemplate tx;

  public AccountDataController(AccountMapper accounts, SubscriptionMapper subscriptions, HitMapper hits,
      DigestMapper digests, CurrentUser currentUser, PlatformTransactionManager txManager) {
    this.accounts = accounts;
    this.subscriptions = subscriptions;
    this.hits = hits;
    this.digests = digests;
    this.currentUser = currentUser;
    this.tx = new TransactionTemplate(txManager);
  }

  @GetMapping("/api/me/export")
  public ResponseEntity<Export> export() {
    long userId = currentUser.require();
    AppUser user = accounts.findById(userId);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    List<ExportedSubscription> subs = subscriptions.listByUser(userId).stream().map(AccountDataController::exported).toList();
    List<ExportedHit> hs = hits.listByUser(userId, null, Integer.MAX_VALUE, 0).stream()
        .map(h -> new ExportedHit(h.id(), h.subscriptionId(), h.workId(), h.kind(), h.created(),
            HitController.names(h.names()), h.digestId()))
        .toList();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"garrulus-export.json\"")
        .body(new Export(user, subs, hs, digests.listByUser(userId)));
  }

  private static ExportedSubscription exported(Subscription s) {
    return new ExportedSubscription(s.id(), s.type(), s.target(), s.label(), s.mainSubjectOnly(), s.newNamesOnly(),
        s.includeAbstractOnly(), s.includeReferences(), HitController.names(s.filters() == null ? "{}" : s.filters()),
        s.feedToken(), s.sinceYear(), s.paused(), s.created());
  }

  /** Hard delete: the account, its subscriptions, hits and digests (cascade) and all its sessions. */
  @DeleteMapping("/api/me")
  public ResponseEntity<Void> delete() {
    long userId = currentUser.require();
    tx.executeWithoutResult(status -> {
      accounts.deleteSessions(userId, null);
      accounts.delete(userId);
    });
    return ResponseEntity.noContent().build();
  }
}
