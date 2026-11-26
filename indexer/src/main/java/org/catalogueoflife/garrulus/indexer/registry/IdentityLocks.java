package org.catalogueoflife.garrulus.indexer.registry;

import java.util.List;
import java.util.TreeSet;
import org.catalogueoflife.garrulus.store.registry.IssnLinkMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The advisory locks that serialise every write of a journal's identity (an offer, its completion, a fold): one
 * transaction-level lock per identity key, so two writers that share any ISSN, ISSN-L or OpenAlex id cannot create or
 * keep two sources of one journal.
 */
@Component
public class IdentityLocks {

  private final IssnLinkMapper links;

  public IdentityLocks(IssnLinkMapper links) {
    this.links = links;
  }

  /**
   * Takes the locks of every key (each normalised ISSN, each ISSN-L the table knows, the OpenAlex id) in sorted order.
   * Call inside a transaction; the locks are released at its end. Returns the first ISSN-L the table knows, or null.
   */
  public @Nullable String lock(List<String> issns, @Nullable String openalexId) {
    TreeSet<String> keys = new TreeSet<>();
    String issnL = null;
    for (String issn : issns) {
      keys.add("issn:" + issn);
      String l = links.issnL(issn);
      if (l != null) {
        keys.add("issnl:" + l);
        if (issnL == null) issnL = l;
      }
    }
    if (openalexId != null) keys.add("oa:" + openalexId);
    for (String key : keys) links.lock("registry:" + key);
    return issnL;
  }
}
