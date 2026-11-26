package org.catalogueoflife.garrulus.indexer.harvest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.UnknownHostException;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.junit.jupiter.api.Test;

class HarvestOutageTest {

  private static HttpProblem unknownHost(String host) {
    return new HttpProblem(URI.create("https://" + host + "/works"), 0, false, "Unknown host: " + host,
        new UnknownHostException(host));
  }

  @Test
  void unknownApiHostIsAnOutage() {
    for (String host : new String[] {"api.crossref.org", "api.openalex.org", "content.openalex.org",
        "api.checklistbank.org", "www.ebi.ac.uk", "zenodo.org"}) {
      assertThat(HarvestOutage.retryable(unknownHost(host))).as(host).isNotNull();
      assertThat(HarvestOutage.retryable(new RuntimeException("wrapped", unknownHost(host)))).as(host).isNotNull();
    }
    assertThat(HarvestOutage.retryable(new UnknownHostException("api.crossref.org"))).isNotNull();
    // any other host is a configuration error: commit 58fb3d9 keeps it non-retryable
    assertThat(HarvestOutage.retryable(unknownHost("example.org"))).isNull();
    assertThat(HarvestOutage.retryable(new UnknownHostException("example.org"))).isNull();
  }

  @Test
  void otherNonRetryableProblemsStayNull() {
    assertThat(HarvestOutage.retryable(new HttpProblem(URI.create("https://api.crossref.org/works"), 400, false,
        "HTTP 400", null))).isNull();
    assertThat(HarvestOutage.retryable(new HttpProblem(URI.create("https://api.crossref.org/works"), 503, true,
        "HTTP 503", null))).isNotNull();
  }
}
