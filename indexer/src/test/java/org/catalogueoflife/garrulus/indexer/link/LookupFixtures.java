package org.catalogueoflife.garrulus.indexer.link;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.indexer.TestHttpClients;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.store.ident.IdScheme;

/** The saved answers of 2026-11-19 under {@code /link/} and the targets the lookup tests ask about. */
public final class LookupFixtures {

  public static final String ZOOKEYS = "10.3897/zookeys.1187.113403";
  public static final String SICI = "10.1002/(sici)1097-4644(19990915)74:4<629::aid-jcb12>3.0.co;2-#";

  private LookupFixtures() {
  }

  public static String fixture(String name) throws IOException {
    try (var in = LookupFixtures.class.getResourceAsStream("/link/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  public static PoliteHttp http() {
    return new PoliteHttp(TestHttpClients.production(), Map.of("localhost", Duration.ZERO), PoliteHttp.userAgent(),
        Clock.systemUTC(), d -> { });
  }

  /** A work with the DOI, the identifiers (scheme, value pairs) it has, and the flags the gate looks at. */
  public static LinkTarget work(long id, String doi, boolean hasActs, boolean plaziOrBlrJournal, String... ids) {
    return new LinkTarget(IdScheme.Entity.WORK, id, doi, List.of(), known(doi == null ? null : doi, ids), hasActs, 1L,
        plaziOrBlrJournal);
  }

  public static LinkTarget work(long id, String doi, String... ids) {
    return work(id, doi, true, false, ids);
  }

  /** A journal with its ISSNs (the ISSN-L first) and the identifiers it has. */
  public static LinkTarget source(long id, List<String> issns, String... ids) {
    Map<String, List<String>> known = known(null, ids);
    known.put("ISSN", issns);
    return new LinkTarget(IdScheme.Entity.SOURCE, id, null, issns, known, false, null, false);
  }

  private static Map<String, List<String>> known(String doi, String... ids) {
    Map<String, List<String>> m = new LinkedHashMap<>();
    if (doi != null) m.put("DOI", List.of(doi));
    for (int i = 0; i < ids.length; i += 2) m.computeIfAbsent(ids[i], k -> new java.util.ArrayList<>()).add(ids[i + 1]);
    return m;
  }
}
