package org.catalogueoflife.garrulus.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import org.catalogueoflife.garrulus.store.account.AccountMapper;
import org.catalogueoflife.garrulus.store.account.AppUser;
import org.catalogueoflife.garrulus.store.account.DigestMapper;
import org.catalogueoflife.garrulus.store.account.EmailToken;
import org.catalogueoflife.garrulus.store.account.EmailTokenMapper;
import org.catalogueoflife.garrulus.store.account.SubscriptionMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class AccountMergerTest {

  static final String ADDRESS = "taken@example.org";

  @Test
  void addressTakenMeanwhileIsAClean409WithoutTheAddressInTheLog() {
    AccountMapper accounts = mock(AccountMapper.class);
    EmailTokenMapper tokens = mock(EmailTokenMapper.class);
    EmailToken token = new EmailToken(1, 7, ADDRESS, "hash", Instant.now().plusSeconds(60));
    when(tokens.findValid("hash")).thenReturn(token);
    when(tokens.consume("hash")).thenReturn(token);
    when(accounts.findById(7L)).thenReturn(new AppUser(7L, null, null, null, null, "UTC", "DAILY", 1, 7, null,
        Instant.now(), null));
    // the lookup sees no holder, but another account takes the address before the update
    when(accounts.findByEmail(ADDRESS)).thenReturn(null);
    doThrow(new DuplicateKeyException("ERROR: duplicate key value violates unique constraint \"app_user_email_idx\" "
        + "Detail: Key (lower(email))=(" + ADDRESS + ") already exists."))
        .when(accounts).setEmail(anyLong(), anyString());
    AccountMerger merger = new AccountMerger(accounts, mock(SubscriptionMapper.class), mock(DigestMapper.class), tokens,
        mock(org.catalogueoflife.garrulus.store.registry.RegistrationMapper.class),
        mock(PlatformTransactionManager.class));

    Logger log = (Logger) LoggerFactory.getLogger(AccountMerger.class);
    ListAppender<ILoggingEvent> logged = new ListAppender<>();
    logged.start();
    log.addAppender(logged);
    try {
      ResponseStatusException e = catchThrowableOfType(ResponseStatusException.class,
          () -> merger.apply(7, "hash", null));
      assertThat(e.getStatusCode().value()).isEqualTo(409);
      assertThat(e.getReason()).isEqualTo("conflict");
      assertThat(e.getCause()).isNull();
    } finally {
      log.detachAppender(logged);
    }
    assertThat(logged.list).isNotEmpty();
    assertThat(logged.list).allSatisfy(ev -> {
      assertThat(ev.getFormattedMessage()).doesNotContain(ADDRESS).contains("7");
      assertThat(ev.getThrowableProxy()).isNull();
    });
  }
}
