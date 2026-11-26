package org.catalogueoflife.garrulus.finder.service;

import jakarta.servlet.http.HttpServletResponse;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.gnfinder.GnfinderUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every error is a JSON object {@code {"status": 503, "error": "..."}}, never a stack trace. gnfinder being unavailable
 * ({@link GnfinderUnavailableException}, also as a cause) is 503.
 */
@RestControllerAdvice
public class ApiErrors {
  private static final System.Logger LOG = System.getLogger(ApiErrors.class.getName());
  static final String GNFINDER_UNAVAILABLE = "gnfinder unavailable; retry later";

  static Map<String, Object> body(int status, String message) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", status);
    m.put("error", message);
    return m;
  }

  /** For filters, which run before the advice. */
  static void write(HttpServletResponse resp, int status, String message) throws IOException {
    resp.setStatus(status);
    resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.getWriter().write(FinderJson.mapper().writeValueAsString(body(status, message)));
  }

  private static ResponseEntity<Map<String, Object>> reply(HttpStatus s, String message) {
    return ResponseEntity.status(s).contentType(MediaType.APPLICATION_JSON).body(body(s.value(), message));
  }

  @ExceptionHandler(BadRequest.class)
  ResponseEntity<Map<String, Object>> badRequest(BadRequest e) {
    return reply(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
    if (find(e, FinderWebConfig.BodyTooLargeException.class) != null) return reply(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge());
    return reply(HttpStatus.BAD_REQUEST, "Cannot read the request body: " + root(e));
  }

  @ExceptionHandler(TooLarge.class)
  ResponseEntity<Map<String, Object>> tooLarge(TooLarge e) {
    return reply(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
  }

  @ExceptionHandler(FinderWebConfig.BodyTooLargeException.class)
  ResponseEntity<Map<String, Object>> tooLarge(FinderWebConfig.BodyTooLargeException e) {
    return reply(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
  }

  @ExceptionHandler(GnfinderUnavailableException.class)
  ResponseEntity<Map<String, Object>> gnfinderUnavailable(GnfinderUnavailableException e) {
    return unavailable(e);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> other(Exception e) throws Exception {
    if (find(e, FinderWebConfig.BodyTooLargeException.class) != null) return reply(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge());
    GnfinderUnavailableException gn = find(e, GnfinderUnavailableException.class);
    if (gn != null) return unavailable(gn); // wrapped by someone on the way
    throw e; // Spring's own handling of the rest: 404, 405, 415 stay what they are, anything else is a logged 500
  }

  /** The detail (with gnfinder's internal URL) goes to the log only; the client learns that it may retry. */
  private static ResponseEntity<Map<String, Object>> unavailable(GnfinderUnavailableException e) {
    LOG.log(System.Logger.Level.WARNING, "gnfinder is not available: {0}", e.getMessage());
    return reply(HttpStatus.SERVICE_UNAVAILABLE, GNFINDER_UNAVAILABLE);
  }

  private static String tooLarge() {
    return "Request body is larger than " + (FinderWebConfig.MAX_BODY / 1024 / 1024) + " MB";
  }

  private static <T extends Throwable> T find(Throwable e, Class<T> type) {
    for (Throwable t = e; t != null; t = t.getCause()) if (type.isInstance(t)) return type.cast(t);
    return null;
  }

  private static String root(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null) t = t.getCause();
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }

  /** A request the caller must fix. */
  static final class BadRequest extends RuntimeException {
    BadRequest(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A text or block longer than the finder takes in one piece: 413. */
  static final class TooLarge extends RuntimeException {
    TooLarge(String message) {
      super(message);
    }
  }
}
