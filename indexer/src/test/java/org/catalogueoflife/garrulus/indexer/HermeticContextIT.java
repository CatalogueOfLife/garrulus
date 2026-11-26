package org.catalogueoflife.garrulus.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;

/** The default IT context reaches no external service and reads no developer secrets from a local .env. */
class HermeticContextIT extends AbstractPostgresIT {

  @Autowired ConfigurableEnvironment env;

  @Test
  void externalBaseUrlsAreUnroutable() {
    assertThat(env.getProperty("garrulus.openalex.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("garrulus.openalex.content.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("garrulus.crossref.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("garrulus.europepmc.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("garrulus.pmc.base-url")).isEqualTo(UNROUTABLE);
    assertThat(env.getProperty("garrulus.pensoft.base-url")).isEqualTo(UNROUTABLE + "/{slug}");
  }

  @Test
  void noSecretsAndNoDotEnv() {
    assertThat(env.getProperty("garrulus.openalex.api-key")).isEmpty();
    assertThat(env.getProperty("garrulus.crossref.mailto")).isEmpty();
    assertThat(env.getProperty("garrulus.harvest.enabled", Boolean.class)).isFalse();
    assertThat(env.getPropertySources().stream().map(ps -> ps.getName())).noneMatch(n -> n.contains(".env"));
  }
}
