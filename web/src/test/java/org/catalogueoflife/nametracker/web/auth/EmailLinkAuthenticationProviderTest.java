package org.catalogueoflife.nametracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.core.Authentication;

class EmailLinkAuthenticationProviderTest {

  final OneTimeTokenService tokens = mock(OneTimeTokenService.class);
  final AccountService accounts = mock(AccountService.class);
  final EmailLinkAuthenticationProvider provider = new EmailLinkAuthenticationProvider(tokens, accounts);
  final OneTimeTokenAuthenticationToken request = OneTimeTokenAuthenticationToken.unauthenticated("t1");

  @Test
  void validTokenAuthenticatesTheAccountId() {
    when(tokens.consume(any())).thenReturn(new DefaultOneTimeToken("t1", "ann@example.org", Instant.now()));
    when(accounts.findOrCreateByEmail("ann@example.org")).thenReturn(17L);

    Authentication a = provider.authenticate(request);

    assertThat(a.isAuthenticated()).isTrue();
    assertThat(a.getPrincipal()).isEqualTo(new GarrulusPrincipal(17));
    assertThat(a.getName()).isEqualTo("17");
    verify(accounts).touchLogin(17L);
  }

  @Test
  void unknownTokenIsRefusedWithoutTouchingAccounts() {
    when(tokens.consume(any())).thenReturn(null);
    assertThatThrownBy(() -> provider.authenticate(request)).isInstanceOf(InvalidOneTimeTokenException.class);
    verifyNoInteractions(accounts);
  }

  @Test
  void tokenForNoAddressIsRefused() {
    when(tokens.consume(any())).thenReturn(new DefaultOneTimeToken("t1", "not-an-address", Instant.now()));
    assertThatThrownBy(() -> provider.authenticate(request)).isInstanceOf(InvalidOneTimeTokenException.class);
    verifyNoInteractions(accounts);
  }

  /** The filter saves only what the provider returns: a failing account step returns nothing to save. */
  @Test
  void accountFailurePropagates() {
    when(tokens.consume(any())).thenReturn(new DefaultOneTimeToken("t1", "ann@example.org", Instant.now()));
    when(accounts.findOrCreateByEmail("ann@example.org")).thenThrow(new DataAccessResourceFailureException("down"));

    assertThatThrownBy(() -> provider.authenticate(request)).isInstanceOf(DataAccessResourceFailureException.class);
    verify(accounts, org.mockito.Mockito.never()).touchLogin(anyLong());
  }
}
