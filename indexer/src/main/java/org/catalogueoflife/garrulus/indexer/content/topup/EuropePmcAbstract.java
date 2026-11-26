package org.catalogueoflife.garrulus.indexer.content.topup;

import java.util.List;
import java.util.Optional;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.layer.europepmc.EuropePmcSearchClient;
import org.catalogueoflife.garrulus.indexer.layer.magtech.MagtechAbstracts;
import org.catalogueoflife.garrulus.indexer.link.DoiBatching;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.catalogueoflife.garrulus.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The abstract from Europe PMC: {@code EXT_ID:<pmid> AND SRC:MED} when the work has a PMID, else {@code DOI:"<doi>"},
 * {@code resultType=core}, the record's {@code abstractText} (inline HTML, stored as JATS like the Europe PMC layer
 * does). Free and keyless, on by default; {@code garrulus.content.topup.europepmc.enabled=false} switches it off
 * (it shares www.ebi.ac.uk with the Europe PMC layer, route and lookups).
 */
@Component
@Order(10)
public class EuropePmcAbstract implements AbstractTopUp {

  public static final String SERVICE = "EUROPEPMC";

  private final EuropePmcSearchClient client;
  private final WorkIdentifierMapper identifiers;
  private final boolean enabled;

  @Autowired
  public EuropePmcAbstract(PoliteHttp http, WorkIdentifierMapper identifiers,
      @Value("${garrulus.europepmc.base-url:https://www.ebi.ac.uk/europepmc/webservices}") String baseUrl,
      @Value("${garrulus.content.topup.europepmc.enabled:true}") boolean enabled) {
    this.client = new EuropePmcSearchClient(http, baseUrl);
    this.identifiers = identifiers;
    this.enabled = enabled;
  }

  @Override
  public String service() {
    return SERVICE;
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public boolean applies(Work w, @Nullable Source s) {
    return pmid(w) != null || quotableDoi(w) != null;
  }

  private @Nullable String pmid(Work w) {
    return w.id() == null ? null : identifiers.list(w.id()).stream()
        .filter(i -> IdScheme.PMID.code().equals(i.scheme())).map(i -> i.value()).findFirst().orElse(null);
  }

  private static @Nullable String quotableDoi(Work w) {
    return w.doi() != null && !w.doi().isBlank() && DoiBatching.quotable(w.doi()) ? w.doi() : null;
  }

  @Override
  public Optional<TopUpResult> fetch(Work w, @Nullable Source s) {
    String pmid = pmid(w);
    String doi = quotableDoi(w);
    String query = pmid != null ? "EXT_ID:" + pmid + " AND SRC:MED" : "DOI:\"" + doi + "\"";
    List<JsonNode> rows = client.search(query, null).results();
    for (JsonNode r : rows) {
      if (pmid == null && !doi.equalsIgnoreCase(r.path("doi").asString("").strip())) continue;
      String html = r.path("abstractText").asString("").strip();
      if (html.isEmpty()) continue;
      String jats = MagtechAbstracts.toJats(html);
      if (jats.isEmpty()) continue;
      return Optional.of(new TopUpResult(null, jats, null, "EUROPEPMC", List.of(), null));
    }
    return Optional.empty();
  }
}
