package org.catalogueoflife.nametracker.web.me;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.web.api.public_.SourceController;
import org.catalogueoflife.nametracker.web.api.public_.WorkController;
import org.catalogueoflife.nametracker.web.auth.CurrentUser;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;

/** The signed-in user's subscriptions (follows): list, create, change, delete and a new feed token. */
@RestController
public class SubscriptionController {

  static final int MAX_SUBSCRIPTIONS = 100;
  static final int MAX_LABEL = 200;
  static final int MAX_TAXON = 50;
  static final int MIN_YEAR = 1750;
  static final Set<String> TYPES = Set.of("TAXON", "SOURCE", "NAME", "TAXGROUP");
  static final Set<String> FILTER_KEYS =
      Set.of("code", "taxgroup", "subfield", "journalType", "tier", "oa", "country");
  private static final Set<String> CREATE_KEYS = Set.of("type", "target", "label", "mainSubjectOnly", "newNamesOnly",
      "includeAbstractOnly", "includeReferences", "filters", "sinceYear");
  private static final Set<String> PATCH_KEYS = Set.of("label", "mainSubjectOnly", "newNamesOnly",
      "includeAbstractOnly", "includeReferences", "filters", "sinceYear", "paused");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SubscriptionMapper subscriptions;
  private final SourceMapper sources;
  private final IndexMapper index;
  private final CurrentUser currentUser;
  private final Clock clock;
  private final String publicUrl;

  public SubscriptionController(SubscriptionMapper subscriptions, SourceMapper sources, IndexMapper index,
      CurrentUser currentUser, Clock clock, @Value("${nametracker.public-url}") String publicUrl) {
    this.subscriptions = subscriptions;
    this.sources = sources;
    this.index = index;
    this.currentUser = currentUser;
    this.clock = clock;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
  }

  /** The DTO of a subscription (also used by the account export). */
  public SubscriptionDto dto(Subscription s) {
    Source src = s.type().equals("SOURCE") ? sourceOf(s.target()) : null;
    return new SubscriptionDto(s.id(), s.type(), s.target(), s.label(), s.mainSubjectOnly(), s.newNamesOnly(),
        s.includeAbstractOnly(), s.includeReferences(), filtersOf(s.filters()), s.sinceYear(), s.paused(),
        feedUrl(s.feedToken()), s.created(), s.targetStatus(), s.xrNote(), s.previousTarget(),
        src == null ? null : src.publicationStatus(), src == null ? null : src.lastIssueYear(),
        src == null ? null : SourceController.ref(sources, src));
  }

  private @Nullable Source sourceOf(String target) {
    try {
      return sources.get(Long.parseLong(target));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  public String feedUrl(String token) {
    return publicUrl + "/feeds/s/" + token + ".atom";
  }

  @GetMapping("/api/me/subscriptions")
  public List<SubscriptionDto> list() {
    return subscriptions.listByUser(currentUser.require()).stream().map(this::dto).toList();
  }

  @PostMapping("/api/me/subscriptions")
  public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
    long userId = currentUser.require();
    rejectUnknown(body, CREATE_KEYS);
    String type = string(body, "type");
    if (type == null || !TYPES.contains(type)) {
      throw bad("type is TAXON, SOURCE, NAME or TAXGROUP");
    }
    String target = target(type, string(body, "target"));
    String label = label(string(body, "label"));
    boolean mainDefault = type.equals("TAXON") || type.equals("TAXGROUP");

    List<Subscription> mine = subscriptions.listByUser(userId);
    for (Subscription s : mine) {
      if (s.type().equals(type) && s.target().equals(target)) {
        return conflict("exists", s.id());
      }
    }
    if (mine.size() >= MAX_SUBSCRIPTIONS) {
      return conflict("limit", null);
    }
    Subscription s = new Subscription(null, userId, type, target, label, bool(body, "mainSubjectOnly", mainDefault),
        bool(body, "newNamesOnly", false), bool(body, "includeAbstractOnly", true),
        bool(body, "includeReferences", false), filtersJson(body.get("filters")), newToken(),
        sinceYear(body.get("sinceYear")), false, null);
    long id;
    try {
      id = subscriptions.insert(s);
    } catch (DuplicateKeyException e) {
      Subscription existing = subscriptions.listByUser(userId).stream()
          .filter(x -> x.type().equals(type) && x.target().equals(target)).findFirst().orElse(null);
      return conflict("exists", existing == null ? null : existing.id());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(dto(owned(userId, id)));
  }

  @PatchMapping("/api/me/subscriptions/{id}")
  public SubscriptionDto update(@PathVariable long id, @RequestBody Map<String, Object> body) {
    long userId = currentUser.require();
    Subscription s = owned(userId, id);
    rejectUnknown(body, PATCH_KEYS);
    Subscription u = new Subscription(s.id(), userId, s.type(), s.target(),
        body.containsKey("label") ? label(string(body, "label")) : s.label(),
        bool(body, "mainSubjectOnly", s.mainSubjectOnly()), bool(body, "newNamesOnly", s.newNamesOnly()),
        bool(body, "includeAbstractOnly", s.includeAbstractOnly()),
        bool(body, "includeReferences", s.includeReferences()),
        body.containsKey("filters") ? filtersJson(body.get("filters")) : s.filters(), s.feedToken(),
        body.containsKey("sinceYear") ? sinceYear(body.get("sinceYear")) : s.sinceYear(),
        bool(body, "paused", s.paused()), s.created());
    subscriptions.update(u);
    return dto(owned(userId, id));
  }

  @DeleteMapping("/api/me/subscriptions/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    if (subscriptions.delete(id, currentUser.require()) == 0) {
      throw notFound();
    }
    return ResponseEntity.noContent().build();
  }

  /** Issues a new feed token; the previous feed URL stops working. */
  @PostMapping("/api/me/subscriptions/{id}/feed-token")
  public SubscriptionDto newFeedToken(@PathVariable long id) {
    long userId = currentUser.require();
    owned(userId, id);
    subscriptions.setFeedToken(id, newToken());
    return dto(owned(userId, id));
  }

  private Subscription owned(long userId, long id) {
    return subscriptions.listByUser(userId).stream().filter(s -> s.id() == id).findFirst()
        .orElseThrow(SubscriptionController::notFound);
  }

  static String newToken() {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private String target(String type, @Nullable String raw) {
    String t = raw == null ? "" : raw.strip();
    switch (type) {
      case "TAXON" -> {
        if (t.isEmpty() || t.codePointCount(0, t.length()) > MAX_TAXON) {
          throw bad("target is a taxon id of 1 to " + MAX_TAXON + " characters");
        }
        return t;
      }
      case "TAXGROUP" -> {
        if (!WorkController.TAXGROUPS.contains(t)) {
          throw bad("unknown taxgroup " + t);
        }
        return t;
      }
      default -> {
        long id;
        try {
          id = Long.parseLong(t);
        } catch (NumberFormatException e) {
          throw unknownTarget();
        }
        boolean exists = type.equals("SOURCE") ? sources.get(id) != null : index.nameString(id) != null;
        if (!exists) {
          throw unknownTarget();
        }
        return Long.toString(id);
      }
    }
  }

  private static String label(@Nullable String raw) {
    String l = raw == null ? "" : raw.strip();
    if (l.isEmpty() || l.codePointCount(0, l.length()) > MAX_LABEL) {
      throw bad("label is 1 to " + MAX_LABEL + " characters");
    }
    return l;
  }

  private @Nullable Integer sinceYear(@Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (!(v instanceof Integer y)) {
      throw bad("sinceYear is an integer year");
    }
    int max = Year.now(clock.withZone(ZoneOffset.UTC)).getValue();
    if (y < MIN_YEAR || y > max) {
      throw bad("sinceYear is " + MIN_YEAR + " to " + max);
    }
    return y;
  }

  /** The filters as the JSON object text of the column, or null for none. Scalars only, the seven known keys. */
  private static @Nullable String filtersJson(@Nullable Object v) {
    if (v == null) {
      return null;
    }
    if (!(v instanceof Map<?, ?> m)) {
      throw bad("filters is an object");
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : m.entrySet()) {
      String key = String.valueOf(e.getKey());
      if (!FILTER_KEYS.contains(key)) {
        throw bad("unknown filter " + key);
      }
      Object val = e.getValue();
      if (!(val instanceof String || val instanceof Boolean || val instanceof Number)) {
        throw bad("filter " + key + " must be a single value");
      }
      String sv = val.toString().strip();
      if (sv.isEmpty()) {
        throw bad("filter " + key + " is empty");
      }
      switch (key) {
        case "code" -> WorkController.requireOneOf(key, sv, WorkController.CODES);
        case "taxgroup" -> WorkController.requireOneOf(key, sv, WorkController.TAXGROUPS);
        case "tier" -> WorkController.requireOneOf(key, sv, WorkController.TIERS);
        case "oa" -> WorkController.requireOneOf(key, sv, WorkController.OA);
        case "journalType" -> WorkController.requireOneOf(key, sv, WorkController.JOURNAL_TYPES);
        default -> { }
      }
      out.put(key, sv);
    }
    return out.isEmpty() ? null : FinderJson.mapper().writeValueAsString(out);
  }

  private static Map<String, Object> filtersOf(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return FinderJson.mapper().readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private static void rejectUnknown(Map<String, Object> body, Set<String> allowed) {
    for (String k : body.keySet()) {
      if (!allowed.contains(k)) {
        throw bad("unknown field " + k);
      }
    }
  }

  private static @Nullable String string(Map<String, Object> body, String key) {
    Object v = body.get(key);
    if (v == null) {
      return null;
    }
    if (!(v instanceof String s)) {
      throw bad(key + " must be a string");
    }
    return s;
  }

  private static boolean bool(Map<String, Object> body, String key, boolean dflt) {
    Object v = body.get(key);
    if (v == null) {
      return dflt;
    }
    if (!(v instanceof Boolean b)) {
      throw bad(key + " must be true or false");
    }
    return b;
  }

  private static ResponseEntity<Map<String, Object>> conflict(String error, @Nullable Long id) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", 409);
    m.put("error", error);
    if (id != null) {
      m.put("id", id);
    }
    return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
  }

  private static ResponseStatusException unknownTarget() {
    return bad("unknown target");
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such subscription");
  }

  private static ResponseStatusException bad(String reason) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
  }
}
