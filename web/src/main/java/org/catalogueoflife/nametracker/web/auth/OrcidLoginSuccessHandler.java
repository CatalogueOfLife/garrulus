package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/**
 * Ends the ORCID login ({@code /login/oauth2/code/orcid}). The ORCID iD is the ID token's {@code sub}: the account
 * with that iD is found, or created without an address (ORCID never gives one) and with the display name from the ID
 * token. The login filter's ORCID-named authentication is then replaced by {@link GarrulusPrincipal}, so only account
 * ids name sessions, and the browser goes back to the SPA: its start page, or the account page asking for an address
 * ({@code /account?addEmail=1}) while the account has none. A failed login goes to {@code /signin?error=orcid}.
 */
@Component
public class OrcidLoginSuccessHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  private static final Logger LOG = LoggerFactory.getLogger(OrcidLoginSuccessHandler.class);

  private final AccountMapper accounts;
  private final CurrentUser currentUser;
  private final String publicUrl;

  public OrcidLoginSuccessHandler(AccountMapper accounts, CurrentUser currentUser,
      @Value("${nametracker.public-url}") String publicUrl) {
    this.accounts = accounts;
    this.currentUser = currentUser;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
  }

  /** The SPA's sign-in page. */
  String signInPage() {
    return publicUrl + "/signin";
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException {
    AppUser user;
    try {
      OidcUser oidc = (OidcUser) authentication.getPrincipal();
      user = findOrCreate(oidc.getSubject(), displayName(oidc));
      accounts.touchLogin(user.id());
      currentUser.signIn(user.id(), request, response);
    } catch (RuntimeException e) {
      // never leave the login filter's ORCID-named authentication in the session
      SecurityContextHolder.clearContext();
      HttpSession session = request.getSession(false);
      if (session != null) {
        session.invalidate();
      }
      LOG.warn("ORCID sign-in failed in the account step: {}", e.getClass().getSimpleName());
      response.sendRedirect(signInPage() + "?error=orcid");
      return;
    }
    // the login dropped the old CSRF token; loading the new one sends the SPA its XSRF-TOKEN cookie with the redirect
    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrf != null) {
      csrf.getToken();
    }
    LOG.info("user {} signed in with ORCID", user.id());
    response.sendRedirect(publicUrl + (user.email() == null ? "/account?addEmail=1" : "/"));
  }

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException {
    LOG.info("ORCID sign-in refused: {}", exception.getClass().getSimpleName());
    response.sendRedirect(signInPage() + "?error=orcid");
  }

  /** Two first logins with one iD at once meet at the unique key; the loser reads the winner's account. */
  private AppUser findOrCreate(String orcid, @Nullable String displayName) {
    AppUser user = accounts.findByOrcid(orcid);
    if (user != null) {
      return user;
    }
    try {
      long id = accounts.insert(
          new AppUser(null, null, null, orcid, displayName, null, null, null, null, null, null, null));
      LOG.info("user {} created by ORCID sign-in", id);
      return java.util.Objects.requireNonNull(accounts.findById(id));
    } catch (DuplicateKeyException race) {
      AppUser winner = accounts.findByOrcid(orcid);
      if (winner == null) {
        throw race;
      }
      return winner;
    }
  }

  /** The {@code name} claim, else given and family name, else null; at most 200 characters. */
  static @Nullable String displayName(OidcUser oidc) {
    String name = blankToNull(oidc.getFullName());
    if (name == null) {
      String given = blankToNull(oidc.getGivenName());
      String family = blankToNull(oidc.getFamilyName());
      name = given == null ? family : family == null ? given : given + " " + family;
    }
    if (name == null) {
      return null;
    }
    int max = MeController.MAX_DISPLAY_NAME;
    return name.codePointCount(0, name.length()) <= max ? name : name.substring(0, name.offsetByCodePoints(0, max));
  }

  private static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.strip();
  }
}
