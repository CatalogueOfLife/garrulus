package org.catalogueoflife.nametracker.web.api.public_;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Small JSON error bodies {status, error, message} for the public API only. */
@RestControllerAdvice(basePackageClasses = ApiErrors.class)
public class ApiErrors {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", e.getStatusCode().value());
    body.put("error", e.getReason() == null ? "error" : e.getReason());
    return ResponseEntity.status(e.getStatusCode()).body(body);
  }
}
