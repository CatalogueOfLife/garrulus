package org.catalogueoflife.garrulus.web.auth;

import java.io.Serializable;
import java.security.Principal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * The principal of every signed-in session, whatever the sign-in path (email link, ORCID). Its name is the account id,
 * which Spring Session stores as {@code spring_session.principal_name}, so the sessions of an account can be found
 * and deleted by id. Serializable because the JDBC session stores the security context with Java serialization. The
 * admin role is an authority of the authentication, not part of the principal.
 */
public record GarrulusPrincipal(long userId) implements Principal, Serializable {

  public static final String ROLE_USER = "ROLE_USER";
  /** Only from an ORCID sign-in of an iD in {@link AdminOrcids}; the admin endpoints check the list again. */
  public static final String ROLE_ADMIN = "ROLE_ADMIN";

  @Override
  public String getName() {
    return String.valueOf(userId);
  }

  /** An authenticated token for this account: ROLE_USER, plus ROLE_ADMIN if {@code admin}. */
  public static Authentication authentication(long userId, boolean admin) {
    return UsernamePasswordAuthenticationToken.authenticated(new GarrulusPrincipal(userId), null, admin
        ? AuthorityUtils.createAuthorityList(ROLE_USER, ROLE_ADMIN)
        : AuthorityUtils.createAuthorityList(ROLE_USER));
  }
}
