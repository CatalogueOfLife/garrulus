package org.catalogueoflife.nametracker.web.auth;

import java.util.Locale;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Accounts by email address. */
@Service
public class AccountService {

  static final int MAX_EMAIL_LENGTH = 254;

  private final AccountMapper accounts;
  private final TransactionTemplate tx;

  public AccountService(AccountMapper accounts, PlatformTransactionManager txManager) {
    this.accounts = accounts;
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * Trimmed and lower-cased; a 400 unless it has exactly one {@code @} with text on both sides, no whitespace or
   * control characters, and at most 254 characters.
   */
  public static String normaliseEmail(@Nullable String raw) {
    if (raw == null) {
      throw invalid();
    }
    String email = raw.strip().toLowerCase(Locale.ROOT);
    int at = email.indexOf('@');
    if (email.length() > MAX_EMAIL_LENGTH || at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1
        || email.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      throw invalid();
    }
    return email;
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email address");
  }

  /** The id of the account with this (normalised) address, if any. */
  public @Nullable Long findIdByEmail(String normalisedEmail) {
    AppUser u = accounts.findByEmail(normalisedEmail);
    return u == null ? null : u.id();
  }

  /**
   * The id of the account with this address. A new account gets the address as verified, which also starts its
   * digests ({@code digest_since}). Two concurrent first sign-ins with one address meet at the unique key; the loser
   * reads the winner's account.
   */
  public long findOrCreateByEmail(String email) {
    String normalised = normaliseEmail(email);
    Long existing = findIdByEmail(normalised);
    if (existing != null) {
      return existing;
    }
    try {
      Long id = tx.execute(status -> {
        long created = accounts.insert(
            new AppUser(null, null, null, null, null, null, null, null, null, null, null, null));
        accounts.setEmail(created, normalised);
        return created;
      });
      return java.util.Objects.requireNonNull(id);
    } catch (DuplicateKeyException race) {
      Long winner = findIdByEmail(normalised);
      if (winner == null) {
        throw race;
      }
      return winner;
    }
  }

  public void touchLogin(long userId) {
    accounts.touchLogin(userId);
  }
}
