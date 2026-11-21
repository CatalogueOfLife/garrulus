package org.catalogueoflife.nametracker.indexer.hits;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.match.TaxGroups;
import org.catalogueoflife.nametracker.store.account.Subscription;

/** Which names of a work a subscription matches, by target and options (window, filters and pause are not here). */
public final class SubscriptionMatcher {

  /** {@code names} are the matching names, the content of the hit snapshot. */
  public record Match(boolean matches, List<WorkNameView> names) {}

  private static final Match NO = new Match(false, List.of());

  private SubscriptionMatcher() {}

  public static Match match(Subscription s, WorkInfo work, List<WorkNameView> names) {
    if (!s.includeAbstractOnly() && !"FULLTEXT".equals(work.textLevel())) return NO;
    boolean source = "SOURCE".equals(s.type());
    if (source && (work.sourceId() == null || !s.target().equals(work.sourceId().toString()))) return NO;
    List<WorkNameView> hits = names.stream()
        .filter(n -> source || targetMatches(s, n))
        .filter(n -> s.includeReferences() || !n.onlyReference())
        // like the search, a part never counts for a name that only occurs in references
        .filter(n -> s.mentionedIn().isEmpty() || (!n.onlyReference() && n.mentionedIn(s.mentionedIn())))
        .filter(n -> !s.mainSubjectOnly() || n.mainSubject())
        .filter(n -> !s.newNamesOnly() || n.actTypes().length > 0)
        .toList();
    if (source && !s.mainSubjectOnly() && !s.newNamesOnly() && s.mentionedIn().isEmpty()) return new Match(true, hits);
    return hits.isEmpty() ? NO : new Match(true, hits);
  }

  private static boolean targetMatches(Subscription s, WorkNameView n) {
    return switch (s.type()) {
      case "TAXON" -> Arrays.asList(n.taxonPath()).contains(s.target());
      case "NAME" -> s.target().equals(String.valueOf(n.nameStringId()));
      case "TAXGROUP" -> n.taxgroup() != null && TaxGroups.withAncestors(n.taxgroup()).contains(s.target());
      default -> false;
    };
  }
}
