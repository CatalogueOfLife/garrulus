package org.catalogueoflife.nametracker.indexer.registry.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.registry.SourceSampleMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/** The candidate choice of the nightly sampling, against the database. */
class SampleSchedulerIT extends AbstractPostgresIT {

  static final Instant NOW = Instant.parse("2026-12-01T01:30:00Z");

  @Autowired SourceSampleMapper samples;
  @Autowired CandidateScorer scorer;
  @Autowired TaskQueue queue;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM source_sample");
    jdbc.update("DELETE FROM source_registration");
    jdbc.update("DELETE FROM source_signal");
    jdbc.update("UPDATE source SET continued_by = NULL");
    jdbc.update("DELETE FROM source s WHERE NOT EXISTS (SELECT 1 FROM work w WHERE w.source_id = s.id)");
  }

  private long candidate(String title, Float score) {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, candidate_score) VALUES (?, 'CANDIDATE', "
        + "'DOAJ', ?) RETURNING id", Long.class, title, score);
  }

  private void sample(long id, String ago) {
    jdbc.update("INSERT INTO source_sample (source_id, sampled_at, articles, with_abstract) VALUES (?, now() - ?::interval, 5, 5)",
        id, ago);
  }

  @Test
  void registeredFirstThenUnsampledThenScore() {
    long old = candidate("sampled long ago, high score", 0.9f);
    sample(old, "400 days");
    long recent = candidate("sampled lately", 0.99f);
    sample(recent, "10 days");
    long low = candidate("never sampled, low", 0.1f);
    long high = candidate("never sampled, high", 0.8f);
    long unscored = candidate("never sampled, unscored", null);
    long reg = candidate("registered, scored low", 0.05f);
    jdbc.update("INSERT INTO app_user (email, email_verified) VALUES ('reg@sample.test', now())");
    jdbc.update("INSERT INTO source_registration (user_id, kind, input, source_id) "
        + "SELECT id, 'ISSN', '1234-5679', ? FROM app_user WHERE email = 'reg@sample.test'", reg);
    long ceased = candidate("ceased", 0.99f);
    jdbc.update("UPDATE source SET publication_status = 'CEASED' WHERE id = ?", ceased);
    long inactive = candidate("inactive", 0.99f);
    jdbc.update("UPDATE source SET active = false WHERE id = ?", inactive);
    long core = candidate("core", 0.99f);
    jdbc.update("UPDATE source SET tier = 'CORE' WHERE id = ?", core);

    List<Long> due = samples.dueForSampling(NOW.minus(java.time.Duration.ofDays(365)), 100);

    // registered; then never sampled by score (unscored last); then the one sampled over a year ago
    assertThat(due).containsExactly(reg, high, low, unscored, old);
    jdbc.update("DELETE FROM app_user WHERE email = 'reg@sample.test'");
  }

  @Test
  void atMostTwoHundredAreEnqueued() {
    for (int i = 0; i < 250; i++) candidate("c" + i, i / 1000f);
    MockEnvironment env = new MockEnvironment().withProperty("nametracker.harvest.enabled", "true");
    new SampleScheduler(queue, samples, scorer, Clock.fixed(NOW, ZoneOffset.UTC), env).nightly();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'SAMPLE_SOURCE'", Integer.class))
        .isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE type = 'SAMPLE_SOURCE' AND priority = 2",
        Integer.class)).isEqualTo(200);
  }
}
