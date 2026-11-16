package org.catalogueoflife.nametracker.web.auth;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticates {@code POST /login/ott}: consumes the one-time token (single use), finds or creates the account of
 * the address it was mailed to (address verified), records the login and returns the {@link GarrulusPrincipal}
 * authentication itself. So the authentication filter saves only the account id to the session; no principal named
 * by an address ever reaches it. If the account step fails, the exception propagates and nothing is saved.
 */
final class EmailLinkAuthenticationProvider implements AuthenticationProvider {

  private final OneTimeTokenService tokens;
  private final AccountService accounts;

  EmailLinkAuthenticationProvider(OneTimeTokenService tokens, AccountService accounts) {
    this.tokens = tokens;
    this.accounts = accounts;
  }

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    OneTimeTokenAuthenticationToken request = (OneTimeTokenAuthenticationToken) authentication;
    OneTimeToken token = tokens.consume(request);
    if (token == null) {
      throw new InvalidOneTimeTokenException("Invalid token");
    }
    String email;
    try {
      // the token's username is the normalised address it was mailed to
      email = AccountService.normaliseEmail(token.getUsername());
    } catch (ResponseStatusException e) {
      throw new InvalidOneTimeTokenException("Token is not for an email address");
    }
    long userId = accounts.findOrCreateByEmail(email);
    accounts.touchLogin(userId);
    Authentication result = GarrulusPrincipal.authentication(userId);
    ((AbstractAuthenticationToken) result).setDetails(request.getDetails());
    return result;
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return OneTimeTokenAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
