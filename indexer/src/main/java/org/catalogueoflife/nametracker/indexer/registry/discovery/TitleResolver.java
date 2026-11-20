package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.harvest.HarvestOutage;
import org.catalogueoflife.nametracker.indexer.task.ServiceOutageException;
import org.catalogueoflife.nametracker.indexer.registry.Candidate;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.store.registry.DiscoveryTitle;
import org.catalogueoflife.nametracker.store.registry.DiscoveryTitleMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns the journal titles of a discovery origin into ISSNs through Crossref {@code /journals?query} (free) and offers
 * the journals found. A row is taken from the five best items whose {@link JournalTitleKey} equals the row's, when they are one journal (two with different ISSN sets leave the title unresolved); any
 * other row is only marked looked at ({@code resolved_at}) and tried again after 180 days. A Crossref outage throws and
 * defers the page; rows of the page that were done are marked and not repeated.
 */
@Component
public class TitleResolver {

  static final int PAGE = 100;
  static final Duration STALE = Duration.ofDays(180);
  private static final Logger LOG = LoggerFactory.getLogger(TitleResolver.class);

  private final DiscoveryTitleMapper titles;
  private final CrossrefClient crossref;
  private final CandidateRegistry registry;
  private final Clock clock;

  public TitleResolver(DiscoveryTitleMapper titles, CrossrefClient crossref, CandidateRegistry registry, Clock clock) {
    this.titles = titles;
    this.crossref = crossref;
    this.registry = registry;
    this.clock = clock;
  }

  /** Resolves up to {@code size} rows after {@code afterKey}; returns the last key when there may be more, else null. */
  public @Nullable String resolvePage(String origin, @Nullable String afterKey, int size) {
    List<DiscoveryTitle> rows = titles.unresolved(origin, afterKey, clock.instant().minus(STALE), size);
    Offers offers = new Offers(registry);
    int unmatched = 0;
    for (DiscoveryTitle row : rows) {
      List<String> issns;
      try {
        issns = match(row);
      } catch (ServiceOutageException e) {
        throw e;
      } catch (RuntimeException e) {
        if (HarvestOutage.retryable(e) != null) throw e; // an outage defers the page
        LOG.warn("{} title '{}' not resolved: {}", origin, row.title(), e.toString());
        issns = null;
      }
      titles.markResolved(origin, row.titleKey(), issns, clock.instant());
      if (issns == null) {
        unmatched++;
        continue;
      }
      offers.offer(new Candidate(origin, row.titleKey(), issns, row.title(), null, null, null, row.detail(),
          List.of(), null));
    }
    LOG.info("{} titles: {} of {} not found in Crossref; {}", origin, unmatched, rows.size(), offers.summary());
    return rows.size() < size ? null : rows.getLast().titleKey();
  }

  /** The ISSNs of the one journal whose title key equals the row's; null for none or for several different ones. */
  private @Nullable List<String> match(DiscoveryTitle row) {
    List<List<String>> hits = new ArrayList<>();
    for (JsonNode item : crossref.journalsByTitle(row.title(), 5)) {
      if (!row.titleKey().equals(JournalTitleKey.of(item.path("title").asString("")))) continue;
      List<String> issns = new ArrayList<>();
      item.path("ISSN").forEach(i -> issns.add(i.asString("")));
      if (!issns.isEmpty()) hits.add(issns);
    }
    if (hits.isEmpty()) return null;
    Set<Set<String>> distinct = new HashSet<>();
    hits.forEach(h -> distinct.add(new HashSet<>(h)));
    if (distinct.size() > 1) {
      LOG.info("{} title '{}' is ambiguous in Crossref ({} journals with that title): left unresolved", row.origin(),
          row.title(), distinct.size());
      return null;
    }
    return hits.getFirst();
  }
}
