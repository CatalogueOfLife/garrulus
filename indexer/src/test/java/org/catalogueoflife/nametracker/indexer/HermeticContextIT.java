package org.catalogueoflife.nametracker.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;

/** The default IT context reaches no external service and reads no developer secrets from a local .env. */
class HermeticContextIT extends AbstractPostgresIT {

  @Autowired ConfigurableEnvironment env;

  @Test
  void externalBaseUrlsAreUnroutable() {
    assertThat(env.getProperty("nametracker.openalex.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("nametracker.openalex.content.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("nametracker.crossref.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("nametracker.europepmc.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("nametracker.pmc.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("nametracker.pensoft.base-url")).isEqualTo(UNROUTABLE + "/{slug}");
  }

  @Test
  void noSecretsAndNoDotEnv() {
    assertThat(env.getProperty("nametracker.openalex.api-key")).isEmpty();
    assertThat(env.getProperty("nametracker.crossref.mailto")).isEmpty();
    assertThat(env.getProperty("nametracker.harvest.enabled", Boolean.class)).isFalse();
    assertThat(env.getPropertySources().stream().map(ps -> ps.getName())).noneMatch(n -> n.contains(".env"));
  }
}
