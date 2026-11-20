package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tests only: {@code GET /login/test-as?userId=n} signs the browser into any account through {@link CurrentUser}, so
 * an IT can hold a real session of an ORCID account without talking to ORCID; {@code &admin=true} adds ROLE_ADMIN as
 * an ORCID sign-in of a listed iD would (whether the account's iD is listed is not checked here). Never part of the
 * application.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestSignIn.Controller.class)
public class TestSignIn {

  @TestComponent
  @RestController
  static class Controller {

    private final CurrentUser currentUser;

    Controller(CurrentUser currentUser) {
      this.currentUser = currentUser;
    }

    @GetMapping("/login/test-as")
    ResponseEntity<Void> signInAs(@RequestParam long userId, @RequestParam(defaultValue = "false") boolean admin,
        HttpServletRequest request, HttpServletResponse response) {
      currentUser.signIn(userId, admin, request, response);
      return ResponseEntity.noContent().build();
    }
  }
}
