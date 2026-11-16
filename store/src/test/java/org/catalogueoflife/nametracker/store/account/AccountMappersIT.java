package org.catalogueoflife.nametracker.store.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.catalogueoflife.nametracker.store.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.index.IndexMapper;
import org.catalogueoflife.nametracker.store.search.SearchMapper;
import org.catalogueoflife.nametracker.store.search.WorkFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountMappersIT extends AbstractPostgresIT {

  @Autowired AccountMapper accounts;
  @Autowired SubscriptionMapper subs;
  @Autowired HitMapper hits;
  @Autowired DigestMapper digests;
  @Autowired EmailTokenMapper tokens;
  @Autowired IndexMapper index;
  @Autowired SearchMapper search;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM work");
  }

  long user(String email) {
    return accounts.insert(new AppUser(null, email, null, null, null, null, null, null, null, null, null, null));
  }

  long sub(long userId, String type, String target) {
    return subs.insert(new Subscription(null, userId, type, target, "label", false, false, true, false, null, null,
        null, false, null));
  }

  long work() {
    return jdbc.queryForObject(
        "INSERT INTO work (doi, origin, state) VALUES (?, 'LIVE', 'MATCHED') RETURNING id", Long.class,
        "10.1/acc" + System.nanoTime());
  }

  @Test
  void insertAppliesDefaults() {
    AppUser u = accounts.findById(user("a@example.org"));
    assertThat(u.timeZone()).isEqualTo("UTC");
    assertThat(u.digestFrequency()).isEqualTo("DAILY");
    assertThat(u.digestWeekday()).isEqualTo(1);
    assertThat(u.digestHour()).isEqualTo(7);
    assertThat(accounts.findByEmail("A@EXAMPLE.ORG").id()).isEqualTo(u.id());
  }

  @Test
  void emailIsUniqueIgnoringCase() {
    user("Foo@example.org");
    assertThatThrownBy(() -> user("foo@EXAMPLE.org")).isInstanceOf(DuplicateKeyException.class);
    // several users without an address are fine
    user(null);
    user(null);
  }

  @Test
  void settingsEmailAndOrcid() {
    long id = user(null);
    accounts.setOrcid(id, "0000-0002-1825-0097");
    assertThat(accounts.findByOrcid("0000-0002-1825-0097").id()).isEqualTo(id);
    accounts.setEmail(id, "x@example.org");
    AppUser u = accounts.findById(id);
    assertThat(u.email()).isEqualTo("x@example.org");
    assertThat(u.emailVerified()).isNotNull();
    assertThat(u.digestSince()).isNotNull();
    Instant since = accounts.findById(id).digestSince();
    accounts.updateSettings(id, "Ann", "Europe/Berlin", "WEEKLY", 3, 9, null);
    u = accounts.findById(id);
    assertThat(u.displayName()).isEqualTo("Ann");
    assertThat(u.timeZone()).isEqualTo("Europe/Berlin");
    assertThat(u.digestFrequency()).isEqualTo("WEEKLY");
    assertThat(u.digestWeekday()).isEqualTo(3);
    assertThat(u.digestHour()).isEqualTo(9);
    assertThat(u.digestSince()).isNotNull().isEqualTo(since);
    Instant later = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    accounts.updateSettings(id, "Ann", "Europe/Berlin", "WEEKLY", 3, 9, later);
    assertThat(accounts.findById(id).digestSince()).isEqualTo(later);
    assertThat(u.lastLogin()).isNull();
    accounts.touchLogin(id);
    assertThat(accounts.findById(id).lastLogin()).isNotNull();
  }

  @Test
  void subscriptionUniquePerUserTypeTarget() {
    long u = user("a@example.org");
    long other = user("b@example.org");
    sub(u, "TAXON", "T1");
    assertThatThrownBy(() -> sub(u, "TAXON", "T1")).isInstanceOf(DuplicateKeyException.class);
    sub(u, "NAME", "T1");
    sub(other, "TAXON", "T1");
    assertThat(subs.countByUser(u)).isEqualTo(2);
    assertThat(subs.listByUser(u)).extracting(Subscription::type).containsExactly("TAXON", "NAME");
  }

  @Test
  void subscriptionUpdateDeleteAndFeedToken() {
    long u = user("a@example.org");
    long other = user("b@example.org");
    long id = sub(u, "TAXON", "T1");
    Subscription s = subs.listByUser(u).get(0);
    assertThat(s.filters()).isEqualTo("{}");
    subs.update(new Subscription(id, u, "TAXON", "T1", "new", true, true, false, true, "{\"code\": \"ZOOLOGICAL\"}",
        null, 2000, true, null));
    s = subs.listByUser(u).get(0);
    assertThat(s.label()).isEqualTo("new");
    assertThat(s.mainSubjectOnly()).isTrue();
    assertThat(s.includeAbstractOnly()).isFalse();
    assertThat(s.filters()).contains("ZOOLOGICAL");
    assertThat(s.sinceYear()).isEqualTo(2000);
    assertThat(s.paused()).isTrue();
    subs.setFeedToken(id, "tok");
    assertThat(subs.byFeedToken("tok").id()).isEqualTo(id);
    assertThat(subs.byFeedToken("nope")).isNull();
    assertThat(subs.delete(id, other)).isZero();
    assertThat(subs.delete(id, u)).isEqualTo(1);
  }

  @Test
  void hitInsertIfAbsentReturnsZeroOnDuplicate() {
    long u = user("a@example.org");
    long s = sub(u, "TAXON", "T1");
    long w = work();
    assertThat(hits.insertIfAbsent(new Hit(null, s, w, "ARTICLE", null, "[{\"name\":\"X y\"}]", null))).isEqualTo(1);
    assertThat(hits.insertIfAbsent(new Hit(null, s, w, "ARTICLE", null, null, null))).isZero();
    assertThat(hits.insertIfAbsent(new Hit(null, s, w, "NEW_NAMES", null, null, null))).isEqualTo(1);
    Hit h = hits.find(s, w, "ARTICLE");
    assertThat(h.names()).contains("X y");
    assertThat(h.digestId()).isNull();
    assertThat(hits.find(s, w, "NEW_NAMES").names()).isEqualTo("[]");
    assertThat(hits.find(s, work(), "ARTICLE")).isNull();
  }

  @Test
  void candidatesFindsTaxonNameSourceAndGroupKeysAndSkipsPaused() {
    long u = user("a@example.org");
    long taxon = sub(u, "TAXON", "T1");
    long name = sub(u, "NAME", "42");
    long source = sub(u, "SOURCE", "7");
    long group = sub(u, "TAXGROUP", "Coleoptera");
    sub(u, "TAXON", "OTHER");
    long paused = sub(u, "TAXON", "T2");
    subs.update(new Subscription(paused, u, "TAXON", "T2", null, false, false, true, false, null, null, null, true,
        null));
    List<Long> found = subs.candidates("7", List.of("T1", "T2"), List.of("42"), List.of("Coleoptera", "Insecta"))
        .stream().map(Subscription::id).toList();
    assertThat(found).containsExactlyInAnyOrder(taxon, name, source, group);
    assertThat(subs.candidates(null, List.of(), null, List.of())).isEmpty();
    assertThat(subs.candidates("7", List.of(), List.of(), List.of())).extracting(Subscription::id)
        .containsExactly(source);
  }

  @Test
  void digestDeliveryMarksAndUnmarks() {
    long u = user("a@example.org");
    long s = sub(u, "TAXON", "T1");
    long w1 = work(), w2 = work();
    hits.insertIfAbsent(new Hit(null, s, w1, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, s, w2, "ARTICLE", null, null, null));
    Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
    List<Hit> due = hits.undeliveredForDigest(u, since, since);
    assertThat(due).hasSize(2);
    assertThat(hits.undeliveredForDigest(u, Instant.now().plus(1, ChronoUnit.HOURS), since)).isEmpty();
    assertThat(hits.undeliveredForDigest(u, since, Instant.now().plus(1, ChronoUnit.HOURS))).isEmpty();

    assertThat(digests.lastSent(u)).isNull();
    long d = digests.insert(new Digest(null, u, null, 2, "SENT", null));
    assertThat(hits.markDelivered(due.stream().map(Hit::id).toList(), d)).isEqualTo(2);
    assertThat(hits.undeliveredForDigest(u, since, since)).isEmpty();
    assertThat(digests.lastSent(u)).isNotNull();
    assertThat(hits.listByUser(u, null, 10, 0)).hasSize(2).allMatch(h -> h.digestId() == d);

    digests.setStatus(d, "FAILED", "smtp down");
    assertThat(digests.lastSent(u)).isNull();
    assertThat(hits.unmark(d)).isEqualTo(2);
    assertThat(hits.undeliveredForDigest(u, since, since)).hasSize(2);
  }

  @Test
  void listByUserAndFeedAreNewestFirstAndScoped() {
    long u = user("a@example.org");
    long other = user("b@example.org");
    long s1 = sub(u, "TAXON", "T1");
    long s2 = sub(u, "TAXON", "T2");
    long so = sub(other, "TAXON", "T1");
    long w1 = work(), w2 = work(), w3 = work();
    hits.insertIfAbsent(new Hit(null, s1, w1, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, s1, w2, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, s2, w3, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, so, w1, "ARTICLE", null, null, null));
    assertThat(hits.listByUser(u, null, 10, 0)).extracting(Hit::workId).containsExactly(w3, w2, w1);
    assertThat(hits.listByUser(u, s1, 10, 0)).extracting(Hit::workId).containsExactly(w2, w1);
    assertThat(hits.listByUser(u, null, 1, 1)).extracting(Hit::workId).containsExactly(w2);
    assertThat(hits.byFeed(s1, 1)).extracting(Hit::workId).containsExactly(w2);
  }

  @Test
  void emailTokenIsConsumedOnce() {
    long u = user("a@example.org");
    tokens.insert(u, "new@example.org", "hash1", Instant.now().plus(1, ChronoUnit.HOURS));
    tokens.insert(u, "old@example.org", "hash2", Instant.now().minus(1, ChronoUnit.HOURS));
    EmailToken t = tokens.consume("hash1");
    assertThat(t.userId()).isEqualTo(u);
    assertThat(t.email()).isEqualTo("new@example.org");
    assertThat(tokens.consume("hash1")).isNull();
    assertThat(tokens.consume("hash2")).isNull();
    assertThat(tokens.consume("unknown")).isNull();
  }

  @Test
  void emailTokenFindValidDoesNotConsume() {
    long u = user("a@example.org");
    tokens.insert(u, "new@example.org", "hash1", Instant.now().plus(1, ChronoUnit.HOURS));
    tokens.insert(u, "old@example.org", "hash2", Instant.now().minus(1, ChronoUnit.HOURS));
    assertThat(tokens.findValid("hash1").userId()).isEqualTo(u);
    assertThat(tokens.findValid("hash1").email()).isEqualTo("new@example.org");
    assertThat(tokens.findValid("hash2")).isNull();
    assertThat(tokens.findValid("unknown")).isNull();
    assertThat(tokens.consume("hash1")).isNotNull();
    assertThat(tokens.findValid("hash1")).isNull();
  }

  void created(long subscriptionId, String created) {
    jdbc.update("UPDATE subscription SET created = ?::timestamptz WHERE id = ?", created, subscriptionId);
  }

  @Test
  void mergeKeepsOlderDuplicateAndMovesHitsWithoutClash() {
    long survivor = user("keep@example.org");
    long loser = user(null);
    long w1 = work(), w2 = work(), w3 = work(), w4 = work();
    // TAXON T1 on both, the survivor's is older; SOURCE 7 on both, the loser's is older; NAME 42 only on the loser
    long keepTaxon = sub(survivor, "TAXON", "T1");
    long dropTaxon = sub(loser, "TAXON", "T1");
    long keepSource = sub(loser, "SOURCE", "7");
    long dropSource = sub(survivor, "SOURCE", "7");
    long name = sub(loser, "NAME", "42");
    long other = sub(user("other@example.org"), "TAXON", "T1");
    created(keepTaxon, "2026-01-01T00:00:00Z");
    created(dropTaxon, "2026-02-01T00:00:00Z");
    created(keepSource, "2026-01-01T00:00:00Z");
    created(dropSource, "2026-02-01T00:00:00Z");
    hits.insertIfAbsent(new Hit(null, keepTaxon, w1, "ARTICLE", null, "[{\"name\":\"kept\"}]", null));
    hits.insertIfAbsent(new Hit(null, dropTaxon, w1, "ARTICLE", null, "[{\"name\":\"clash\"}]", null));
    hits.insertIfAbsent(new Hit(null, dropTaxon, w1, "NEW_NAMES", null, null, null));
    hits.insertIfAbsent(new Hit(null, dropTaxon, w2, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, keepSource, w3, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, dropSource, w3, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, dropSource, w4, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, name, w4, "ARTICLE", null, null, null));
    hits.insertIfAbsent(new Hit(null, other, w1, "ARTICLE", null, null, null));
    long digest = digests.insert(new Digest(null, loser, null, 1, "SENT", null));
    hits.markDelivered(List.of(hits.find(dropTaxon, w2, "ARTICLE").id()), digest);

    subs.merge(loser, survivor);
    assertThat(digests.reassign(loser, survivor)).isEqualTo(1);

    assertThat(subs.countByUser(loser)).isZero();
    assertThat(subs.listByUser(survivor)).extracting(Subscription::id)
        .containsExactlyInAnyOrder(keepTaxon, keepSource, name);
    assertThat(subs.listByUser(survivor)).filteredOn(s -> s.id() == keepSource).singleElement()
        .satisfies(s -> assertThat(s.created()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z")));
    assertThat(hits.find(keepTaxon, w1, "ARTICLE").names()).contains("kept");
    assertThat(hits.find(keepTaxon, w1, "NEW_NAMES")).isNotNull();
    assertThat(hits.find(keepTaxon, w2, "ARTICLE").digestId()).isEqualTo(digest);
    assertThat(hits.find(keepSource, w3, "ARTICLE")).isNotNull();
    assertThat(hits.find(keepSource, w4, "ARTICLE")).isNotNull();
    assertThat(hits.find(name, w4, "ARTICLE")).isNotNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit WHERE subscription_id IN (?, ?)", Integer.class,
        dropTaxon, dropSource)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit", Integer.class)).isEqualTo(7);
    assertThat(hits.find(other, w1, "ARTICLE")).isNotNull();
    assertThat(jdbc.queryForObject("SELECT user_id FROM digest WHERE id = ?", Long.class, digest))
        .isEqualTo(survivor);
  }

  void session(String id, String principal) {
    jdbc.update("INSERT INTO spring_session (primary_id, session_id, creation_time, last_access_time, "
        + "max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, 0, 0, 60, 60, ?)",
        "p-" + id, id, principal);
  }

  @Test
  void deleteSessionsByAccountIdKeepsTheGivenOne() {
    jdbc.update("DELETE FROM spring_session");
    session("s1", "5");
    session("s2", "5");
    session("s3", "50");
    session("s4", null);
    assertThat(accounts.deleteSessions(5, "s1")).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT trim(session_id) FROM spring_session ORDER BY 1", String.class))
        .containsExactly("s1", "s3", "s4");
    assertThat(accounts.deleteSessions(5, null)).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT trim(session_id) FROM spring_session ORDER BY 1", String.class))
        .containsExactly("s3", "s4");
  }

  @Test
  void deleteUserCascadesSubscriptionsHitsDigestsTokens() {
    long u = user("a@example.org");
    long s = sub(u, "TAXON", "T1");
    long w = work();
    hits.insertIfAbsent(new Hit(null, s, w, "ARTICLE", null, null, null));
    long d = digests.insert(new Digest(null, u, null, 1, "SENT", null));
    hits.markDelivered(List.of(hits.find(s, w, "ARTICLE").id()), d);
    tokens.insert(u, "n@example.org", "h", Instant.now().plus(1, ChronoUnit.HOURS));
    long keep = user("keep@example.org");
    sub(keep, "TAXON", "T1");

    assertThat(accounts.delete(u)).isEqualTo(1);
    assertThat(accounts.findById(u)).isNull();
    for (String t : List.of("subscription WHERE user_id", "digest WHERE user_id", "email_token WHERE user_id")) {
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + t + " = ?", Integer.class, u)).as(t).isZero();
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM hit", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE id = ?", Integer.class, w)).isEqualTo(1);
    assertThat(subs.countByUser(keep)).isEqualTo(1);
  }

  @Test
  void firstIndexedAtIsSetOnceAndKept() throws Exception {
    long w = work();
    assertThat(firstIndexed(w)).isNull();
    index.setIndexed(w, new String[] {"ZOOLOGICAL"}, new String[] {}, false);
    Instant first = firstIndexed(w);
    Instant indexedAt = indexedAt(w);
    assertThat(first).isNotNull().isEqualTo(indexedAt);
    Thread.sleep(20);
    index.setIndexed(w, new String[] {"ZOOLOGICAL"}, new String[] {}, true);
    assertThat(firstIndexed(w)).isEqualTo(first);
    assertThat(indexedAt(w)).isAfter(first);
  }

  Instant firstIndexed(long w) {
    return jdbc.queryForObject("SELECT first_indexed_at FROM work WHERE id = ?", Instant.class, w);
  }

  Instant indexedAt(long w) {
    return jdbc.queryForObject("SELECT indexed_at FROM work WHERE id = ?", Instant.class, w);
  }

  @Test
  void workMatchesAppliesFilter() {
    long w = work();
    index.setIndexed(w, new String[] {"ZOOLOGICAL"}, new String[] {}, false);
    WorkFilter all = new WorkFilter(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
        false, null);
    WorkFilter zoo = new WorkFilter(null, null, "ZOOLOGICAL", null, null, null, null, null, null, null, null, null,
        null, null, false, null);
    WorkFilter bot = new WorkFilter(null, null, "BOTANICAL", null, null, null, null, null, null, null, null, null,
        null, null, false, null);
    assertThat(search.workMatches(w, all)).isTrue();
    assertThat(search.workMatches(w, zoo)).isTrue();
    assertThat(search.workMatches(w, bot)).isFalse();
    assertThat(search.workMatches(w + 1000, all)).isFalse();
  }
}
