package org.catalogueoflife.garrulus.eval.curated;

import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.CodePoints;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.doc.FlatText;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the Label Studio import file: one task per document. {@code data.text} is the flat text of the document (blocks
 * joined by a blank line), so Label Studio offsets are flat code points; {@code data.document} carries the document itself, so
 * the later import does not need anything else. The pre-tags are one prediction of Name regions, each with the
 * {@code score} of {@link PreTagger.PreTag} (Label Studio shows it per region and can sort by it). Pre-tagged acts add an
 * Act region, its act_type choice and an act_of relation to the Name region of their mention.
 */
public final class LabelStudioExporter {
  public static final String MODEL_VERSION = "pretag";
  private static final JsonMapper MAPPER = FinderJson.mapper();

  private LabelStudioExporter() {}

  public static void write(List<CuratedSelector.Pick> picks, Map<String, List<PreTagger.PreTag>> pretags, Path tasksJson) {
    write(picks, pretags, Map.of(), tasksJson);
  }

  /** The act_type choice of the Label Studio config for an act type. */
  static String actChoice(ActType t) {
    return switch (t) {
      case SP_NOV -> "sp. nov.";
      case SSP_NOV -> "ssp. nov.";
      case VAR_NOV -> "var. nov.";
      case GEN_NOV -> "gen. nov.";
      case SUBGEN_NOV -> "subgen. nov.";
      case FAM_NOV -> "fam. nov.";
      case HIGHER_NOV -> "higher nov.";
      case COMB_NOV -> "comb. nov.";
      case STAT_NOV -> "stat. nov.";
      case STAT_REV -> "stat. rev.";
      case SYN_NOV -> "syn. nov.";
      case NOM_NOV -> "nom. nov.";
      case TYPE_DESIGNATION -> "type designation";
    };
  }

  public static void write(List<CuratedSelector.Pick> picks, Map<String, List<PreTagger.PreTag>> pretags,
                           Map<String, List<PreTagger.ActTag>> acts, Path tasksJson) {
    ArrayNode tasks = MAPPER.createArrayNode();
    for (CuratedSelector.Pick p : picks) {
      FlatText flat = FlatText.of(p.document());
      ObjectNode task = tasks.addObject();
      ObjectNode data = task.putObject("data");
      data.put("text", flat.text());
      data.put("docId", p.docId());
      data.put("stratum", p.stratum());
      data.put("split", p.split());
      data.put("language", p.language());
      data.put("license", p.license());
      data.put("textRetained", p.textRetained());
      data.set("document", MAPPER.valueToTree(p.document()));
      ObjectNode prediction = task.putArray("predictions").addObject();
      prediction.put("model_version", MODEL_VERSION);
      ArrayNode result = prediction.putArray("result");
      int n = 0;
      Map<String, String> regionBySpan = new java.util.HashMap<>();
      for (PreTagger.PreTag tag : pretags.getOrDefault(p.docId(), List.of())) {
        Mention m = tag.mention();
        regionBySpan.put(m.blockId() + ":" + m.start() + ":" + m.end(), "r" + (n + 1));
        int start = flat.toFlat(m.blockId(), m.start());
        int end = flat.toFlat(m.blockId(), m.end());
        ObjectNode r = result.addObject();
        r.put("id", "r" + (++n));
        r.put("from_name", "label");
        r.put("to_name", "text");
        r.put("type", "labels");
        ObjectNode v = r.putObject("value");
        v.put("start", start);
        v.put("end", end);
        v.put("text", CodePoints.slice(flat.text(), start, end));
        v.putArray("labels").add("Name");
        r.put("score", tag.score());
      }
      int k = 0;
      for (PreTagger.ActTag a : acts.getOrDefault(p.docId(), List.of())) {
        String nameRegion = regionBySpan.get(a.name().blockId() + ":" + a.name().start() + ":" + a.name().end());
        if (nameRegion == null) continue;
        String id = "a" + (++k);
        int start = flat.toFlat(a.blockId(), a.start());
        int end = flat.toFlat(a.blockId(), a.end());
        String text = CodePoints.slice(flat.text(), start, end);
        ObjectNode r = result.addObject();
        r.put("id", id);
        r.put("from_name", "label");
        r.put("to_name", "text");
        r.put("type", "labels");
        ObjectNode v = r.putObject("value");
        v.put("start", start);
        v.put("end", end);
        v.put("text", text);
        v.putArray("labels").add("Act");
        r.put("score", 1.0);
        ObjectNode c = result.addObject();
        c.put("id", id);
        c.put("from_name", "act_type");
        c.put("to_name", "text");
        c.put("type", "choices");
        ObjectNode cv = c.putObject("value");
        cv.put("start", start);
        cv.put("end", end);
        cv.put("text", text);
        cv.putArray("choices").add(actChoice(a.type()));
        ObjectNode rel = result.addObject();
        rel.put("from_id", id);
        rel.put("to_id", nameRegion);
        rel.put("type", "relation");
        rel.put("direction", "right");
        rel.putArray("labels").add(LabelStudioImporter.ACT_OF);
      }
    }
    try {
      if (tasksJson.toAbsolutePath().getParent() != null) Files.createDirectories(tasksJson.toAbsolutePath().getParent());
      Files.writeString(tasksJson, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tasks));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
