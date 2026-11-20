package org.catalogueoflife.nametracker.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.store.account.AccountMapper;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The signed-in account, its settings and its address. */
@RestController
public class MeController {

  static final Set<String> FREQUENCIES = Set.of("DAILY", "WEEKLY", "OFF");
  static final int MAX_DISPLAY_NAME = 200;

  /**
   * {@code created} lets the SPA propose the browser's time zone once, to a new account still on UTC. {@code admin} is
   * true only for a session with ROLE_ADMIN (an ORCID sign-in of a listed iD) whose iD is still listed.
   */
  public record Me(long id, @Nullable String email, @Nullable Instant emailVerified, @Nullable String orcid,
      @Nullable String displayName, String timeZone, String digestFrequency, int digestWeekday, int digestHour,
      Instant created, boolean admin) {

    static Me of(AppUser u, boolean admin) {
      return new Me(u.id(), u.email(), u.emailVerified(), u.orcid(), u.displayName(), u.timeZone(),
          u.digestFrequency(), u.digestWeekday(), u.digestHour(), u.created(), admin);
    }
  }

  /** Every field is optional; an absent (null) field keeps its value, a blank display name clears it. */
  public record MeUpdate(@Nullable String displayName, @Nullable String timeZone, @Nullable String digestFrequency,
      @Nullable Integer digestWeekday, @Nullable Integer digestHour) {}

  public record EmailRequest(@Nullable String email) {}

  public record VerifyRequest(@Nullable String token) {}

  private final AccountMapper accounts;
  private final CurrentUser currentUser;
  private final EmailVerificationService verification;
  private final Clock clock;
  private final AdminOrcids adminOrcids;

  public MeController(AccountMapper accounts, CurrentUser currentUser, EmailVerificationService verification,
      Clock clock, AdminOrcids adminOrcids) {
    this.accounts = accounts;
    this.currentUser = currentUser;
    this.verification = verification;
    this.clock = clock;
    this.adminOrcids = adminOrcids;
  }

  @GetMapping("/api/me")
  public Me me() {
    return view(load());
  }

  /**
   * Sets display name, time zone (IANA id), digest frequency (DAILY, WEEKLY, OFF), weekday (1 = Monday to 7) and
   * local hour (0 to 23). Turning digests on from OFF restarts them now: older hits are never mailed.
   */
  @PatchMapping("/api/me")
  public Me update(@RequestBody MeUpdate u) {
    AppUser user = load();
    String displayName = user.displayName();
    if (u.displayName() != null) {
      String name = u.displayName().strip();
      if (name.codePointCount(0, name.length()) > MAX_DISPLAY_NAME) {
        throw bad("displayName is longer than " + MAX_DISPLAY_NAME + " characters");
      }
      displayName = name.isEmpty() ? null : name;
    }
    String timeZone = user.timeZone();
    if (u.timeZone() != null) {
      if (!ZoneId.getAvailableZoneIds().contains(u.timeZone())) {
        throw bad("timeZone is not an IANA time zone");
      }
      timeZone = u.timeZone();
    }
    String frequency = user.digestFrequency();
    if (u.digestFrequency() != null) {
      if (!FREQUENCIES.contains(u.digestFrequency())) {
        throw bad("digestFrequency is DAILY, WEEKLY or OFF");
      }
      frequency = u.digestFrequency();
    }
    int weekday = user.digestWeekday();
    if (u.digestWeekday() != null) {
      if (u.digestWeekday() < 1 || u.digestWeekday() > 7) {
        throw bad("digestWeekday is 1 (Monday) to 7");
      }
      weekday = u.digestWeekday();
    }
    int hour = user.digestHour();
    if (u.digestHour() != null) {
      if (u.digestHour() < 0 || u.digestHour() > 23) {
        throw bad("digestHour is 0 to 23");
      }
      hour = u.digestHour();
    }
    Instant digestSince = "OFF".equals(user.digestFrequency()) && !"OFF".equals(frequency) ? clock.instant() : null;
    accounts.updateSettings(user.id(), displayName, timeZone, frequency, weekday, hour, digestSince);
    return view(load());
  }

  /**
   * Starts a change of address: mails a verification link to the new address. Always {@code 202 {}}, whether or not
   * the address is in use; nothing is stored on the account until the link is used. An invalid address is a 400.
   */
  @PostMapping("/api/me/email")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> requestEmail(@RequestBody EmailRequest body, HttpServletRequest request) {
    verification.request(load().id(), body.email(), request.getRemoteAddr());
    return Map.of();
  }

  /**
   * Uses a verification link: {@code {"outcome": "SET" | "MERGED" | "REFUSED_OTHER_ORCID"}}. Needs the session of the
   * account that asked for it (else 401, and the token stays valid); an unknown, used or expired token is a 400.
   */
  @PostMapping("/api/auth/verify-email")
  public Map<String, String> verifyEmail(@RequestBody VerifyRequest body, HttpServletRequest request,
      HttpServletResponse response) {
    return Map.of("outcome", verification.verify(load().id(), body.token(), request, response).name());
  }

  /** The account as the SPA sees it; admin by the same rule as the admin endpoints (AdminAccess). */
  private Me view(AppUser user) {
    return Me.of(user, currentUser.hasAdminRole() && adminOrcids.contains(user.orcid()));
  }

  /** The signed-in account; a 401 if it no longer exists. */
  private AppUser load() {
    AppUser user = accounts.findById(currentUser.require());
    if (user == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return user;
  }

  private static ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
