package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Cache-Control: no-store} on the account, sign-in and admin paths. Spring Security's default cache headers are
 * switched off in {@link SecurityConfig}, because they made every public read uncacheable; the responses that belong
 * to one user (or to the admin token) get this header instead. The feeds set their own.
 */
final class NoStoreFilter extends OncePerRequestFilter {

  static final RequestMatcher PATHS = new OrRequestMatcher(
      CsrfCookieFilter.SPA_PATHS,
      PathPatternRequestMatcher.withDefaults().matcher("/api/admin/**"));

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (PATHS.matches(request)) {
      response.setHeader("Cache-Control", "no-store");
    }
    chain.doFilter(request, response);
  }
}
