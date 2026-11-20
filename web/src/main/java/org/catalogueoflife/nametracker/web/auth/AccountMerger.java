package org.catalogueoflife.nametracker.web.auth;

import java.util.Objects;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.account.DigestMapper;
import org.catalogueoflife.nametracker.store.account.EmailToken;
import org.catalogueoflife.nametracker.store.account.EmailTokenMapper;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.registry.RegistrationMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies a used verify-email token in one transaction (spec, "Address changes and merges"):
 * <ul>
 *   <li>the address is unused (or already the requester's): it becomes the requester's address ({@code SET}); the
 *       first verified address also starts the digests ({@code digest_since});</li>
 *   <li>it belongs to another account without an ORCID iD: the requester's account merges into that one, which
 *       keeps its id ({@code MERGED});</li>
 *   <li>it belongs to an account with another ORCID iD: nothing changes ({@code REFUSED_OTHER_ORCID}).</li>
 * </ul>
 * In a merge the survivor (the account holding the address) takes over the requester's follows (of two with the same
 * type and target the older stays, with its options; hits move where they don't clash; the cap of 100 does not
 * apply), digests, journal registrations, ORCID iD (when it has none) and display name (when it has none). The requester's account and its
 * sessions, except the current one, are deleted; the caller then signs the current session in as the survivor.
 */
@Service
public class AccountMerger {

  public enum Outcome { SET, MERGED, REFUSED_OTHER_ORCID }

  /** The outcome and the account the current session belongs to afterwards. */
  public record Result(Outcome outcome, long userId) {}

  private static final Logger LOG = LoggerFactory.getLogger(AccountMerger.class);

  private final AccountMapper accounts;
  private final SubscriptionMapper subscriptions;
  private final DigestMapper digests;
  private final EmailTokenMapper tokens;
  private final RegistrationMapper registrations;
  private final TransactionTemplate tx;

  public AccountMerger(AccountMapper accounts, SubscriptionMapper subscriptions, DigestMapper digests,
      EmailTokenMapper tokens, RegistrationMapper registrations, PlatformTransactionManager txManager) {
    this.registrations = registrations;
    this.accounts = accounts;
    this.subscriptions = subscriptions;
    this.digests = digests;
    this.tokens = tokens;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Consumes the token of {@code requesterId} and applies its address. A 400 when the token is unknown, expired or
   * used meanwhile (nothing changes); a 401 when it is another account's (left in place) or the requester is gone; a
   * 409 "conflict" when another account took the address between the lookup and the update (rolled back, the token
   * stays usable). The database's message names the address, so only the user id and the exception class are logged.
   *
   * @param currentSessionId the requester's current session, which survives the merge (null: none)
   */
  public Result apply(long requesterId, String tokenHash, @Nullable String currentSessionId) {
    try {
      return applyInTx(requesterId, tokenHash, currentSessionId);
    } catch (DuplicateKeyException e) {
      LOG.warn("address change of user {} conflicted with another account ({})", requesterId,
          e.getClass().getSimpleName());
      throw new ResponseStatusException(HttpStatus.CONFLICT, "conflict");
    }
  }

  private Result applyInTx(long requesterId, String tokenHash, @Nullable String currentSessionId) {
    return Objects.requireNonNull(tx.execute(status -> {
      EmailToken found = tokens.findValid(tokenHash);
      if (found == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or expired link");
      }
      if (found.userId() != requesterId) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "the link belongs to another account");
      }
      EmailToken token = tokens.consume(tokenHash);
      if (token == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid or expired link");
      }
      AppUser requester = accounts.findById(requesterId);
      if (requester == null) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      }
      AppUser holder = accounts.findByEmail(token.email());
      if (holder == null || holder.id() == requesterId) {
        accounts.setEmail(requesterId, token.email());
        LOG.info("user {} verified a new address", requesterId);
        return new Result(Outcome.SET, requesterId);
      }
      if (holder.orcid() != null && !holder.orcid().equals(requester.orcid())) {
        LOG.info("user {} verified an address of user {}, which has another ORCID iD: refused", requesterId,
            holder.id());
        return new Result(Outcome.REFUSED_OTHER_ORCID, requesterId);
      }
      merge(requester, holder, currentSessionId);
      return new Result(Outcome.MERGED, holder.id());
    }));
  }

  private void merge(AppUser loser, AppUser survivor, @Nullable String currentSessionId) {
    long from = loser.id();
    long to = survivor.id();
    subscriptions.merge(from, to);
    digests.reassign(from, to);
    registrations.reassign(from, to);
    accounts.deleteSessions(from, currentSessionId);
    accounts.delete(from);
    // after the delete: the ORCID iD is unique
    if (survivor.orcid() == null && loser.orcid() != null) {
      accounts.setOrcid(to, loser.orcid());
    }
    if (survivor.displayName() == null && loser.displayName() != null) {
      accounts.updateSettings(to, loser.displayName(), survivor.timeZone(), survivor.digestFrequency(),
          survivor.digestWeekday(), survivor.digestHour(), null);
    }
    accounts.touchLogin(to);
    LOG.info("user {} merged into user {}", from, to);
  }
}
