package org.catalogueoflife.garrulus.web.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.catalogueoflife.garrulus.store.account.AccountMapper;
import org.catalogueoflife.garrulus.store.account.AppUser;
import org.catalogueoflife.garrulus.web.auth.AdminOrcids;
import org.catalogueoflife.garrulus.web.auth.CurrentUser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * The access check of every admin controller (ADR-0006, amendment M6b), called first in each handler, so whatever
 * path variant reached the handler is checked:
 * <ul>
 *   <li>{@code X-Admin-Token} sent (scripts): compared in constant time with {@code garrulus.admin.token}; 401 on a
 *     mismatch or when no token is configured. A sent header never falls back to the session. These requests are
 *     exempt from XSRF ({@code SecurityConfig}), under exactly this rule: the header is present.</li>
 *   <li>no header (browser): the session must hold ROLE_ADMIN (only an ORCID sign-in of a listed iD gets it) and the
 *     account's ORCID iD must still be in {@link AdminOrcids}, looked up on every request; else 403, or 401 when not
 *     signed in. Session writes need XSRF like every other session write.</li>
 * </ul>
 */
@Component
public class AdminAccess {

  private static final Logger LOG = LoggerFactory.getLogger(AdminAccess.class);

  private final byte @Nullable [] token;
  private final AdminOrcids orcids;
  private final AccountMapper accounts;
  private final CurrentUser currentUser;

  public AdminAccess(@Value("${garrulus.admin.token:}") String token, AdminOrcids orcids, AccountMapper accounts,
      CurrentUser currentUser) {
    this.token = token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    this.orcids = orcids;
    this.accounts = accounts;
    this.currentUser = currentUser;
  }

  /**
   * Returns if the request may use the admin endpoints, else throws 401 or 403.
   *
   * @param tokenHeader the {@code X-Admin-Token} header as the controller received it
   */
  public void check(@Nullable String tokenHeader) {
    // the CSRF exemption looks at the raw request: decide the path by the same test, whatever the binding made of it
    String given = tokenHeader != null ? tokenHeader : rawHeader();
    if (given != null) {
      if (token == null || !MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
      }
      return;
    }
    long userId = currentUser.require();
    if (!currentUser.hasAdminRole()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    AppUser user = accounts.findById(userId);
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    if (!orcids.contains(user.orcid())) {
      LOG.info("admin session of user {} refused: its ORCID iD is not listed (any more)", userId);
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
  }

  private static @Nullable String rawHeader() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      return a.getRequest().getHeader(AdminOrcids.TOKEN_HEADER);
    }
    return null;
  }
}
