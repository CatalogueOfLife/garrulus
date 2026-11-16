package org.catalogueoflife.nametracker.web.auth;

import java.io.Serializable;
import java.security.Principal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * The principal of every signed-in session, whatever the sign-in path (email link, ORCID). Its name is the account id,
 * which Spring Session stores as {@code spring_session.principal_name}, so the sessions of an account can be found
 * and deleted by id. Serializable because the JDBC session stores the security context with Java serialization.
 */
public record GarrulusPrincipal(long userId) implements Principal, Serializable {

  @Override
  public String getName() {
    return String.valueOf(userId);
  }

  /** An authenticated token for this account, with the single authority ROLE_USER. */
  public static Authentication authentication(long userId) {
    return UsernamePasswordAuthenticationToken.authenticated(
        new GarrulusPrincipal(userId), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
  }
}
