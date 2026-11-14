package org.catalogueoflife.nametracker.web.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Temporary shared-secret guard for /api/admin until ORCID admin roles arrive (M4). Absent without a token. */
@Component
@ConditionalOnExpression("!'${nametracker.admin.token:}'.isBlank()")
public class AdminTokenFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Admin-Token";
  private final byte[] token;

  public AdminTokenFilter(@Value("${nametracker.admin.token}") String token) {
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/admin/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String given = req.getHeader(HEADER);
    if (given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(req, res);
    } else {
      res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
  }
}
