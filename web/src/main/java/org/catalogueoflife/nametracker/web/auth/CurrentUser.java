package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in account of the current request, and the one place that signs an account into the session. */
@Component
public class CurrentUser {

  private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
  private final SecurityContextRepository contexts;

  public CurrentUser(SecurityContextRepository contexts) {
    this.contexts = contexts;
  }

  /** The account id, or a 401 unless the request is authenticated with a {@link GarrulusPrincipal}. */
  public long require() {
    Authentication a = holder.getContext().getAuthentication();
    if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof GarrulusPrincipal p) {
      return p.userId();
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  }

  /**
   * True if the request is authenticated with a {@link GarrulusPrincipal} that holds ROLE_ADMIN. That alone does not
   * make an admin: the account's iD must still be in {@link AdminOrcids}.
   */
  public boolean hasAdminRole() {
    Authentication a = holder.getContext().getAuthentication();
    return a != null && a.isAuthenticated() && a.getPrincipal() instanceof GarrulusPrincipal
        && a.getAuthorities().stream().anyMatch(g -> GarrulusPrincipal.ROLE_ADMIN.equals(g.getAuthority()));
  }

  /**
   * Replaces the authentication of this request with {@link GarrulusPrincipal}(userId) and saves it to the session.
   * Every sign-in path except the email link (whose provider returns the authentication itself) ends here.
   * {@code admin} adds ROLE_ADMIN; only the ORCID sign-in of a listed iD passes true.
   */
  public void signIn(long userId, boolean admin, HttpServletRequest request, HttpServletResponse response) {
    SecurityContext context = holder.createEmptyContext();
    context.setAuthentication(GarrulusPrincipal.authentication(userId, admin));
    holder.setContext(context);
    contexts.saveContext(context, request, response);
  }
}
