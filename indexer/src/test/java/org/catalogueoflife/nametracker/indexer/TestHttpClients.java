package org.catalogueoflife.nametracker.indexer;

import java.net.http.HttpClient;

/**
 * The HTTP client of the tests: the production one ({@link HarvestConfig#httpClient()}, HTTP/1.1, no redirects). The
 * JDK default would ask WireMock for a cleartext HTTP/2 upgrade (h2c) and send every later request of the client over
 * HTTP/2, which production never does; under the load of the full suite those connections were reset now and then
 * ("connection reset", "header parser received no bytes").
 */
public final class TestHttpClients {

  private TestHttpClients() {
  }

  public static HttpClient production() {
    return HarvestConfig.httpClient();
  }
}
