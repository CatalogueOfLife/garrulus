package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.CorpusStore;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A provisional gold for wiring tests, made from an export that still has errors the annotator has not fixed yet. A lenient
 * read finds the Act regions inference cannot resolve and the Authorship regions that follow no Name; they are removed from a
 * copy of the export together with their choices and relations, and the copy is imported with inference into a corpus
 * directory (gold.jsonl.gz plus the manifest). Never a substitute for the real gold: no number computed on it is a result.
 */
public final class ProvisionalGold {
  private ProvisionalGold() {}

  public record Result(List<LabelStudioImporter.Removed> removed, List<GoldDocument> documents) {}

  public static Result run(Path export, Path outExport, ColDictionary dictionary, Path manifest, Path outGold) {
    LabelStudioImporter.Options lenient = LabelStudioImporter.Options.lenient(dictionary);
    LabelStudioImporter.read(export, lenient);
    List<LabelStudioImporter.Removed> removed = lenient.removed();
    writeWithout(export, outExport, removed);
    List<GoldDocument> docs = LabelStudioImporter.read(outExport, LabelStudioImporter.Options.inferring(dictionary));
    CuratedManifest.read(manifest).check(docs);
    CorpusStore.write(outGold, docs);
    try {
      Path target = outGold.toAbsolutePath().getParent().resolve("manifest.tsv");
      if (!target.equals(manifest.toAbsolutePath())) Files.copy(manifest, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Result(removed, docs);
  }

  /** Copies the export without the removed regions, their choices and textareas (same id) and the relations that touch them. */
  static void writeWithout(Path export, Path outExport, List<LabelStudioImporter.Removed> removed) {
    try {
      JsonNode tasks = FinderJson.mapper().readTree(Files.readString(export));
      for (JsonNode task : tasks) {
        JsonNode ann = LabelStudioImporter.latest(task.path("annotations"));
        if (ann == null) continue;
        String docId = task.path("data").path("docId").asString("");
        Set<String> ids = removed.stream().filter(r -> r.docId().equals(docId)).map(LabelStudioImporter.Removed::regionId)
            .collect(Collectors.toCollection(HashSet::new));
        if (ids.isEmpty()) continue;
        ArrayNode kept = FinderJson.mapper().createArrayNode();
        for (JsonNode r : ann.path("result")) {
          boolean drop = ids.contains(r.path("id").asString(""))
              || ("relation".equals(r.path("type").asString("")) && (ids.contains(r.path("from_id").asString("")) || ids.contains(r.path("to_id").asString(""))));
          if (!drop) kept.add(r);
        }
        ((ObjectNode) ann).set("result", kept);
      }
      if (outExport.getParent() != null) Files.createDirectories(outExport.getParent());
      Files.writeString(outExport, FinderJson.mapper().writeValueAsString(tasks));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
