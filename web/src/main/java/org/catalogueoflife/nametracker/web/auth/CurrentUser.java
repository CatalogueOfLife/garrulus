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
   * Replaces the authentication of this request with {@link GarrulusPrincipal}(userId) and saves it to the session.
   * Every sign-in path ends here.
   */
  public void signIn(long userId, HttpServletRequest request, HttpServletResponse response) {
    SecurityContext context = holder.createEmptyContext();
    context.setAuthentication(GarrulusPrincipal.authentication(userId));
    holder.setContext(context);
    contexts.saveContext(context, request, response);
  }
}
