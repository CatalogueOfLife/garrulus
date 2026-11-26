package org.catalogueoflife.garrulus.indexer.match;

import java.util.List;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.index.IndexMapper;
import org.catalogueoflife.garrulus.store.index.NameMatch;
import org.springframework.stereotype.Component;

/**
 * Matches in an XR release (by default the current one), stored per (name string, hint key, release). A stored match is returned as is, so
 * a name is asked of CLB once per hint set and release. Not transactional: two concurrent misses both ask CLB and the
 * second {@code putMatch} overwrites the first with the same result.
 */
@Component
public class MatchCache {

  private final IndexMapper index;
  private final ClbClient clb;
  private final XrReleaseService xr;

  public MatchCache(IndexMapper index, ClbClient clb, XrReleaseService xr) {
    this.index = index;
    this.clb = clb;
    this.xr = xr;
  }

  public NameMatch match(long nameStringId, MatchQuery q) {
    return match(nameStringId, q, xr.current());
  }

  /** Matches in an explicit release (the cache key includes it), whether or not it is the pinned one. */
  public NameMatch match(long nameStringId, MatchQuery q, int release) {
    String hintKey = q.hintKey();
    NameMatch hit = index.match(nameStringId, hintKey, release);
    if (hit != null) return hit;
    ClbMatch m = clb.match(release, q);
    NameMatch nm = new NameMatch(nameStringId, hintKey, release, m.type(), m.usageId(), m.usageName(), m.usageRank(),
        m.usageStatus(), m.acceptedId(), m.code(), m.group(), m.taxonPath(), classificationJson(m.classification()), null);
    index.putMatch(nm);
    NameMatch stored = index.match(nameStringId, hintKey, release);
    return stored != null ? stored : nm;
  }

  static String classificationJson(List<ClbMatch.Taxon> classification) {
    return FinderJson.mapper().writeValueAsString(classification);
  }
}
