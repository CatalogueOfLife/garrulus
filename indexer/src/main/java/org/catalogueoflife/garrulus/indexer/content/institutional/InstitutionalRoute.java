package org.catalogueoflife.garrulus.indexer.content.institutional;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.indexer.content.ContentRoute;
import org.catalogueoflife.garrulus.indexer.content.FetchedContent;
import org.catalogueoflife.garrulus.indexer.content.LicencePolicy;
import org.catalogueoflife.garrulus.indexer.content.route.AbstractRoute;
import org.catalogueoflife.garrulus.indexer.content.route.OpenAlexTeiRoute;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.content.ContentMapper;
import org.catalogueoflife.garrulus.store.content.InstitutionalFetchMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Route {@code INSTITUTIONAL}: the subscriber text-and-data-mining APIs reachable from the University of Copenhagen
 * (Elsevier, Wiley; {@link PublisherTdm}). <b>Off by default</b> ({@code garrulus.content.institutional.enabled},
 * empty allow-list {@code ...publishers}); it may be switched on only after the KU library has confirmed in writing
 * which licences permit text and data mining for this use (spec, "Institutional access").
 *
 * <p>A route of last resort ({@link #lastResort()}, {@code @Order(100)}): {@code RESOLVE_CONTENT} asks it only when
 * every open route before it answered without a full text (not when one was down or waited for its budget). It applies
 * only to a work with a DOI that an allowed and configured publisher claims, whose licence is not open, that has no
 * full text from another route, and that the publisher has not answered for within {@code refetch-after} (365 days:
 * an OK, a 404, a 403 below the breaker, an unusable answer) nor failed for good within 30 days (a body over the size
 * limit). An outage does not count. That rule keeps the 7/30/90-day rechecks and every re-resolve from fetching
 * again; only the re-fetch of a forced {@code FIND_NAMES} whose text is gone ({@code "reprocess":true}) asks again.
 * The route costs nothing per call, so {@code freeOnly} does not skip it.
 *
 * <p>Every request is a single attempt ({@link PoliteHttp#getOnce}: no retries inside the route) and is audited and
 * counted: one {@code institutional_fetch} row with work, DOI, publisher, API, HTTP status, bytes and outcome. The
 * daily cap (fetches a UTC day, counted in that table) is checked under a per-publisher lock held only for the count,
 * the request and its audit row; a worker that cannot get the lock within {@link #LOCK_WAIT} defers its task. The
 * answer is converted (GROBID for Wiley) outside the lock and the row's outcome corrected when it is unusable.
 * <ul>
 *   <li>429, 5xx, an I/O failure or GROBID down: outcome ERROR, the task is deferred 30 minutes
 *       ({@link ServiceOutageException});</li>
 *   <li>the daily cap reached: the task is deferred to 00:05 UTC ({@link BudgetExhaustedException});</li>
 *   <li>401: the token is wrong, the publisher is off until the next start (one WARN), the work not blocked;</li>
 *   <li>403: not entitled to this article (REFUSED, blocks the work), unless {@code max-consecutive-403} come in a row:
 *       then the token is refused, the publisher is off until the next start (WARN) and that streak's rows become
 *       REFUSED_SYSTEMIC, which blocks nothing;</li>
 *   <li>a body over the size limit or a refused host: FAILED, a non-retryable failure that blocks the work 30 days.</li>
 * </ul>
 *
 * <p>The text is closed ({@code license = null}): {@code RESOLVE_CONTENT} keeps only hashes and the URL, spools the
 * document and {@code FIND_NAMES} purges it after naming, so only mentions, offsets, snippets and hashes remain. An
 * abstract in the answer is never written to the work: it reaches the finder only inside the spooled document. Tokens
 * travel in headers, never in the URL, and PoliteHttp drops them on a redirect to another host.
 */
@Component
@Order(100)
public class InstitutionalRoute implements ContentRoute {

  public static final String ROUTE = "INSTITUTIONAL";
  /** Answers that settle a work for {@code refetch-after}. */
  static final Set<String> ANSWERED = Set.of("OK", "NO_FULLTEXT", "UNUSABLE", "NOT_FOUND", "REFUSED", "CLIENT_ERROR");
  /** A failure that would repeat (a body over the size limit) settles a work for this long. */
  static final Duration FAILED_BLOCK = Duration.ofDays(30);
  /** How long a worker waits for a publisher's lock before it defers its task. */
  static final Duration LOCK_WAIT = Duration.ofSeconds(90);
  static final Duration OUTAGE_DELAY = Duration.ofMinutes(30);
  static final Duration BUSY_DELAY = Duration.ofMinutes(5);
  private static final Logger LOG = LoggerFactory.getLogger(InstitutionalRoute.class);

  private final InstitutionalProperties props;
  private final PoliteHttp http;
  private final InstitutionalFetchMapper audit;
  private final ContentMapper contents;
  private final LicencePolicy policy;
  private final Clock clock;
  private final Duration lockWait;
  private final List<PublisherTdm> publishers;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  /** Publishers whose credentials were refused (401, or a run of 403s) since start. */
  private final Set<String> refused = ConcurrentHashMap.newKeySet();
  /** Per publisher, the audit rows of the current run of 403s; guarded by the publisher's lock. */
  private final Map<String, List<Long>> refusals = new ConcurrentHashMap<>();
  /** The UTC day each publisher's cap was last logged, so a full day logs it once. */
  private final Map<String, LocalDate> capLogged = new ConcurrentHashMap<>();

  @Autowired
  public InstitutionalRoute(InstitutionalProperties props, PoliteHttp http, InstitutionalFetchMapper audit,
      ContentMapper contents, LicencePolicy policy, Clock clock, OpenAlexTeiRoute tei) {
    this(props, http, audit, contents, policy, clock, tei.knownWords(), LOCK_WAIT);
  }

  InstitutionalRoute(InstitutionalProperties props, PoliteHttp http, InstitutionalFetchMapper audit,
      ContentMapper contents, LicencePolicy policy, Clock clock, Predicate<String> isKnownWord, Duration lockWait) {
    this.props = props;
    this.http = http;
    this.audit = audit;
    this.contents = contents;
    this.policy = policy;
    this.clock = clock;
    this.lockWait = lockWait;
    this.publishers = load(props, isKnownWord);
  }

  /** The allowed publishers that are configured; logs why the others are left out. */
  private static List<PublisherTdm> load(InstitutionalProperties p, Predicate<String> isKnownWord) {
    List<PublisherTdm> loaded = new ArrayList<>();
    if (!p.enabled()) return loaded;
    for (String name : p.publishers()) {
      switch (name) {
        case ElsevierTdm.PUBLISHER -> {
          if (p.elsevierApiKey() == null) {
            LOG.warn("Institutional route: ELSEVIER is allowed but ELSEVIER_API_KEY is blank, not loaded");
          } else {
            loaded.add(new ElsevierTdm(p.elsevierBaseUrl(), p.elsevierApiKey(), p.elsevierInsttoken()));
          }
        }
        case WileyTdm.PUBLISHER -> {
          if (p.wileyToken() == null) {
            LOG.warn("Institutional route: WILEY is allowed but WILEY_TDM_TOKEN is blank, not loaded");
          } else if (p.grobidUrl() == null) {
            LOG.warn("Institutional route: WILEY is allowed but no GROBID is configured "
                + "(garrulus.content.institutional.grobid-url) to read its PDFs, not loaded");
          } else {
            loaded.add(new WileyTdm(p.wileyBaseUrl(), p.wileyToken(), new GrobidPdfClient(p.grobidUrl()),
                isKnownWord));
          }
        }
        default -> LOG.warn("Institutional route: unknown publisher {} in the allow-list, ignored", name);
      }
    }
    if (loaded.isEmpty()) {
      LOG.warn("Institutional route is switched on but no publisher is allowed and configured");
    } else {
      LOG.info("Institutional route on for {}", loaded.stream().map(PublisherTdm::publisher).toList());
    }
    return loaded;
  }

  /** The publishers this route asks, in allow-list order; empty when it is off. */
  public List<String> publishers() {
    return publishers.stream().map(PublisherTdm::publisher).toList();
  }

  @Override
  public String route() {
    return ROUTE;
  }

  @Override
  public boolean lastResort() {
    return true;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return applies(w, s, false);
  }

  @Override
  public boolean applies(Work w, @Nullable Source s, boolean reprocess) {
    if (publishers.isEmpty() || w.id() == null) return false;
    PublisherTdm p = publisher(w);
    if (p == null || refused.contains(p.publisher()) || policy.isOpen(w.license())) return false;
    // only for a work no other route has the full text of
    boolean otherFullText = contents.listByWork(w.id()).stream()
        .anyMatch(c -> !AbstractRoute.ROUTE.equals(c.route()) && !ROUTE.equals(c.route()));
    if (otherFullText) return false;
    if (reprocess) return true;
    Instant now = clock.instant();
    return !audit.answeredSince(w.id(), now.minus(props.refetchAfter()), ANSWERED)
        && !audit.answeredSince(w.id(), now.minus(FAILED_BLOCK), Set.of("FAILED"));
  }

  private @Nullable PublisherTdm publisher(Work w) {
    if (w.doi() == null || w.doi().isBlank()) return null;
    String doi = w.doi().strip().toLowerCase(Locale.ROOT);
    for (PublisherTdm p : publishers) {
      if (p.claims(doi)) return p;
    }
    return null;
  }

  /** Call only after {@link #applies(Work, Source, boolean)}: it checks the publisher, the cap and nothing else. */
  @Override
  public Optional<FetchedContent> fetch(Work w, @Nullable Source s) {
    PublisherTdm p = publisher(w);
    if (p == null || w.id() == null || refused.contains(p.publisher())) return Optional.empty();
    String doi = w.doi().strip().toLowerCase(Locale.ROOT);
    Answer a = requestLocked(p, w.id(), doi);
    if (a == null) return Optional.empty();
    URI safe = PoliteHttp.redact(p.url(doi));
    Document doc;
    try {
      doc = p.convert(a.body(), "work:" + w.id());
    } catch (HttpProblem e) { // a local service the conversion needs (GROBID)
      if (e.retryable()) {
        audit.setOutcome(List.of(a.auditId()), "ERROR");
        throw new ServiceOutageException("GROBID", clock.instant().plus(OUTAGE_DELAY), e);
      }
      return unusable(a, p, w, safe, e);
    } catch (RuntimeException e) {
      return unusable(a, p, w, safe, e);
    }
    if (doc.blocks().stream().noneMatch(b -> b.type() == BlockType.PARAGRAPH)) {
      audit.setOutcome(List.of(a.auditId()), "NO_FULLTEXT");
      return Optional.empty();
    }
    return Optional.of(new FetchedContent(ROUTE, p.kind(), a.body(), doc, null, safe.toString(), true));
  }

  private Optional<FetchedContent> unusable(Answer a, PublisherTdm p, Work w, URI safe, RuntimeException e) {
    audit.setOutcome(List.of(a.auditId()), "UNUSABLE");
    LOG.info("Unusable answer of {} for work {} at {}: {}", p.api(), w.id(), safe, e.getMessage());
    return Optional.empty();
  }

  /** A 200 with a body, audited OK until the conversion says otherwise. */
  private record Answer(long auditId, byte[] body) {
  }

  /** The cap check, the one request and its audit row, under the publisher's lock. */
  private @Nullable Answer requestLocked(PublisherTdm p, long workId, String doi) {
    ReentrantLock lock = locks.computeIfAbsent(p.publisher(), k -> new ReentrantLock());
    try {
      if (!lock.tryLock(lockWait.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new ServiceOutageException(p.publisher() + " TDM (busy)", clock.instant().plus(BUSY_DELAY));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceOutageException(p.publisher() + " TDM (interrupted)", clock.instant().plus(BUSY_DELAY), e);
    }
    try {
      if (refused.contains(p.publisher())) return null; // switched off while this worker waited
      checkCap(p);
      return request(p, workId, doi);
    } finally {
      lock.unlock();
    }
  }

  private void checkCap(PublisherTdm p) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    int cap = props.dailyCap(p.publisher());
    if (audit.countSince(p.publisher(), today.atStartOfDay(ZoneOffset.UTC).toInstant()) < cap) return;
    if (!today.equals(capLogged.put(p.publisher(), today))) {
      LOG.info("Institutional route: the daily cap of {} ({} fetches) is reached until 00:00 UTC", p.publisher(), cap);
    }
    throw new BudgetExhaustedException("institutional " + p.publisher(),
        today.plusDays(1).atTime(LocalTime.of(0, 5)).toInstant(ZoneOffset.UTC));
  }

  private @Nullable Answer request(PublisherTdm p, long workId, String doi) {
    URI url = p.url(doi);
    URI safe = PoliteHttp.redact(url);
    HttpResponse<byte[]> resp;
    try {
      resp = http.getOnce(url, p.headers());
    } catch (HttpProblem e) {
      if (e.retryable()) {
        record(workId, doi, p, e.status(), 0, "ERROR");
        throw new ServiceOutageException(p.api(), clock.instant().plus(OUTAGE_DELAY), e);
      }
      record(workId, doi, p, e.status(), 0, "FAILED");
      throw e;
    }
    int status = resp.statusCode();
    byte[] body = resp.body() == null ? new byte[0] : resp.body();
    if (status == 403) {
      refusal(p, record(workId, doi, p, status, body.length, "REFUSED"));
      throw new HttpProblem(safe, status, false, "HTTP 403 from " + p.api() + ": " + safe, null);
    }
    refusals.remove(p.publisher()); // any other answer ends a run of 403s
    if (status == 429 || status >= 500) {
      record(workId, doi, p, status, body.length, "ERROR");
      throw new ServiceOutageException(p.api() + " (HTTP " + status + ")", clock.instant().plus(OUTAGE_DELAY));
    }
    if (status == 401) {
      record(workId, doi, p, status, body.length, "UNAUTHORIZED");
      switchOff(p, "refused the credentials (401)");
      throw new HttpProblem(safe, status, false, "HTTP 401 from " + p.api() + ": " + safe, null);
    }
    if (status == 404 || status == 410) {
      record(workId, doi, p, status, body.length, "NOT_FOUND");
      return null;
    }
    if (status < 200 || status >= 300 || body.length == 0) {
      record(workId, doi, p, status, body.length, status >= 400 ? "CLIENT_ERROR" : "UNUSABLE");
      return null;
    }
    return new Answer(record(workId, doi, p, status, body.length, "OK"), body);
  }

  /** Counts a 403; at {@code max-consecutive-403} in a row the token is refused, not the articles. */
  private void refusal(PublisherTdm p, long auditId) {
    List<Long> run = refusals.computeIfAbsent(p.publisher(), k -> new ArrayList<>());
    run.add(auditId);
    if (run.size() < props.maxConsecutive403()) return;
    audit.setOutcome(List.copyOf(run), "REFUSED_SYSTEMIC");
    refusals.remove(p.publisher());
    switchOff(p, "answered " + run.size() + " 403s in a row (the token is refused)");
  }

  private void switchOff(PublisherTdm p, String why) {
    if (refused.add(p.publisher())) {
      LOG.warn("Institutional route: {} {}; it is off until the next start", p.publisher(), why);
    }
  }

  private long record(long workId, String doi, PublisherTdm p, int status, int bytes, String outcome) {
    return audit.insert(workId, doi, p.publisher(), p.api(), status, bytes, outcome);
  }
}
