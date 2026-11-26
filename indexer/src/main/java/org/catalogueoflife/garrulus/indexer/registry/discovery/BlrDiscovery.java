package org.catalogueoflife.garrulus.indexer.registry.discovery;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.registry.Candidate;
import org.catalogueoflife.garrulus.indexer.registry.CandidateRegistry;
import org.catalogueoflife.garrulus.store.layer.LayerUnmatchedMapper;
import org.catalogueoflife.garrulus.store.layer.UnmatchedIssn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Discovery from the Biodiversity Literature Repository stream: the DOIs the BLR layer found for journals the registry
 * does not know ({@code layer_unmatched}). The journals with at least {@code garrulus.discovery.blr.min-dois} (2)
 * such DOIs first seen in the last 12 months are offered, signal detail {@code {"dois","lastSeen","example"}} where
 * {@code lastSeen} is the newest first sighting (dated evidence of life: a ceased journal revives on it). One task, no
 * external call.
 */
@Component
public class BlrDiscovery implements DiscoverySource {

  public static final String ORIGIN = "BLR";
  static final int MONTHS = 12;
  private static final Logger LOG = LoggerFactory.getLogger(BlrDiscovery.class);

  private final LayerUnmatchedMapper unmatched;
  private final CandidateRegistry registry;
  private final Clock clock;
  private final int minDois;

  public BlrDiscovery(LayerUnmatchedMapper unmatched, CandidateRegistry registry, Clock clock,
      @Value("${garrulus.discovery.blr.min-dois:2}") int minDois) {
    this.unmatched = unmatched;
    this.registry = registry;
    this.clock = clock;
    this.minDois = minDois;
  }

  @Override
  public String origin() {
    return ORIGIN;
  }

  @Override
  public @Nullable String page(JsonNode payload) {
    var since = clock.instant().atOffset(ZoneOffset.UTC).minusMonths(MONTHS).toInstant();
    Offers offers = new Offers(registry);
    for (UnmatchedIssn u : unmatched.byIssn(since)) {
      if (u.dois() < minDois) continue;
      String detail = FinderJson.mapper().createObjectNode().put("dois", u.dois()).put("lastSeen", u.lastSeen().toString())
          .put("example", u.example()).toString();
      offers.offer(new Candidate(ORIGIN, "", List.of(u.issn()), u.containerTitle(), null, null, null, detail, List.of(),
          null));
    }
    LOG.info("BLR discovery: {}", offers.summary());
    return null;
  }
}
