package org.catalogueoflife.nametracker.indexer.xr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.match.ClbClient;
import org.catalogueoflife.nametracker.indexer.match.ClbClient.TaxonLookup;
import org.catalogueoflife.nametracker.store.account.SubscriptionMapper;
import org.catalogueoflife.nametracker.store.account.TaxonFollow;
import org.catalogueoflife.nametracker.store.index.XrRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Follows on removed taxa, the first step of {@code XR_FINISH}. Every {@code TAXON} follow whose COL id is not proven
 * to be in the new release (see {@link SubscriptionMapper#taxonFollowsToCheck}) is looked up once per id with
 * {@link ClbClient#taxon}. FOUND: nothing. GONE with a successor: the follow moves to it, keeps its label and options,
 * and gets the note "COL &lt;alias&gt; replaced taxon &lt;old&gt; by &lt;new&gt;" (status MOVED); if its user follows the
 * successor already, it is flagged instead (a user cannot follow one taxon twice). GONE without one: flagged MISSING
 * with "COL &lt;alias&gt; no longer has taxon &lt;old&gt;", keeping its id, so it matches nothing new. A MISSING follow
 * whose taxon is back (a switch back to a release that has it) is OK again, its note cleared. A follow noted since the
 * switch to this release ({@code xr_note_at} at or after {@code switched_at}) is skipped, so a redelivery asks CLB
 * only for what is left. No hit comes from any of this. A CLB outage stops the check ({@code ServiceOutageException});
 * what was written stays. Before the first lookup CLB must serve the release itself ({@code GET /dataset/k}); if it
 * answers 4xx the check is skipped with an ERROR and no follow changes.
 */
@Component
public class FollowCheck {

  private static final Logger LOG = LoggerFactory.getLogger(FollowCheck.class);

  public record Result(int checked, int moved, int missing, int restored) {}

  private final SubscriptionMapper subscriptions;
  private final ClbClient clb;

  public FollowCheck(SubscriptionMapper subscriptions, ClbClient clb) {
    this.subscriptions = subscriptions;
    this.clb = clb;
  }

  public Result run(XrRelease release) {
    if (release.switchedAt() == null) throw new IllegalStateException("XR release " + release.key() + " never switched to");
    Map<String, List<TaxonFollow>> byTarget = new LinkedHashMap<>();
    for (TaxonFollow f : subscriptions.taxonFollowsToCheck(release.key(), release.switchedAt())) {
      byTarget.computeIfAbsent(f.target(), t -> new ArrayList<>()).add(f);
    }
    String col = colName(release.alias());
    if (byTarget.values().stream().anyMatch(fs -> !fs.get(0).present()) && !served(release)) {
      return new Result(0, 0, 0, 0);
    }
    int moved = 0, missing = 0, restored = 0;
    for (Map.Entry<String, List<TaxonFollow>> e : byTarget.entrySet()) {
      String old = e.getKey();
      List<TaxonFollow> follows = e.getValue();
      boolean present = follows.get(0).present();
      TaxonLookup found = present ? null : clb.taxon(release.key(), old);
      if (present || found.status() == TaxonLookup.Status.FOUND) {
        for (TaxonFollow f : follows) {
          if ("MISSING".equals(f.targetStatus())) restored += subscriptions.clearMissing(f.id(), old);
        }
        continue;
      }
      String next = found.supersededBy();
      for (TaxonFollow f : follows) {
        if (next != null && move(f, next, col + " replaced taxon " + old + " by " + next)) {
          moved++;
        } else {
          String note = next == null ? col + " no longer has taxon " + old
              : col + " replaced taxon " + old + " by " + next + ", which you follow already";
          missing += subscriptions.flagMissing(f.id(), old, note);
        }
      }
    }
    Result r = new Result(byTarget.values().stream().mapToInt(List::size).sum(), moved, missing, restored);
    LOG.info("Follow check of XR release {}: {} taxon follows on {} ids not proven present or missing; {} moved, {} "
        + "flagged missing, {} back", release.key(), r.checked(), byTarget.size(), moved, missing, restored);
    return r;
  }

  /**
   * True if CLB serves the release itself, asked once before any follow is looked up: a release CLB no longer serves
   * would answer 404 for every taxon and flag every follow. A 4xx is logged as an ERROR and the check is skipped; an
   * outage is thrown ({@code ServiceOutageException}).
   */
  private boolean served(XrRelease release) {
    try {
      clb.dataset(release.key());
      return true;
    } catch (HttpProblem e) {
      if (e.status() < 400 || e.status() >= 500) throw e;
      LOG.error("Follow check of XR release {} skipped, no follow is changed: CLB does not serve the release ({})",
          release.key(), e.getMessage());
      return false;
    }
  }

  /** False if the user follows the successor already (or the follow changed meanwhile). */
  private boolean move(TaxonFollow f, String next, String note) {
    try {
      return subscriptions.moveTarget(f.id(), f.target(), next, note) > 0;
    } catch (DuplicateKeyException e) { // the user followed the successor just now
      return false;
    }
  }

  /** "COL &lt;alias&gt;", without doubling an alias that starts with COL already ("COL26.10 XR"). */
  static String colName(String alias) {
    return alias.startsWith("COL") ? alias : "COL " + alias;
  }
}
