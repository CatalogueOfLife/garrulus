package org.catalogueoflife.nametracker.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
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

  /**
   * Europe PMC (www.ebi.ac.uk) closes every HTTP/2 stream of the JDK client with an EOF (seen in the M2 dev run, while
   * HTTP/1.1 answers 200), so the Europe PMC and PMC routes never succeeded. The harvest client speaks HTTP/1.1 only.
   */
  @Test
  void httpClientSpeaksHttp11() {
    HttpClient client = HarvestConfig.httpClient();
    assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
  }
}
