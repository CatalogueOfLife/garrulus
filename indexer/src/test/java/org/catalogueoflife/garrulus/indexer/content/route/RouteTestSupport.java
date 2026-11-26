package org.catalogueoflife.garrulus.indexer.content.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.catalogueoflife.garrulus.indexer.TestHttpClients;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.store.work.Work;

/** Shared by the route tests: fixtures from the classpath, a PoliteHttp without waiting, and Work builders. */
public final class RouteTestSupport {

  private RouteTestSupport() {
  }

  public static String fixture(String path) {
    try (var in = RouteTestSupport.class.getResourceAsStream("/" + path)) {
      if (in == null) throw new IllegalArgumentException("No fixture " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static PoliteHttp http() {
    return new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO), PoliteHttp.userAgent(),
        Clock.systemUTC(), d -> { });
  }

  public static Work work(Long id, String doi, String pmcid) {
    return new Work(id, null, doi, null, "T", null, null, null, null, null, null, null, null, null, null, null, null,
        null, "LIVE", null, null, null, null, null, null, null, null, pmcid, null, null, null, null, null);
  }

  /** A work with a DOI, abstract text, publication year and source id. */
  public static Work work(Long id, String doi, String abstractText, Short year, Long sourceId) {
    return new Work(id, null, doi, sourceId, "T", null, year, null, null, null, null, null, null, null, null, null,
        abstractText, abstractText == null ? null : "CROSSREF_JATS", "LIVE", null, null, null, null, null, null, null,
        null, null, null, null, null, null, null);
  }

  /** A source with the id, country and ISSNs. */
  public static org.catalogueoflife.garrulus.store.source.Source source(long id, String country,
      String... issns) {
    return new org.catalogueoflife.garrulus.store.source.Source(id, null, null, java.util.List.of(issns), "J", null,
        null, null, null, "CORE", "MANUAL", null, true, null, null, null, null, null, null, null, country, null, null,
        null, null, null, null, null, null);
  }
}
