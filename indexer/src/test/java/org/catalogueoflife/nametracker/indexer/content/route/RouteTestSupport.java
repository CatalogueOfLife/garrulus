package org.catalogueoflife.nametracker.indexer.content.route;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.catalogueoflife.nametracker.store.work.Work;

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
    return new PoliteHttp(HttpClient.newHttpClient(), Map.of("localhost", Duration.ZERO), PoliteHttp.userAgent(),
        Clock.systemUTC(), d -> { });
  }

  public static Work work(Long id, String doi, String pmcid) {
    return new Work(id, null, doi, null, "T", null, null, null, null, null, null, null, null, null, null, null, null,
        null, "LIVE", null, null, null, null, null, null, null, null, pmcid, null, null, null, null);
  }
}
