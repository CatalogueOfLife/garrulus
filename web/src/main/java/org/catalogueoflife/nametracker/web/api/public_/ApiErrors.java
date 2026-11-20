package org.catalogueoflife.nametracker.web.api.public_;

import java.util.LinkedHashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.web.auth.MeController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Small JSON error bodies {status, error}, error being the reason, for the public API and the account API. */
@RestControllerAdvice(basePackageClasses = {ApiErrors.class, MeController.class, org.catalogueoflife.nametracker.web.me.SubscriptionController.class,
    org.catalogueoflife.nametracker.web.digest.UnsubscribeController.class,
    org.catalogueoflife.nametracker.web.registry.RegistrationController.class})
public class ApiErrors {

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", e.getStatusCode().value());
    body.put("error", e.getReason() == null ? "error" : e.getReason());
    return ResponseEntity.status(e.getStatusCode()).body(body);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", 400);
    body.put("error", "invalid value for " + e.getName());
    return ResponseEntity.badRequest().body(body);
  }
}
