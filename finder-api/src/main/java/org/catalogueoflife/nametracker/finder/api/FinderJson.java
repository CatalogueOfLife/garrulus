package org.catalogueoflife.nametracker.finder.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The shared mapper for the finder contract: camelCase, nulls omitted, unknown properties ignored. */
public final class FinderJson {
  private static final JsonMapper MAPPER = JsonMapper.builder()
      .changeDefaultPropertyInclusion(v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private FinderJson() {}

  /** Shared, immutable, thread-safe. */
  public static JsonMapper mapper() {
    return MAPPER;
  }
}
