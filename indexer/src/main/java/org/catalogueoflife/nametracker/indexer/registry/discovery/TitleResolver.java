package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
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
 * the journals found. A row is taken from the first of the five best items whose {@link JournalTitleKey} equals the row's; any
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
      List<String> issns = match(row);
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

  private @Nullable List<String> match(DiscoveryTitle row) {
    for (JsonNode item : crossref.journalsByTitle(row.title(), 5)) {
      if (!row.titleKey().equals(JournalTitleKey.of(item.path("title").asString("")))) continue;
      List<String> issns = new ArrayList<>();
      item.path("ISSN").forEach(i -> issns.add(i.asString("")));
      return issns.isEmpty() ? null : issns;
    }
    return null;
  }
}
