package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
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
 * {@code score} of {@link PreTagger.PreTag} (Label Studio shows it per region and can sort by it).
 */
public final class LabelStudioExporter {
  public static final String MODEL_VERSION = "pretag";
  private static final JsonMapper MAPPER = FinderJson.mapper();

  private LabelStudioExporter() {}

  public static void write(List<CuratedSelector.Pick> picks, Map<String, List<PreTagger.PreTag>> pretags, Path tasksJson) {
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
      for (PreTagger.PreTag tag : pretags.getOrDefault(p.docId(), List.of())) {
        Mention m = tag.mention();
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
    }
    try {
      if (tasksJson.toAbsolutePath().getParent() != null) Files.createDirectories(tasksJson.toAbsolutePath().getParent());
      Files.writeString(tasksJson, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tasks));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
