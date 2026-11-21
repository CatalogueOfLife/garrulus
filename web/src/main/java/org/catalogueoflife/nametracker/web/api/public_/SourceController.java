package org.catalogueoflife.nametracker.web.api.public_;

import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.catalogueoflife.nametracker.store.source.Source;
import org.catalogueoflife.nametracker.store.stats.StatsMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceDetail;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceItem;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourcePage;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** The journals that are followed: active sources of tier CORE or BROAD. Candidates and excluded ones do not exist here. */
@RestController
public class SourceController {

  private static final Logger LOG = LoggerFactory.getLogger(SourceController.class);
  private static final Set<String> PUBLIC_TIERS = Set.of("CORE", "BROAD");
  private static final Set<String> STATUSES = Set.of("ACTIVE", "CEASED");
  private static final Set<String> JOURNAL_TYPES = Set.of("TAXONOMIC", "GENERAL");
  static final int RECENT = 10;

  private final SourceMapper sources;
  private final WorkController works;
  private final SearchMapper search;
  private final IdentifierViews identifiers;
  private final StatsMapper statistics;

  public SourceController(SourceMapper sources, WorkController works, SearchMapper search, IdentifierViews identifiers,
      StatsMapper statistics) {
    this.statistics = statistics;
    this.identifiers = identifiers;
    this.search = search;
    this.sources = sources;
    this.works = works;
  }

  @GetMapping("/api/sources")
  public SourcePage list(@RequestParam(required = false) String q, @RequestParam(required = false) String tier,
      @RequestParam(required = false) String journalType,
      @RequestParam(required = false) String publicationStatus, @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    WorkController.checkPaging(limit, offset);
    if (tier != null && !PUBLIC_TIERS.contains(tier)) {
      throw WorkController.bad("unknown tier " + tier);
    }
    if (journalType != null && !JOURNAL_TYPES.contains(journalType)) {
      throw WorkController.bad("unknown journalType " + journalType);
    }
    if (publicationStatus != null && !STATUSES.contains(publicationStatus)) {
      throw WorkController.bad("unknown publicationStatus " + publicationStatus);
    }
    String term = q == null || q.isBlank() ? null : q.strip();
    List<SourceItem> items = sources.listPublic(term, tier, journalType, publicationStatus, limit, offset).stream()
        .map(this::item).toList();
    return new SourcePage(sources.countPublic(term, tier, journalType, publicationStatus), limit, offset, items);
  }

  @GetMapping("/api/sources/{id}")
  public SourceDetail get(@PathVariable long id) {
    Source s = sources.get(id);
    if (s == null || !Boolean.TRUE.equals(s.active()) || !PUBLIC_TIERS.contains(s.tier())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no source " + id);
    }
    WorkFilter f = WorkFilter.builder().source(id).build();
    return new SourceDetail(s.id(), s.title(), s.publisher(), s.issnL(), s.tier(), s.journalType(), s.primarySubfield(),
        nn(s.subfields()), nn(s.taxgroups()), shares(s), nn(s.codes()), s.oaModel(), s.country(), s.nameDensity(),
        s.actRate(), s.worksPerYear(), s.publicationStatus(), s.ceasedReason(), s.lastIssueYear(), ref(sources, s),
        search.yearCounts(f), works.page(f, RECENT, 0, List.of()).results(), identifiers.source(s),
        statistics.sourceLanguages(id));
  }

  /** The continuing journal of a ceased source as a reference; null when none is set or it is not itself public. */
  public static SourceRef ref(SourceMapper sources, Source s) {
    if (s.continuedBy() == null) return null;
    Source c = sources.get(s.continuedBy());
    boolean open = c != null && Boolean.TRUE.equals(c.active()) && PUBLIC_TIERS.contains(c.tier());
    return open ? new SourceRef(c.id(), c.title()) : null;
  }

  private SourceItem item(Source s) {
    return new SourceItem(s.id(), s.title(), s.publisher(), s.tier(), s.journalType(), s.primarySubfield(),
        nn(s.subfields()), nn(s.taxgroups()), nn(s.codes()), s.oaModel(), s.country(), s.nameDensity(), s.actRate(),
        s.worksPerYear(), s.publicationStatus(), s.ceasedReason(), s.lastIssueYear(), ref(sources, s));
  }

  private static List<String> nn(List<String> l) {
    return l == null ? List.of() : l;
  }

  private static JsonNode shares(Source s) {
    if (s.taxgroupShares() == null) {
      return null;
    }
    try {
      return FinderJson.mapper().readTree(s.taxgroupShares());
    } catch (RuntimeException e) {
      LOG.warn("Unreadable taxgroup_shares of source {}: {}", s.id(), e.toString());
      return null;
    }
  }
}
