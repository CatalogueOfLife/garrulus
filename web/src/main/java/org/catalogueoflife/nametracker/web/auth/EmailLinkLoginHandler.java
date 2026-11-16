package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/**
 * Ends the one-time-token login ({@code POST /login/ott}) for the SPA: never a redirect. By the time it runs,
 * {@link EmailLinkAuthenticationProvider} has found or created the account and the filter has saved its
 * {@link GarrulusPrincipal} to the session; the answer is {@code 200 {"userId":n}}. A bad, used or expired token is a
 * 401.
 */
@Component
class EmailLinkLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

  private static final Logger LOG = LoggerFactory.getLogger(EmailLinkLoginHandler.class);

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException {
    long userId = ((GarrulusPrincipal) authentication.getPrincipal()).userId();
    // the session authentication strategy (CsrfAuthenticationStrategy) dropped the old CSRF token at login and left
    // a new, not yet saved one; loading it now sends the SPA its new XSRF-TOKEN cookie with this answer
    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (csrf != null) {
      csrf.getToken();
    }
    LOG.info("user {} signed in with an email link", userId);
    write(response, HttpServletResponse.SC_OK, "{\"userId\":" + userId + "}");
  }

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException {
    LOG.info("email link sign-in refused: {}", exception.getClass().getSimpleName());
    write(response, HttpServletResponse.SC_UNAUTHORIZED,
        "{\"status\":401,\"error\":\"invalid or expired sign-in link\"}");
  }

  static void write(HttpServletResponse response, int status, String json) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json);
  }
}
