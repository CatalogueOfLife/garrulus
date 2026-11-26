package org.catalogueoflife.garrulus.web.registry;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.Issn;
import org.catalogueoflife.garrulus.store.registry.RegistrationMapper;
import org.catalogueoflife.garrulus.store.registry.SourceRegistration;
import org.catalogueoflife.garrulus.store.registry.SourceSignalMapper;
import org.catalogueoflife.garrulus.store.route.SourceRoute;
import org.catalogueoflife.garrulus.store.route.SourceRouteMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.catalogueoflife.garrulus.web.auth.CurrentUser;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * Journal registration by a signed-in user: {@code POST /api/sources/register} with exactly one of {@code issn},
 * {@code doi} (of an article) or {@code url}, plus an optional {@code note}. The web app makes no outbound call: it
 * stores the registration (IDENTIFYING) and enqueues {@code IDENTIFY_SOURCE {"registrationId":n}} at LIVE; the indexer
 * identifies the journal. At most {@value #MAX_PER_DAY} registrations per user in 24 hours, and at most
 * {@code garrulus.registration.daily-cap} (200) of all users together (accounts are free, and each registration is
 * a LIVE task with a page fetch ahead of the harvest; approximate under parallel requests); the same input again while
 * the first is identifying or identified answers that registration. The body is read up to {@value #MAX_BODY} bytes, a
 * DOI may have {@value #MAX_DOI} characters. The user's registrations are
 * read and withdrawn under {@code /api/me/registrations}; withdrawing removes the registration's signal from its source
 * (the source stays).
 */
@RestController
public class RegistrationController {

  static final int MAX_PER_DAY = 20;
  static final int MAX_LIST = 100;
  static final int MAX_NOTE = 500;
  static final int MAX_URL = 2000;
  static final int MAX_DOI = 300;
  /** The request body is read up to this many bytes; a larger one is a 413. */
  static final int MAX_BODY = 8 * 1024;
  static final String IDENTIFY_TYPE = "IDENTIFY_SOURCE";
  static final String SIGNAL_ORIGIN = "REGISTERED";
  /** The insert's answer when the global daily cap is reached (no registration id is negative). */
  private static final Long CAPPED = -1L;
  private static final Set<String> KEYS = Set.of("issn", "doi", "url", "note");
  private static final List<String> INPUTS = List.of("issn", "doi", "url");

  private final RegistrationMapper registrations;
  private final SourceMapper sources;
  private final SourceRouteMapper routes;
  private final SourceSignalMapper signals;
  private final TaskQueue queue;
  private final CurrentUser currentUser;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final int dailyCap;

  public RegistrationController(RegistrationMapper registrations, SourceMapper sources, SourceRouteMapper routes,
      SourceSignalMapper signals, TaskQueue queue, CurrentUser currentUser, TransactionTemplate tx, Clock clock,
      @Value("${garrulus.registration.daily-cap:200}") int dailyCap) {
    this.dailyCap = dailyCap;
    this.registrations = registrations;
    this.sources = sources;
    this.routes = routes;
    this.signals = signals;
    this.queue = queue;
    this.currentUser = currentUser;
    this.tx = tx;
    this.clock = clock;
  }

  @PostMapping("/api/sources/register")
  public ResponseEntity<Map<String, Object>> register(HttpServletRequest request) throws IOException {
    long userId = currentUser.require();
    Map<String, Object> body = body(request);
    for (String k : body.keySet()) {
      if (!KEYS.contains(k)) throw bad("unknown field " + k);
    }
    List<String> given = INPUTS.stream().filter(k -> body.get(k) != null).toList();
    if (given.size() != 1) throw bad("exactly one of issn, doi or url is required");
    String key = given.getFirst();
    String raw = string(body, key);
    String kind = key.toUpperCase(Locale.ROOT);
    String input = switch (key) {
      case "issn" -> {
        String issn = Issn.normalise(raw);
        if (issn == null) throw bad("not a valid ISSN (eight characters with a correct check digit)");
        yield issn;
      }
      case "doi" -> {
        String doi = raw.strip().length() > MAX_DOI ? null : IdScheme.DOI.normalise(raw);
        if (doi == null) throw bad("not a DOI of at most " + MAX_DOI + " characters");
        yield doi;
      }
      default -> url(raw);
    };
    String note = string(body, "note");
    note = note == null || note.isBlank() ? null : note.strip();
    if (note != null && note.codePointCount(0, note.length()) > MAX_NOTE) {
      throw bad("note is at most " + MAX_NOTE + " characters");
    }
    String n = note;
    Long id = tx.execute(status -> {
      // one user's registrations are counted and inserted one at a time, so the cap holds under parallel requests
      registrations.lockUser(userId);
      // the same input again while the first is identifying or identified: that registration, not a second signal
      Long same = registrations.findActive(userId, kind, input);
      if (same != null) return same;
      Instant dayAgo = clock.instant().minus(Duration.ofHours(24));
      if (registrations.countSince(userId, dayAgo) >= MAX_PER_DAY) return null;
      if (registrations.countAllSince(dayAgo) >= dailyCap) return CAPPED;
      long rid = registrations.insert(userId, kind, input, n);
      queue.enqueue(IDENTIFY_TYPE, "{\"registrationId\":" + rid + "}", TaskPriority.LIVE, "identify:r" + rid);
      return rid;
    });
    if (id == null) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "at most " + MAX_PER_DAY + " registrations in 24 hours");
    }
    if (CAPPED.equals(id)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "registrations are paused for today; please try again tomorrow");
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("id", id));
  }

  @GetMapping("/api/me/registrations")
  public List<RegistrationDto> list() {
    Map<Long, RegistrationDto.@Nullable JournalDto> journals = new HashMap<>();
    return registrations.listByUser(currentUser.require(), MAX_LIST).stream().map(r -> dto(r, journals)).toList();
  }

  @GetMapping("/api/me/registrations/{id}")
  public RegistrationDto get(@PathVariable long id) {
    return dto(owned(id), new HashMap<>());
  }

  @DeleteMapping("/api/me/registrations/{id}")
  public ResponseEntity<Void> withdraw(@PathVariable long id) {
    long userId = currentUser.require();
    tx.executeWithoutResult(status -> {
      if (registrations.getForUser(id, userId) == null) throw notFound();
      // the source as the withdrawing statement saw it: an identification committing meanwhile is not missed
      Long sourceId = registrations.withdraw(id, userId);
      if (sourceId != null) signals.delete(sourceId, SIGNAL_ORIGIN, String.valueOf(id));
    });
    return ResponseEntity.noContent().build();
  }

  /** The JSON object of the request, at most {@value #MAX_BODY} bytes (413 above, 400 if it is no object). */
  private static Map<String, Object> body(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_BODY) throw tooLarge();
    byte[] raw = request.getInputStream().readNBytes(MAX_BODY + 1);
    if (raw.length > MAX_BODY) throw tooLarge();
    try {
      Map<String, Object> m = FinderJson.mapper().readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
      if (m == null) throw bad("the body is a JSON object");
      return m;
    } catch (JacksonException e) {
      throw bad("the body is a JSON object");
    }
  }

  private static ResponseStatusException tooLarge() {
    return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "the request is larger than " + MAX_BODY
        + " bytes");
  }

  private SourceRegistration owned(long id) {
    SourceRegistration r = registrations.getForUser(id, currentUser.require());
    if (r == null) throw notFound();
    return r;
  }

  private RegistrationDto dto(SourceRegistration r, Map<Long, RegistrationDto.@Nullable JournalDto> journals) {
    RegistrationDto.JournalDto j = null;
    if (r.state().equals("IDENTIFIED") && r.sourceId() != null) {
      j = journals.computeIfAbsent(r.sourceId(), this::journal);
    }
    return new RegistrationDto(r.id(), r.kind(), r.input(), r.note(), r.state(), r.outcome(), r.reason(),
        status(r, j), j, r.created(), r.identifiedAt());
  }

  private RegistrationDto.@Nullable JournalDto journal(long sourceId) {
    Source s = sources.get(sourceId);
    if (s == null) return null;
    List<String> layers = routes.forSource(sourceId).stream().map(SourceRoute::layer).distinct().sorted().toList();
    return new RegistrationDto.JournalDto(s.id(), s.title(), s.issns() == null ? List.of() : s.issns(), s.publisher(),
        s.country(), s.tier(), s.publicationStatus(), layers);
  }

  /**
   * The status from the state and the source's current tier and publication status (a folded registration points at
   * the survivor, whose tier may differ from the one recorded at identification). An identified registration whose
   * source is gone falls back to the tier recorded at identification.
   */
  static String status(SourceRegistration r, RegistrationDto.@Nullable JournalDto j) {
    if (!r.state().equals("IDENTIFIED")) return r.state();
    if (j != null && j.publicationStatus().equals("CEASED")) return "CEASED";
    if (j == null && "CEASED".equals(r.outcome())) return "CEASED";
    String tier = j != null ? j.tier() : r.tierAtIdentification();
    return switch (tier == null ? "CANDIDATE" : tier) {
      case "CORE", "BROAD" -> "COVERED";
      case "EXCLUDED" -> "DECLINED";
      default -> "CANDIDATE";
    };
  }

  /** An http(s) URL with a host and no user info, at most {@value #MAX_URL} characters; stripped. */
  private static String url(String raw) {
    String s = raw.strip();
    if (s.isEmpty() || s.length() > MAX_URL) throw bad("url is 1 to " + MAX_URL + " characters");
    URI uri;
    try {
      uri = new URI(s);
    } catch (URISyntaxException e) {
      throw bad("url is not a valid address");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) throw bad("url must start with http:// or https://");
    if (uri.getHost() == null || uri.getHost().isBlank()) throw bad("url has no host");
    if (uri.getRawUserInfo() != null) throw bad("url must not contain a user name or password");
    return s;
  }

  private static @Nullable String string(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) return null;
    if (!(v instanceof String s)) throw bad(key + " must be a string");
    return s;
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such registration");
  }

  private static ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
