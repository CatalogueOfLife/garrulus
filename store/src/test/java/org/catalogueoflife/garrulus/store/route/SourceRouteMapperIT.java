package org.catalogueoflife.garrulus.store.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.catalogueoflife.garrulus.store.AbstractPostgresIT;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.source.SourceMapper;
import org.catalogueoflife.garrulus.store.task.TaskMapper;
import org.catalogueoflife.garrulus.store.task.TaskPriority;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class SourceRouteMapperIT extends AbstractPostgresIT {

  @Autowired SourceMapper sources;
  @Autowired SourceRouteMapper routes;
  @Autowired TaskMapper tasks;

  private long source(String oa) {
    return sources.upsertSeed(new Source(null, oa, "1111-2222", List.of("1111-2222"), "T", "Pub", "host", true, true,
        "CORE", "SEED_TOPIC", "{}", true, List.of("sf"), "sf", null, List.of(), "{}", List.of(), "GOLD", "DE", null,
        null, 50, null, null, null, null, "SEED"));
  }

  private static SourceRoute route(long sourceId, String layer, String config) {
    return new SourceRoute(0L, sourceId, layer, config, "PENDING", "SEED", null, null, null, null, null, null);
  }

  @Test
  void insertAndListByStatus() {
    long s = source("S-route-1");
    long a = routes.insert(route(s, "OAI", "{\"url\":\"https://a\"}"));
    long b = routes.insert(route(s, "EUROPEPMC", "{\"journal\":\"x\"}"));
    routes.setStatus(b, "ACTIVE", null);
    SourceRoute got = routes.get(a);
    assertThat(got.sourceId()).isEqualTo(s);
    assertThat(got.status()).isEqualTo("PENDING");
    assertThat(got.config()).contains("url");
    assertThat(got.created()).isNotNull();
    assertThat(routes.forSource(s)).extracting(SourceRoute::id).containsExactly(a, b);
    assertThat(routes.listByStatus("OAI", "PENDING", 10)).extracting(SourceRoute::id).contains(a).doesNotContain(b);
    assertThat(routes.listByStatus(null, "ACTIVE", 10)).extracting(SourceRoute::id).contains(b);

    Instant now = Instant.now();
    assertThat(routes.setVerified(a, now)).isEqualTo(1);
    assertThat(routes.markOk(a, now)).isEqualTo(1);
    assertThat(routes.setOnboarded(a, now)).isEqualTo(1);
    assertThat(routes.setBackfilled(a, now)).isEqualTo(1);
    SourceRoute done = routes.get(a);
    assertThat(done.verifiedAt()).isNotNull();
    assertThat(done.lastOk()).isNotNull();
    assertThat(done.onboardedAt()).isNotNull();
    assertThat(done.backfilledAt()).isNotNull();
  }

  @Test
  void setStatusOverwritesError() {
    long id = routes.insert(route(source("S-route-2"), "OAI", "{}"));
    routes.setStatus(id, "BROKEN", "HTTP 500");
    assertThat(routes.get(id).lastError()).isEqualTo("HTTP 500");
    routes.setStatus(id, "ACTIVE", null);
    assertThat(routes.get(id).status()).isEqualTo("ACTIVE");
    assertThat(routes.get(id).lastError()).isNull();
  }

  @Test
  void uniquePerSourceLayerConfig() {
    long s = source("S-route-3");
    routes.insert(route(s, "OAI", "{\"a\": 1}"));
    assertThatThrownBy(() -> routes.insert(route(s, "OAI", "{\"a\":1}"))).isInstanceOf(DuplicateKeyException.class);
    routes.insert(route(s, "OAI", "{\"a\":2}"));
    routes.insert(route(s, "EUROPEPMC", "{\"a\":1}"));
  }

  @Test
  void insertIfAbsentReturnsNullOnConflict() {
    long s = source("S-route-4");
    Long a = routes.insertIfAbsent(route(s, "OAI", "{\"a\": 1}"));
    assertThat(a).isNotNull();
    assertThat(routes.insertIfAbsent(route(s, "OAI", "{\"a\":1}"))).isNull();
    assertThat(routes.forSource(s)).extracting(SourceRoute::id).containsExactly(a);
  }

  @Test
  void rolloutListsVerifiedRoutesOfWantedSourcesWithoutLiveHarvest() {
    long wanted = source("S-route-5");
    long candidate = sources.upsertSeed(new Source(null, "S-route-6", "3333-4444", List.of("3333-4444"), "T", "Pub",
        "host", true, true, "CANDIDATE", "SEED_TOPIC", "{}", true, List.of("sf"), "sf", null, List.of(), "{}",
        List.of(), "GOLD", "DE", null, null, 50, null, null, null, null, "SEED"));
    long ready = routes.insert(route(wanted, "OAI", "{\"r\":1}"));
    long running = routes.insert(route(wanted, "OAI", "{\"r\":2}"));
    long pending = routes.insert(route(wanted, "OAI", "{\"r\":3}"));
    long unwanted = routes.insert(route(candidate, "OAI", "{\"r\":4}"));
    for (long id : List.of(ready, running, unwanted)) routes.setStatus(id, "VERIFIED", null);
    tasks.insert("HARVEST_OAI", "{\"routeId\":" + running + ",\"mode\":\"ONBOARDING\"}", TaskPriority.REPROCESS,
        "rollout-test:" + running, null);
    List<Long> got = routes.listRollout(1000).stream().map(SourceRoute::id).toList();
    assertThat(got).contains(ready).doesNotContain(running, pending, unwanted);
    assertThat(routes.listRollout(1)).hasSize(1);
  }

  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

  @Test
  void rolloutSkipsRoutesWhoseOnboardingDiedSinceTheirVerification() {
    long wanted = source("S-route-7");
    long dead = routes.insert(route(wanted, "OAI", "{\"d\":1}"));
    routes.setStatus(dead, "VERIFIED", null);
    routes.setVerified(dead, java.time.Instant.now().minusSeconds(3600));
    tasks.insert("HARVEST_OAI", "{\"routeId\":" + dead + ",\"mode\":\"ONBOARDING\"}", TaskPriority.REPROCESS,
        "rollout-dead:" + dead, null);
    jdbc.update("UPDATE task SET state = 'DEAD', finished = now() WHERE dedup_key = ?", "rollout-dead:" + dead);
    // a dead pass does not block the routes after it night after night
    assertThat(routes.listRollout(1000)).extracting(SourceRoute::id).doesNotContain(dead);
    // verified again since: rolled out again
    routes.setVerified(dead, java.time.Instant.now().plusSeconds(60));
    assertThat(routes.listRollout(1000)).extracting(SourceRoute::id).contains(dead);
  }

  @Test
  void markOkClearsTheLastError() {
    long s = source("S-route-8");
    long id = routes.insert(route(s, "OAI", "{\"ok\":1}"));
    routes.setError(id, "HTTP 400");
    routes.markOk(id, java.time.Instant.now());
    assertThat(routes.get(id).lastError()).isNull();
    assertThat(routes.get(id).lastOk()).isNotNull();
  }
}
