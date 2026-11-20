package org.catalogueoflife.nametracker.indexer.registry.discovery;

import java.util.Arrays;
import java.util.List;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.registry.Candidate;
import org.catalogueoflife.nametracker.indexer.registry.CandidateRegistry;
import org.catalogueoflife.nametracker.indexer.registry.DoajClient;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * DOAJ discovery: the journals of a few LCC subject classes (about 600 for the default codes; Biology (General)
 * QH301-705.5 has 482 and is left out), one page of 100 per task, code after code. Position {@code {"code","page"}}.
 * Each journal is offered as a candidate with its LCC codes as {@code DOAJ_LCC} classifications and the signal detail
 * {@code {"doajId","lcc":[…]}}. Codes: {@code nametracker.discovery.doaj.lcc-codes} (comma-separated; blank = built-in).
 */
@Component
public class DoajDiscovery implements DiscoverySource {

  public static final String ORIGIN = "DOAJ";
  static final List<String> CODES = List.of("QL1-991", "QK1-989", "QH1-278.5", "QR1-502", "QH540-549.5", "QE701-760");
  private static final Logger LOG = LoggerFactory.getLogger(DoajDiscovery.class);

  private final DoajClient doaj;
  private final CandidateRegistry registry;
  private final List<String> codes;

  public DoajDiscovery(DoajClient doaj, CandidateRegistry registry,
      @Value("${nametracker.discovery.doaj.lcc-codes:}") String codes) {
    this.doaj = doaj;
    this.registry = registry;
    this.codes = codes.isBlank() ? CODES : Arrays.stream(codes.split(",")).map(String::strip)
        .filter(s -> !s.isEmpty()).toList();
  }

  @Override
  public String origin() {
    return ORIGIN;
  }

  @Override
  public @Nullable String page(JsonNode payload) {
    String code = payload.path("code").asString("");
    int page = Math.max(1, payload.path("page").asInt(1));
    int at = code.isEmpty() ? 0 : codes.indexOf(code);
    if (at < 0) {
      LOG.warn("DOAJ discovery: LCC code {} is no longer configured, task ends", code);
      return null;
    }
    if (codes.isEmpty()) return null;
    code = codes.get(at);
    DoajClient.DoajPage p = doaj.journalsByLcc(code, page);
    Offers offers = new Offers(registry);
    for (DoajClient.DoajJournal j : p.journals()) offers.offer(candidate(j));
    LOG.info("DOAJ discovery {} page {} of {} journals: {}", code, page, p.total(), offers.summary());
    if ((long) page * DoajClient.PAGE_SIZE < p.total() && !p.journals().isEmpty()) return position(code, page + 1);
    return at + 1 < codes.size() ? position(codes.get(at + 1), 1) : null;
  }

  static Candidate candidate(DoajClient.DoajJournal j) {
    ObjectNode detail = FinderJson.mapper().createObjectNode().put("doajId", j.id());
    ArrayNode lcc = detail.putArray("lcc");
    j.lcc().forEach(c -> lcc.add(c.code()));
    return new Candidate(ORIGIN, "", j.issns(), j.title().isBlank() ? null : j.title(), j.publisher(), j.country(), null,
        detail.toString(), j.lcc(), null);
  }

  private static String position(String code, int page) {
    return FinderJson.mapper().createObjectNode().put("origin", ORIGIN).put("code", code).put("page", page).toString();
  }
}
