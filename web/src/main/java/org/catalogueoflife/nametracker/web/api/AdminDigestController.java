package org.catalogueoflife.nametracker.web.api;

import org.catalogueoflife.nametracker.web.auth.AdminOrcids;
import org.catalogueoflife.nametracker.web.digest.DigestJob;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/admin/digests/run {"userId":n}} sends the user's digest now, due or not (for testing); 404 for an
 * unknown user. Admin token or admin session ({@link AdminAccess}) like the other admin endpoints; not mapped without
 * either way configured ({@link AdminAccessConfigured}).
 */
@RestController
@Conditional(AdminAccessConfigured.class)
public class AdminDigestController {

  public record RunRequest(@Nullable Long userId) {}

  private final DigestJob job;
  private final AdminAccess access;

  public AdminDigestController(DigestJob job, AdminAccess access) {
    this.job = job;
    this.access = access;
  }

  @PostMapping("/api/admin/digests/run")
  public DigestJob.Result run(@RequestHeader(name = AdminOrcids.TOKEN_HEADER, required = false) @Nullable String given,
      @RequestBody RunRequest req) {
    access.check(given);
    if (req.userId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
    }
    DigestJob.Result r = job.runFor(req.userId(), true);
    if (r == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no user " + req.userId());
    }
    return r;
  }
}
