package org.catalogueoflife.nametracker.indexer.finder;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.remote.FinderUnavailableException;
import org.catalogueoflife.nametracker.indexer.AbstractPostgresIT;
import org.catalogueoflife.nametracker.store.task.TaskPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The automatic rollout at start: the finder's configHash against the hashes rolled out before. */
@Import(FinderRolloutIT.StubConfig.class)
class FinderRolloutIT extends AbstractPostgresIT {

  static class StubFinder implements NameFinder {
    volatile String hash = "cfg1";
    volatile String dictionaryKey = "dict-1";
    volatile boolean down;

    @Override
    public FinderInfo info() {
      if (down) throw new FinderUnavailableException("finder down");
      return new FinderInfo("stub", "1", hash, dictionaryKey);
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      throw new AssertionError("not called");
    }
  }

  @TestConfiguration
  static class StubConfig {
    @Bean
    StubFinder stubFinder() {
      return new StubFinder();
    }
  }

  @Autowired StubFinder stub;
  @Autowired FinderRollout rollout;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM task");
    jdbc.update("DELETE FROM finder_rollout");
    stub.hash = "cfg1";
    stub.dictionaryKey = "dict-1";
    stub.down = false;
  }

  private List<Map<String, Object>> rows() {
    return jdbc.queryForList("SELECT config_hash, baseline, enqueued_at FROM finder_rollout "
        + "ORDER BY started, config_hash");
  }

  private List<Map<String, Object>> tasks() {
    return jdbc.queryForList("SELECT type, replace(payload::text, ' ', '') AS payload, priority, dedup_key FROM task "
        + "ORDER BY id");
  }

  @Test
  void firstStartIsBaseline() {
    rollout.check();

    assertThat(rows()).hasSize(1).first().satisfies(r -> {
      assertThat(r.get("config_hash")).isEqualTo("cfg1");
      assertThat(r.get("baseline")).isEqualTo(true);
      assertThat(r.get("enqueued_at")).isNull();
    });
    assertThat(tasks()).isEmpty();
  }

  @Test
  void newHashEnqueuesRollout() {
    rollout.check();
    stub.hash = "cfg2";

    rollout.check();

    assertThat(rows()).extracting(r -> r.get("config_hash") + ":" + r.get("baseline"))
        .containsExactlyInAnyOrder("cfg1:true", "cfg2:false");
    assertThat(tasks()).hasSize(1).first().satisfies(t -> {
      assertThat(t.get("type")).isEqualTo(ReprocessNamesHandler.TYPE);
      assertThat(t.get("dedup_key")).isEqualTo("rollout:cfg2");
      assertThat(t.get("priority")).isEqualTo(TaskPriority.REPROCESS.code());
      assertThat((String) t.get("payload")).contains("\"all\":true").contains("\"rollout\":\"cfg2\"");
    });

    // a second start with the same finder enqueues nothing more
    rollout.check();
    assertThat(tasks()).hasSize(1);
    assertThat(rows()).hasSize(2);
  }

  @Test
  void sameHashNothing() {
    rollout.check();
    stub.dictionaryKey = "dict-2"; // a new dictionary is no finder upgrade

    rollout.check();

    assertThat(rows()).hasSize(1);
    assertThat(tasks()).isEmpty();
  }

  @Test
  void unavailableFinderIsSkipped() {
    stub.down = true;

    rollout.check();

    assertThat(rows()).isEmpty();
    assertThat(tasks()).isEmpty();
  }
}
