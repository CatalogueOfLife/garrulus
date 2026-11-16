package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Loads the deferred CSRF token on the SPA's account and sign-in paths, so that the cookie repository writes the
 * XSRF-TOKEN cookie (also on a plain GET), which the SPA then echoes in the X-XSRF-TOKEN header. Adapted from Blixa,
 * but scoped: public reads (and the portal's CORS calls) get no cookie, so caches in front of web can keep them.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

  /** The SPA reads GET /api/auth/config or GET /api/me before it posts anything. */
  static final RequestMatcher SPA_PATHS = new OrRequestMatcher(
      PathPatternRequestMatcher.withDefaults().matcher("/api/me/**"),
      PathPatternRequestMatcher.withDefaults().matcher("/api/auth/**"),
      PathPatternRequestMatcher.withDefaults().matcher("/login/**"));

  private final RequestMatcher paths;

  CsrfCookieFilter(RequestMatcher paths) {
    this.paths = paths;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (paths.matches(request)) {
      CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (token != null) {
        token.getToken();
      }
    }
    chain.doFilter(request, response);
  }
}
