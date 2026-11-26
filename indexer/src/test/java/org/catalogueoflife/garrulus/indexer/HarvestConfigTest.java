package org.catalogueoflife.garrulus.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.net.URI;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.http.TrustedAuthorities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

class HarvestConfigTest {

  @Test
  void hostIntervalOverridesKeepTheirDots() {
    MockEnvironment env = new MockEnvironment().withProperty("garrulus.http.min-interval.api.crossref.org", "2s");
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
    // PoliteHttp follows redirects itself, checking every hop (PublicHost)
    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
  }

  @Test
  void theMatchBaseUrlHostIsNotThrottled() {
    MockEnvironment env = new MockEnvironment().withProperty("garrulus.clb.match-base-url", "http://clb-box.lan:18100");
    ConfigurationPropertySources.attach(env);
    assertThat(HarvestConfig.intervals(env)).containsEntry("clb-box.lan", Duration.ZERO);
    env.withProperty("garrulus.http.min-interval.clb-box.lan", "50ms");
    assertThat(HarvestConfig.intervals(env)).containsEntry("clb-box.lan", Duration.ofMillis(50));
  }

  @Test
  void configuredServiceAuthoritiesAreTrusted() {
    MockEnvironment env = new MockEnvironment()
        .withProperty("garrulus.clb.match-base-url", "http://clb-box.lan:18100")
        .withProperty("garrulus.pensoft.base-url", "http://127.0.0.1:9/{slug}")
        .withProperty("garrulus.openalex.base-url", "https://api.openalex.org")
        .withProperty("garrulus.public-url", "http://localhost:5173")
        .withProperty("garrulus.finder.gnfinder-url", "http://localhost:8778")
        .withProperty("garrulus.finder.url", "http://localhost:8095")
        .withProperty("garrulus.http.trusted-hosts", "grobid.lan:8070");
    ConfigurationPropertySources.attach(env);
    TrustedAuthorities t = HarvestConfig.trustedAuthorities(env);
    assertThat(t.trusts(URI.create("http://clb-box.lan:18100/dataset"))).isTrue();
    assertThat(t.trusts(URI.create("http://clb-box.lan:8081/"))).isFalse();
    assertThat(t.trusts(URI.create("http://127.0.0.1:9/zookeys"))).isTrue();
    assertThat(t.trusts(URI.create("https://api.openalex.org/works"))).isTrue();
    assertThat(t.trusts(URI.create("http://grobid.lan:8070/"))).isTrue();
    // never the SPA, gnfinder or a finder service: PoliteHttp does not fetch them
    for (String u : new String[] {"http://localhost:5173/", "http://localhost:8778/", "http://localhost:8095/",
        "http://localhost:8081/"}) {
      assertThat(t.trusts(URI.create(u))).as(u).isFalse();
    }
    assertThat(HarvestConfig.trustedAuthorities(new MockEnvironment()).trusts(URI.create("http://localhost:8080/")))
        .isFalse();
  }
}
