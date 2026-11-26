package org.catalogueoflife.garrulus.store.discipline;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DisciplineMapper {

  /** By sort order. */
  List<Discipline> all();

  /** Retired code to its replacement; only rows with a replacement. */
  default Map<String, String> replacements() {
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (Map<String, Object> r : replacementRows()) {
      out.put((String) r.get("code"), (String) r.get("replaced_by"));
    }
    return out;
  }

  List<Map<String, Object>> replacementRows();

  /** The crosswalk rows: scheme, external_code, discipline, weight. */
  List<Map<String, Object>> mappings();
}
