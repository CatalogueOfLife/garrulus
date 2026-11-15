package org.catalogueoflife.nametracker.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

class HarvestConfigTest {

  @Test
  void hostIntervalOverridesKeepTheirDots() {
    MockEnvironment env = new MockEnvironment().withProperty("nametracker.http.min-interval.api.crossref.org", "2s");
    ConfigurationPropertySources.attach(env); // Spring Boot does this for the real environment
    assertThat(HarvestConfig.intervals(env)).containsEntry("api.crossref.org", Duration.ofSeconds(2))
        .containsEntry("api.openalex.org", Duration.ofMillis(100));
    assertThat(HarvestConfig.intervals(new MockEnvironment())).isEqualTo(PoliteHttp.defaultIntervals());
  }
}
