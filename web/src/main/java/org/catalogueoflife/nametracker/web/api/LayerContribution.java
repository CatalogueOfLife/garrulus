package org.catalogueoflife.nametracker.web.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 28-day contribution of the layers as of the last coverage check, in total, by the journal's country (a journal
 * without one is {@code UNKNOWN}) and by the work's language (undecided is {@code UNDECIDED}); built from the rows of {@code LayerHealthMapper.latestContribution}.
 */
record LayerContribution(Map<String, Map<String, Long>> total,
    Map<String, Map<String, Map<String, Long>>> byCountry,
    Map<String, Map<String, Map<String, Long>>> byLanguage) {

  static LayerContribution of(List<Map<String, Object>> rows, List<Map<String, Object>> languageRows) {
    Map<String, Map<String, Long>> total = new LinkedHashMap<>();
    Map<String, Map<String, Map<String, Long>>> byCountry = new LinkedHashMap<>();
    for (Map<String, Object> r : rows) {
      String layer = (String) r.get("layer");
      String country = "".equals(r.get("country")) ? "UNKNOWN" : (String) r.get("country");
      long first = ((Number) r.get("first_found")).longValue();
      long only = ((Number) r.get("only_found")).longValue();
      Map<String, Long> t = total.computeIfAbsent(layer, k -> new LinkedHashMap<>());
      t.merge("first", first, Long::sum);
      t.merge("only", only, Long::sum);
      Map<String, Long> c = byCountry.computeIfAbsent(country, k -> new LinkedHashMap<>())
          .computeIfAbsent(layer, k -> new LinkedHashMap<>());
      c.merge("first", first, Long::sum);
      c.merge("only", only, Long::sum);
    }
    Map<String, Map<String, Map<String, Long>>> byLanguage = new LinkedHashMap<>();
    for (Map<String, Object> r : languageRows) {
      String language = "".equals(r.get("language")) ? "UNDECIDED" : (String) r.get("language");
      Map<String, Long> c = byLanguage.computeIfAbsent(language, k -> new LinkedHashMap<>())
          .computeIfAbsent((String) r.get("layer"), k -> new LinkedHashMap<>());
      c.merge("first", ((Number) r.get("first_found")).longValue(), Long::sum);
      c.merge("only", ((Number) r.get("only_found")).longValue(), Long::sum);
    }
    return new LayerContribution(total, byCountry, byLanguage);
  }
}
