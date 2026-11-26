package org.catalogueoflife.garrulus.finder.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ReadListener;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * The web side of the contract. The JSON mapper bean is {@link FinderJson#mapper()} itself, so Spring MVC (Jackson 3) writes
 * and reads exactly what the contract tests and {@code RemoteNameFinder} use: NON_NULL, camelCase, enums by name. Boot's
 * own mapper backs off because it is {@code @ConditionalOnMissingBean}.
 */
@Configuration
public class FinderWebConfig {
  /** The most bytes of a request body. */
  public static final long MAX_BODY = 20L * 1024 * 1024;

  @Bean
  JsonMapper jsonMapper() {
    return FinderJson.mapper();
  }

  @Bean
  ApiErrors apiErrors() {
    return new ApiErrors();
  }

  @Bean
  BodyLimitFilter bodyLimitFilter() {
    return new BodyLimitFilter();
  }

  /** Thrown by the body stream when more than {@link #MAX_BODY} bytes are read. */
  static final class BodyTooLargeException extends IOException {
    BodyTooLargeException() {
      super("Request body is larger than " + (MAX_BODY / 1024 / 1024) + " MB");
    }
  }

  /** Answers 413 for a declared Content-Length over the limit, and cuts off chunked bodies that exceed it. */
  static final class BodyLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
      if (req.getContentLengthLong() > MAX_BODY) {
        ApiErrors.write(resp, 413, new BodyTooLargeException().getMessage());
        return;
      }
      chain.doFilter(new HttpServletRequestWrapper(req) {
        @Override
        public ServletInputStream getInputStream() throws IOException {
          return new Limited(super.getInputStream());
        }
      }, resp);
    }
  }

  private static final class Limited extends ServletInputStream {
    private final ServletInputStream in;
    private long read;

    Limited(ServletInputStream in) {
      this.in = in;
    }

    private void count(long n) throws IOException {
      if (n > 0 && (read += n) > MAX_BODY) throw new BodyTooLargeException();
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) count(1);
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      count(n);
      return n;
    }

    @Override
    public boolean isFinished() {
      return in.isFinished();
    }

    @Override
    public boolean isReady() {
      return in.isReady();
    }

    @Override
    public void setReadListener(ReadListener l) {
      in.setReadListener(l);
    }
  }
}
