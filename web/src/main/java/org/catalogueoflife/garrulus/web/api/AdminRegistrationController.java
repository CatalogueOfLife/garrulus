package org.catalogueoflife.garrulus.web.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.garrulus.store.registry.AdminRegistrationRow;
import org.catalogueoflife.garrulus.store.registry.RegistrationMapper;
import org.catalogueoflife.garrulus.web.auth.AdminOrcids;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The journals users registered (admin token or admin session, {@link AdminAccess}), newest first. */
@RestController
@Conditional(AdminAccessConfigured.class)
public class AdminRegistrationController {

  private static final Set<String> STATES = Set.of("IDENTIFYING", "IDENTIFIED", "NOT_IDENTIFIED", "WITHDRAWN");

  /**
   * A registration. {@code outcome} is the one of its identification (a fold keeps it); {@code sourceTier} and
   * {@code sourcePublicationStatus} are those of the source it points at now.
   */
  public record AdminRegistrationView(long id, String kind, String input, @Nullable String note, String state,
      @Nullable String outcome, @Nullable String reason, @Nullable Long sourceId, @Nullable String sourceTitle,
      @Nullable String sourceTier, @Nullable String sourcePublicationStatus, @Nullable String userDisplayName,
      Instant created) {
    static AdminRegistrationView of(AdminRegistrationRow r) {
      return new AdminRegistrationView(r.id(), r.kind(), r.input(), r.note(), r.state(), r.outcome(), r.reason(),
          r.sourceId(), r.sourceTitle(), r.sourceTier(), r.sourcePublicationStatus(), r.userDisplayName(), r.created());
    }
  }

  private final RegistrationMapper registrations;
  private final AdminAccess access;

  public AdminRegistrationController(RegistrationMapper registrations, AdminAccess access) {
    this.registrations = registrations;
    this.access = access;
  }

  @GetMapping("/api/admin/registrations")
  public List<AdminRegistrationView> list(
      @RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestParam(required = false) @Nullable String state,
      @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "0") int offset) {
    access.check(given);
    if (state != null && !STATES.contains(state)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown state " + state);
    }
    if (limit < 1 || limit > AdminSourceController.MAX_LIMIT || offset < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "limit must be 1.." + AdminSourceController.MAX_LIMIT + ", offset >= 0");
    }
    return registrations.adminList(state, limit, offset).stream().map(AdminRegistrationView::of).toList();
  }
}
