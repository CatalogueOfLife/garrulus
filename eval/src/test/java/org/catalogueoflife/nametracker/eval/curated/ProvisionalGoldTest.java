package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.CorpusStore;
import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisionalGoldTest {
  @TempDir Path dir;

  private static final String TEXT = "Aus bus sp. nov. gen. nov. from China; Cus dus Smith, 1900 sp. nov. then Eus fus (Jones) Brown";
  private static final Document DOC = new Document("openalex:W9", "en", List.of(new Block("b1", BlockType.ABSTRACT, 0, TEXT, List.of(), List.of())), Map.of());
  private static final FlatText FLAT = FlatText.of(DOC);

  private static String region(String id, String text, String label) {
    int s = FLAT.toFlat("b1", TEXT.indexOf(text));
    return "{\"id\":\"" + id + "\",\"from_name\":\"label\",\"to_name\":\"text\",\"type\":\"labels\",\"value\":{\"start\":" + s + ",\"end\":" + (s + text.length())
        + ",\"text\":\"" + text + "\",\"labels\":[\"" + label + "\"]}}";
  }

  @Test
  void removesUnresolvableActsAndOrphanAuthorshipsAndImportsTheRest() throws Exception {
    ObjectNode d = FinderJson.mapper().createObjectNode();
    d.put("text", FLAT.text());
    d.put("docId", "openalex:W9");
    d.put("split", "dev");
    d.put("license", "CC-BY");
    d.put("textRetained", true);
    d.set("document", FinderJson.mapper().valueToTree(DOC));
    String items = String.join(",",
        region("n1", "Aus bus", "Name"),
        region("a1", "sp. nov.", "Act"),     // resolved: type and link are inferred
        region("a2", "gen. nov.", "Act"),    // unresolvable: a1 lies between it and n1
        "{\"id\":\"a2\",\"from_name\":\"act_type\",\"to_name\":\"text\",\"type\":\"choices\",\"value\":{\"choices\":[\"gen. nov.\"]}}",
        "{\"from_id\":\"a2\",\"to_id\":\"n1\",\"type\":\"relation\",\"labels\":[\"is_other\"]}",
        region("n2", "Cus dus", "Name"),
        region("au1", "Smith, 1900", "Authorship"),
        region("n3", "Eus fus", "Name"),
        region("au2", "Brown", "Authorship"));   // unresolvable: "(Jones) " lies between it and n3
    Path export = dir.resolve("export.json");
    Files.writeString(export, "[{\"data\":" + FinderJson.mapper().writeValueAsString(d)
        + ",\"annotations\":[{\"id\":1,\"was_cancelled\":false,\"result\":[" + items + "]}]}]");
    Path manifest = dir.resolve("manifest.tsv");
    Files.writeString(manifest, CuratedManifest.HEADER + "\n" + String.join("\t", "openalex:W9", "ABSTRACTS", "dev", "en", "CC-BY", "true", "", "", GoldDocument.textHash(DOC)) + "\n");

    Path outGold = dir.resolve("prov/gold.jsonl.gz");
    ProvisionalGold.Result res = ProvisionalGold.run(export, dir.resolve("prov-export.json"), null, manifest, outGold);

    assertThat(res.removed()).extracting(LabelStudioImporter.Removed::regionId).containsExactlyInAnyOrder("a2", "au2");
    assertThat(res.documents()).hasSize(1);
    GoldDocument g = res.documents().get(0);
    assertThat(g.acts()).extracting(a -> a.type()).containsExactly(ActType.SP_NOV);
    assertThat(g.names()).extracting(n -> n.authorship()).containsExactly(null, "Smith, 1900", null);
    assertThat(Files.readString(dir.resolve("prov-export.json"))).doesNotContain("\"gen. nov.\"]").doesNotContain("is_other");
    assertThat(dir.resolve("prov/manifest.tsv")).exists();
    assertThat(CorpusStore.read(outGold, null)).hasSize(1);
  }
}
