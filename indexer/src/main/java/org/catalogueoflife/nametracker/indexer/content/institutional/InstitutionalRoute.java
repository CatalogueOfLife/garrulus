package org.catalogueoflife.nametracker.indexer.content.institutional;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.indexer.content.ContentRoute;
import org.catalogueoflife.nametracker.indexer.content.FetchedContent;
import org.catalogueoflife.nametracker.indexer.content.LicencePolicy;
import org.catalogueoflife.nametracker.indexer.content.route.AbstractRoute;
import org.catalogueoflife.nametracker.indexer.content.route.OpenAlexTeiRoute;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.content.ContentMapper;
import org.catalogueoflife.nametracker.store.content.InstitutionalFetchMapper;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Route {@code INSTITUTIONAL}: the subscriber text-and-data-mining APIs reachable from the University of Copenhagen
 * (Elsevier, Wiley; {@link PublisherTdm}). <b>Off by default</b> ({@code nametracker.content.institutional.enabled},
 * empty allow-list {@code ...publishers}); it may be switched on only after the KU library has confirmed in writing
 * which licences permit text and data mining for this use (spec, "Institutional access").
 *
 * <p>It runs last ({@code @Order(100)}, after every open route), so only for a work no open route had the full text
 * of, and applies only to a work with a DOI that an allowed and configured publisher claims, whose licence is not open,
 * that has no full text from another route, and that the publisher has not answered for within {@code refetch-after}
 * (365 days: an OK, a 4xx or an unusable answer; an outage does not count). That last rule keeps the 7/30/90-day
 * rechecks and every re-resolve from fetching again; only the re-fetch of a forced {@code FIND_NAMES} whose text is gone
 * ({@code "reprocess":true}) asks again. The route costs nothing per call, so {@code freeOnly} does not skip it.
 *
 * <p>Each publisher has a daily cap (fetches a UTC day, counted in {@code institutional_fetch}); its fetches run one at
 * a time, so the count and the request cannot race. Every request is audited (one {@code institutional_fetch} row with
 * work, DOI, publisher, API, HTTP status, bytes and outcome), errors too. A 401 switches the publisher off until the
 * next start (one WARN: the token is wrong) and a 403 (not entitled to this article) is a non-retryable failure;
 * 429, 5xx and network failures (after PoliteHttp's retries) are retryable.
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
  private static final Logger LOG = LoggerFactory.getLogger(InstitutionalRoute.class);

  private final InstitutionalProperties props;
  private final PoliteHttp http;
  private final InstitutionalFetchMapper audit;
  private final ContentMapper contents;
  private final LicencePolicy policy;
  private final Clock clock;
  private final List<PublisherTdm> publishers;
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  /** Publishers whose credentials were refused (401) since start. */
  private final Set<String> refused = ConcurrentHashMap.newKeySet();
  /** The UTC day each publisher's cap was last logged, so a full day logs it once. */
  private final Map<String, LocalDate> capLogged = new ConcurrentHashMap<>();

  @Autowired
  public InstitutionalRoute(InstitutionalProperties props, PoliteHttp http, InstitutionalFetchMapper audit,
      ContentMapper contents, LicencePolicy policy, Clock clock, OpenAlexTeiRoute tei) {
    this(props, http, audit, contents, policy, clock, tei.knownWords());
  }

  InstitutionalRoute(InstitutionalProperties props, PoliteHttp http, InstitutionalFetchMapper audit,
      ContentMapper contents, LicencePolicy policy, Clock clock, Predicate<String> isKnownWord) {
    this.props = props;
    this.http = http;
    this.audit = audit;
    this.contents = contents;
    this.policy = policy;
    this.clock = clock;
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
                + "(nametracker.content.institutional.grobid-url) to read its PDFs, not loaded");
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
    return reprocess || !audit.answeredSince(w.id(), clock.instant().minus(props.refetchAfter()), ANSWERED);
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
    ReentrantLock lock = locks.computeIfAbsent(p.publisher(), k -> new ReentrantLock());
    lock.lock();
    try {
      if (capReached(p)) return Optional.empty();
      return request(p, w);
    } finally {
      lock.unlock();
    }
  }

  private boolean capReached(PublisherTdm p) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
    int cap = props.dailyCap(p.publisher());
    if (audit.countSince(p.publisher(), dayStart) < cap) return false;
    if (!today.equals(capLogged.put(p.publisher(), today))) {
      LOG.info("Institutional route: the daily cap of {} ({} fetches) is reached until 00:00 UTC", p.publisher(), cap);
    }
    return true;
  }

  private Optional<FetchedContent> request(PublisherTdm p, Work w) {
    String doi = w.doi().strip().toLowerCase(Locale.ROOT);
    URI url = p.url(doi);
    URI safe = PoliteHttp.redact(url);
    HttpResponse<byte[]> resp;
    try {
      resp = http.get(url, p.headers());
    } catch (HttpProblem e) {
      record(w, doi, p, e.status(), 0, e.retryable() ? "ERROR" : "FAILED");
      throw e;
    }
    int status = resp.statusCode();
    byte[] body = resp.body() == null ? new byte[0] : resp.body();
    if (status == 401) {
      record(w, doi, p, status, body.length, "UNAUTHORIZED");
      if (refused.add(p.publisher())) {
        LOG.warn("Institutional route: {} refused the credentials (401); it is off until the next start",
            p.publisher());
      }
      throw new HttpProblem(safe, status, false, "HTTP 401 from " + p.api() + ": " + safe, null);
    }
    if (status == 403) {
      record(w, doi, p, status, body.length, "REFUSED");
      throw new HttpProblem(safe, status, false, "HTTP 403 from " + p.api() + ": " + safe, null);
    }
    if (status == 404 || status == 410) {
      record(w, doi, p, status, body.length, "NOT_FOUND");
      return Optional.empty();
    }
    if (status < 200 || status >= 300 || body.length == 0) {
      record(w, doi, p, status, body.length, status >= 400 ? "CLIENT_ERROR" : "UNUSABLE");
      return Optional.empty();
    }
    Document doc;
    try {
      doc = p.convert(body, "work:" + w.id());
    } catch (HttpProblem e) { // a local service the conversion needs (GROBID)
      record(w, doi, p, status, body.length, e.retryable() ? "ERROR" : "UNUSABLE");
      if (e.retryable()) throw e;
      LOG.info("Unusable answer of {} for work {} at {}: {}", p.api(), w.id(), safe, e.getMessage());
      return Optional.empty();
    } catch (RuntimeException e) {
      record(w, doi, p, status, body.length, "UNUSABLE");
      LOG.info("Unusable answer of {} for work {} at {}: {}", p.api(), w.id(), safe, e.getMessage());
      return Optional.empty();
    }
    boolean fulltext = doc.blocks().stream().anyMatch(b -> b.type() == BlockType.PARAGRAPH);
    record(w, doi, p, status, body.length, fulltext ? "OK" : "NO_FULLTEXT");
    if (!fulltext) return Optional.empty();
    return Optional.of(new FetchedContent(ROUTE, p.kind(), body, doc, null, safe.toString(), true));
  }

  private void record(Work w, String doi, PublisherTdm p, int status, int bytes, String outcome) {
    audit.insert(w.id(), doi, p.publisher(), p.api(), status, bytes, outcome);
  }
}
