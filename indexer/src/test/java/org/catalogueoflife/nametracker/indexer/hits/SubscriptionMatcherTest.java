package org.catalogueoflife.nametracker.indexer.hits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.junit.jupiter.api.Test;

class SubscriptionMatcherTest {
  private static final WorkInfo FULL = new WorkInfo(1, 7L, "FULLTEXT");
  private static final WorkInfo ABS = new WorkInfo(1, 7L, "ABSTRACT_ONLY");

  private static Subscription sub(String type, String target, boolean main, boolean newNames, boolean abstractOnly,
      boolean refs) {
    return new Subscription(1L, 1, type, target, null, main, newNames, abstractOnly, refs, "{}", "t", null, false,
        Instant.EPOCH);
  }

  private static Subscription taxon(String t) {
    return sub("TAXON", t, true, false, true, false);
  }

  private static WorkNameView name(long id, String group, boolean main, boolean ref, String... acts) {
    return new WorkNameView(id, "Name " + id, new String[] {"Animalia", "Carabidae"}, group, main, ref, acts, "EXACT", false, false, false, 1);
  }

  private static WorkNameView part(long id, boolean title, boolean abs, boolean heading, int body) {
    return new WorkNameView(id, "Name " + id, new String[] {"Animalia", "Carabidae"}, null, true, false, new String[0],
        "EXACT", title, abs, heading, body);
  }

  private static Subscription withMentioned(String type, String target, boolean main, String... parts) {
    return new Subscription(1L, 1, type, target, null, main, false, true, false, "{}", "t", null, false, Instant.EPOCH,
        "OK", null, null, null, List.of(parts));
  }

  @Test
  void sourceSubscriptionNeverMatchesWorkWithoutSource() {
    var s = sub("SOURCE", "null", false, false, true, false);
    assertThat(SubscriptionMatcher.match(s, new WorkInfo(1, null, "FULLTEXT"), List.of()).matches()).isFalse();
  }

  @Test
  void taxonMatchesDescendantViaPath() {
    var m = SubscriptionMatcher.match(taxon("Carabidae"), FULL, List.of(name(1, null, true, false)));
    assertThat(m.matches()).isTrue();
    assertThat(m.names()).hasSize(1);
    assertThat(SubscriptionMatcher.match(taxon("Staphylinidae"), FULL, List.of(name(1, null, true, false))).matches())
        .isFalse();
  }

  @Test
  void taxonDefaultIgnoresNonMainSubject() {
    assertThat(SubscriptionMatcher.match(taxon("Carabidae"), FULL, List.of(name(1, null, false, false))).matches())
        .isFalse();
    var s = sub("TAXON", "Carabidae", false, false, true, false);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, null, false, false))).matches()).isTrue();
  }

  @Test
  void referenceOnlyNamesNeverMatchByDefault() {
    var s = sub("TAXON", "Carabidae", false, false, true, false);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, null, true, true))).matches()).isFalse();
  }

  @Test
  void includeReferencesMatchesThem() {
    var s = sub("TAXON", "Carabidae", false, false, true, true);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, null, false, true))).matches()).isTrue();
  }

  @Test
  void newNamesOnlyNeedsActTypes() {
    var s = sub("TAXON", "Carabidae", true, true, true, false);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, null, true, false))).matches()).isFalse();
    var m = SubscriptionMatcher.match(s, FULL, List.of(name(1, null, true, false), name(2, null, true, false, "SP_NOV")));
    assertThat(m.matches()).isTrue();
    assertThat(m.names()).extracting(WorkNameView::nameStringId).containsExactly(2L);
  }

  @Test
  void abstractOnlyExcludedWhenOptionFalse() {
    var s = sub("TAXON", "Carabidae", true, false, false, false);
    assertThat(SubscriptionMatcher.match(s, ABS, List.of(name(1, null, true, false))).matches()).isFalse();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, null, true, false))).matches()).isTrue();
    assertThat(SubscriptionMatcher.match(taxon("Carabidae"), ABS, List.of(name(1, null, true, false))).matches())
        .isTrue();
  }

  @Test
  void sourceDefaultMatchesEveryPaper() {
    var s = sub("SOURCE", "7", false, false, true, false);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of()).matches()).isTrue();
    var m = SubscriptionMatcher.match(s, FULL, List.of(name(1, null, false, false), name(2, null, true, true)));
    assertThat(m.matches()).isTrue();
    assertThat(m.names()).extracting(WorkNameView::nameStringId).containsExactly(1L);
    assertThat(SubscriptionMatcher.match(sub("SOURCE", "8", false, false, true, false), FULL, List.of()).matches())
        .isFalse();
    // options apply when set
    var main = sub("SOURCE", "7", true, false, true, false);
    assertThat(SubscriptionMatcher.match(main, FULL, List.of(name(1, null, false, false))).matches()).isFalse();
  }

  @Test
  void nameMatchesByNameStringId() {
    var s = sub("NAME", "42", false, false, true, false);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(42, null, false, false))).matches()).isTrue();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(43, null, false, false))).matches()).isFalse();
  }

  @Test
  void taxgroupMatchesChildGroup() {
    var s = sub("TAXGROUP", "Arthropods", true, false, true, false);
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, "Coleoptera", true, false))).matches()).isTrue();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, "Mammals", true, false))).matches()).isFalse();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(name(1, null, true, false))).matches()).isFalse();
  }

  @Test
  void emptyMentionedInIsUnchanged() {
    var m = SubscriptionMatcher.match(withMentioned("TAXON", "Carabidae", true), FULL, List.of(part(1, false, false, false, 0)));
    assertThat(m.matches()).isTrue();
  }

  @Test
  void titleOnlyFollow() {
    var s = withMentioned("TAXON", "Carabidae", true, "TITLE");
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(part(1, false, true, false, 3))).matches()).isFalse();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(part(1, true, false, false, 0))).matches()).isTrue();
  }

  @Test
  void bodyIncludesHeadings() {
    var s = withMentioned("TAXON", "Carabidae", true, "BODY");
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(part(1, false, false, true, 0))).matches()).isTrue();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(part(1, false, false, false, 0))).matches()).isFalse();
  }

  @Test
  void sourceFollowWithTitleOnlyNeedsATitleName() {
    var s = withMentioned("SOURCE", "7", false, "TITLE");
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(part(1, false, true, false, 1))).matches()).isFalse();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of()).matches()).isFalse();
    assertThat(SubscriptionMatcher.match(s, FULL, List.of(part(1, true, false, false, 0))).matches()).isTrue();
    var any = withMentioned("SOURCE", "7", false);
    assertThat(SubscriptionMatcher.match(any, FULL, List.of()).matches()).isTrue();
  }
}
