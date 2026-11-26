package org.catalogueoflife.garrulus.indexer.openalex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.catalogueoflife.garrulus.store.discipline.RawClassification;
import tools.jackson.databind.JsonNode;

/** The raw classification rows of an OpenAlex source entity: its subfields and its top topics, by topic count. */
public final class OpenAlexClassifications {

  public static final String SUBFIELD = "OPENALEX_SUBFIELD";
  public static final String TOPIC = "OPENALEX_TOPIC";
  public static final int TOP_TOPICS = 10;

  private OpenAlexClassifications() {}

  /** Rows by scheme; shares are the count share of the source's topic counts, rank follows the share. */
  public static Map<String, List<RawClassification>> raw(JsonNode source) {
    Map<String, Long> subfields = new LinkedHashMap<>();
    Map<String, String> subfieldLabels = new LinkedHashMap<>();
    List<JsonNode> topics = new ArrayList<>();
    long total = 0;
    for (JsonNode t : source.path("topics")) {
      long c = t.path("count").asLong(1);
      total += c;
      topics.add(t);
      String sf = bareId(t.path("subfield").path("id").asString(""));
      if (!sf.isEmpty()) {
        subfields.merge(sf, c, Long::sum);
        subfieldLabels.putIfAbsent(sf, t.path("subfield").path("display_name").asString(null));
      }
    }
    List<RawClassification> sfRows = new ArrayList<>();
    List<RawClassification> topicRows = new ArrayList<>();
    if (total > 0) {
      final long sum = total;
      int rank = 0;
      for (Map.Entry<String, Long> e : subfields.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
        sfRows.add(new RawClassification(SUBFIELD, e.getKey(), subfieldLabels.get(e.getKey()),
            (float) e.getValue() / sum, rank++));
      }
      rank = 0;
      for (JsonNode t : topics.stream().sorted(Comparator.comparingLong((JsonNode n) -> n.path("count").asLong(1))
          .reversed()).limit(TOP_TOPICS).toList()) {
        String id = bareId(t.path("id").asString(""));
        if (id.isEmpty()) continue;
        topicRows.add(new RawClassification(TOPIC, id, t.path("display_name").asString(null),
            (float) t.path("count").asLong(1) / sum, rank++));
      }
    }
    return Map.of(SUBFIELD, sfRows, TOPIC, topicRows);
  }

  /** The last path segment: {@code https://openalex.org/subfields/1105} gives {@code 1105}. */
  public static String bareId(String key) {
    String k = key.strip();
    int slash = k.lastIndexOf('/');
    return slash >= 0 ? k.substring(slash + 1) : k;
  }
}
