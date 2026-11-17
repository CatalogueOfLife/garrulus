package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.Generator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Settings of {@link PipelineNameFinder}, bound from {@code pipeline-defaults.json} (next to this class) plus overrides.
 *
 * <p>Overrides are JSON objects merged into the defaults: objects merge key by key (so one judge weight can be changed
 * alone), everything else, arrays included, replaces the default. Unknown keys are an error.
 *
 * @param judgeWeights {@link ThresholdJudge} weights by feature name
 * @param certainOdds gnfinder oddsLog10 from which a multi-word name bypasses the judge
 * @param maxChunkCodePoints the most code points sent to gnfinder in one request (split at block boundaries)
 * @param actMaxDistance code points allowed between a name and its act marker
 * @param enabledGenerators the candidate generators in use, see {@link PipelineNameFinder} for the markup generator rule
 * @param llmJudgeEnabled must be false: the LLM judge is a documented option that is not implemented
 * @param epoch the generation of the finder code: part of {@link #configHash()}, so bumping it by hand in
 *     {@code pipeline-defaults.json} after a finder-core change makes the indexer reprocess every work
 */
public record PipelineConfig(Map<String, Double> judgeWeights, double judgeBias, double judgeThreshold, double certainOdds,
                             int maxChunkCodePoints, int actMaxDistance, Set<Generator> enabledGenerators,
                             boolean llmJudgeEnabled, int epoch) {
  static final String DEFAULTS = "pipeline-defaults.json";
  private static final String LLM = "LLM judge not implemented: llmJudgeEnabled must be false";
  private static final JsonMapper MAPPER = FinderJson.mapper().rebuild()
      .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
      .build();

  public PipelineConfig {
    if (llmJudgeEnabled) throw new IllegalArgumentException(LLM);
    if (judgeWeights == null) throw new IllegalArgumentException("judgeWeights is required");
    if (maxChunkCodePoints <= 0) throw new IllegalArgumentException("maxChunkCodePoints must be positive");
    if (actMaxDistance < 0) throw new IllegalArgumentException("actMaxDistance must not be negative");
    if (epoch < 0) throw new IllegalArgumentException("epoch must not be negative");
    if (!(judgeThreshold >= 0 && judgeThreshold <= 1)) throw new IllegalArgumentException("judgeThreshold must be in [0, 1]");
    // sorted and enum-ordered, so equal configs serialise (and hash) identically whatever the input order
    judgeWeights = Collections.unmodifiableMap(new TreeMap<>(judgeWeights));
    EnumSet<Generator> g = EnumSet.noneOf(Generator.class);
    if (enabledGenerators != null) g.addAll(enabledGenerators);
    enabledGenerators = Collections.unmodifiableSet(g);
  }

  /** The defaults from {@code pipeline-defaults.json}. */
  public static PipelineConfig defaults() {
    return bind(defaultsTree());
  }

  /** The defaults with the JSON object {@code overridesJson} merged in. */
  public static PipelineConfig load(String overridesJson) {
    return defaults().withOverrides(overridesJson);
  }

  /** This config with the JSON object {@code overridesJson} merged in. */
  public PipelineConfig withOverrides(String overridesJson) {
    JsonNode overrides;
    try {
      overrides = MAPPER.readTree(overridesJson);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("Pipeline config overrides are not valid JSON: " + e.getOriginalMessage(), e);
    }
    if (!(overrides instanceof ObjectNode o)) throw new IllegalArgumentException("Pipeline config overrides must be a JSON object");
    ObjectNode tree = MAPPER.valueToTree(this);
    merge(tree, o);
    return bind(tree);
  }

  /** The canonical JSON: keys sorted at every level, no whitespace. */
  public String toCanonicalJson() {
    return MAPPER.writeValueAsString(sorted(MAPPER.valueToTree(this)));
  }

  /** SHA-256 (lower-case hex) of {@link #toCanonicalJson()}, so it does not depend on key order. */
  public String configHash() {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(toCanonicalJson().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static ObjectNode defaultsTree() {
    try (InputStream in = PipelineConfig.class.getResourceAsStream(DEFAULTS)) {
      if (in == null) throw new IllegalStateException("Missing resource " + DEFAULTS + " next to " + PipelineConfig.class.getName());
      return (ObjectNode) MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static PipelineConfig bind(ObjectNode tree) {
    // checked before binding so the reason is not buried in a Jackson exception, and any non-false value fails
    JsonNode llm = tree.get("llmJudgeEnabled");
    if (llm != null && !(llm.isBoolean() && !llm.booleanValue())) throw new IllegalArgumentException(LLM);
    try {
      return MAPPER.treeToValue(tree, PipelineConfig.class);
    } catch (JacksonException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IllegalArgumentException iae) throw iae;
      throw new IllegalArgumentException("Invalid pipeline config: " + e.getOriginalMessage(), e);
    }
  }

  private static void merge(ObjectNode target, ObjectNode overrides) {
    for (var e : overrides.properties()) {
      JsonNode old = target.get(e.getKey());
      if (old instanceof ObjectNode to && e.getValue() instanceof ObjectNode from) {
        merge(to, from);
      } else {
        target.set(e.getKey(), e.getValue());
      }
    }
  }

  private static JsonNode sorted(JsonNode n) {
    if (n instanceof ObjectNode o) {
      Map<String, JsonNode> byKey = new TreeMap<>();
      for (var e : o.properties()) byKey.put(e.getKey(), e.getValue());
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      byKey.forEach((k, v) -> out.set(k, sorted(v)));
      return out;
    }
    if (n.isArray()) {
      var out = JsonNodeFactory.instance.arrayNode();
      n.forEach(x -> out.add(sorted(x)));
      return out;
    }
    return n;
  }
}
