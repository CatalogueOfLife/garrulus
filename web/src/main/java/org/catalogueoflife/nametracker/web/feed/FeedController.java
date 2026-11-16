package org.catalogueoflife.nametracker.web.feed;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.store.account.HitMapper;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.names.NameMapper;
import org.catalogueoflife.nametracker.store.search.FeedRow;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Atom feeds. Public feeds (taxon, source, name, taxgroup) list the works indexed for the target, published in the last
 * {@code nametracker.feeds.window-days} days, 50 at most, newest first_indexed_at first. The private feed
 * {@code /feeds/s/{token}.atom} lists the hits of one subscription; its token is the only credential. No cookies are set
 * and no email address is written.
 */
@RestController
public class FeedController {

  private static final Logger LOG = LoggerFactory.getLogger(FeedController.class);
  static final int LIMIT = 50;
  static final MediaType ATOM = MediaType.parseMediaType("application/atom+xml;charset=UTF-8");
  private static final Set<String> PUBLIC_TIERS = Set.of("CORE", "BROAD");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final SearchMapper search;
  private final SourceMapper sources;
  private final NameMapper names;
  private final SubscriptionMapper subscriptions;
  private final HitMapper hits;
  private final String publicUrl;
  private final int windowDays;

  public FeedController(SearchMapper search, SourceMapper sources, NameMapper names, SubscriptionMapper subscriptions,
      HitMapper hits, @Value("${nametracker.public-url}") String publicUrl,
      @Value("${nametracker.feeds.window-days}") int windowDays) {
    this.search = search;
    this.sources = sources;
    this.names = names;
    this.subscriptions = subscriptions;
    this.hits = hits;
    this.publicUrl = publicUrl.strip().replaceAll("/+$", "");
    this.windowDays = windowDays;
  }

  @GetMapping("/feeds/taxon/{colId}.atom")
  public ResponseEntity<byte[]> taxon(@PathVariable String colId) {
    // a COL id cannot be checked without calling CLB: an unknown one is an empty feed
    return publicFeed("taxon/" + colId, "taxon " + colId,
        filter(colId, null, null, null, true));
  }

  @GetMapping("/feeds/source/{id}.atom")
  public ResponseEntity<byte[]> source(@PathVariable long id) {
    Source s = sources.get(id);
    if (s == null || !Boolean.TRUE.equals(s.active()) || !PUBLIC_TIERS.contains(s.tier())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
    }
    return publicFeed("source/" + id, s.title(), filter(null, null, null, id, false));
  }

  @GetMapping("/feeds/name/{id}.atom")
  public ResponseEntity<byte[]> name(@PathVariable long id) {
    var n = names.get(id);
    if (n == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no name " + id);
    }
    return publicFeed("name/" + id, n.name(), filter(null, id, null, null, false));
  }

  @GetMapping("/feeds/taxgroup/{group}.atom")
  public ResponseEntity<byte[]> taxgroup(@PathVariable String group) {
    try {
      TaxGroup.valueOf(group);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no taxgroup " + group);
    }
    return publicFeed("taxgroup/" + group, group, filter(null, null, group, null, true));
  }

  @GetMapping("/feeds/s/{token}.atom")
  public ResponseEntity<byte[]> subscription(@PathVariable String token) {
    Subscription sub = subscriptions.byFeedToken(token);
    if (sub == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no feed");
    }
    List<Hit> rows = hits.byFeed(sub.id(), LIMIT);
    Map<Long, FeedRow> works = rows.isEmpty() ? Map.of() : search.feedByIds(rows.stream().map(Hit::workId).toList())
        .stream().collect(Collectors.toMap(FeedRow::id, Function.identity()));
    List<AtomWriter.Entry> entries = new ArrayList<>();
    for (Hit h : rows) {
      FeedRow w = works.get(h.workId());
      if (w != null) {
        entries.add(entry(w, hitLines(h)));
      }
    }
    String self = publicUrl + "/feeds/s/" + token + ".atom";
    byte[] xml = AtomWriter.write(new AtomWriter.Feed(self, "Garrulus: " + sub.label(), newest(entries), entries));
    // the token is the only credential: keep the URL out of search engines and referrers
    return ResponseEntity.ok().contentType(ATOM).header("Cache-Control", "private, no-store")
        .header("X-Robots-Tag", "noindex").header("Referrer-Policy", "no-referrer").body(xml);
  }

  private static WorkFilter filter(String taxon, Long name, String taxgroup, Long source, boolean mainSubjectOnly) {
    return new WorkFilter(taxon, name, null, taxgroup, null, null, null, null, null, source, null, null, null, null,
        mainSubjectOnly, null);
  }

  private ResponseEntity<byte[]> publicFeed(String path, String label, WorkFilter f) {
    List<AtomWriter.Entry> entries = search.feed(f, windowDays, LIMIT).stream().map(w -> entry(w, "")).toList();
    String self = publicUrl + "/feeds/" + path + ".atom";
    byte[] xml = AtomWriter.write(new AtomWriter.Feed(self, "Garrulus: " + label, newest(entries), entries));
    return ResponseEntity.ok().contentType(ATOM).header("Cache-Control", "public, max-age=900").body(xml);
  }

  private static Instant newest(List<AtomWriter.Entry> entries) {
    return entries.stream().map(AtomWriter.Entry::updated).max(Comparator.naturalOrder()).orElse(null);
  }

  private AtomWriter.Entry entry(FeedRow w, String hitLines) {
    String url = publicUrl + "/work/" + w.id();
    StringBuilder sum = new StringBuilder();
    if (w.sourceTitle() != null) {
      sum.append(w.sourceTitle());
    }
    LocalDate date = w.publicationDate();
    if (date != null) {
      sum.append(sum.isEmpty() ? "" : ", ").append(date);
    } else if (w.year() != null) {
      sum.append(sum.isEmpty() ? "" : ", ").append(w.year());
    }
    if (!hitLines.isEmpty()) {
      sum.append(sum.isEmpty() ? "" : "\n").append(hitLines);
    }
    return new AtomWriter.Entry(url, w.title() == null || w.title().isBlank() ? "(untitled)" : w.title(), url,
        w.doi() == null || w.doi().isBlank() ? null : "https://doi.org/" + w.doi(), w.firstIndexedAt(), sum.toString());
  }

  /** "Matching names: A, B" and "new: C" for the names of a hit; new names are the NEW_NAMES ones or those with acts. */
  private static String hitLines(Hit h) {
    Set<String> matching = new LinkedHashSet<>();
    Set<String> fresh = new LinkedHashSet<>();
    try {
      JsonNode arr = JSON.readTree(h.names());
      for (JsonNode n : arr) {
        String name = n.path("name").asString("");
        if (name.isBlank()) {
          continue;
        }
        boolean act = n.path("actTypes").isArray() && !n.path("actTypes").isEmpty();
        if ("NEW_NAMES".equals(h.kind()) || act) {
          fresh.add(name);
        } else {
          matching.add(name);
        }
      }
    } catch (RuntimeException e) {
      LOG.warn("Unreadable names of hit {}: {}", h.id(), e.toString());
    }
    StringBuilder b = new StringBuilder();
    if (!matching.isEmpty()) {
      b.append("Matching names: ").append(String.join(", ", matching));
    }
    for (String n : fresh) {
      b.append(b.isEmpty() ? "" : "\n").append("new: ").append(n);
    }
    return b.toString();
  }
}
