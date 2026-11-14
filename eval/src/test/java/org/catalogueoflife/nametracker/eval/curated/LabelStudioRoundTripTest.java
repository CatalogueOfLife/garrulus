package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelStudioRoundTripTest {
  @TempDir Path dir;

  private static final String T1 = "Ophelia yangi sp. nov. from China";
  private static final String T2 = "Seen in 😀 Carabus auratus and C. auratus.";
  private static final Document DOC = new Document("openalex:W1", "en", List.of(
      new Block("b1", BlockType.TITLE, 0, T1, List.of(), List.of()),
      new Block("b2", BlockType.ABSTRACT, 0, T2, List.of(), List.of()),
      new Block("b3", BlockType.HEADING, 1, "Family Opheliidae", List.of(), List.of())), Map.of("stratum", "BROAD_EN"));
  private static final FlatText FLAT = FlatText.of(DOC);

  private static CuratedSelector.Pick pick(boolean retained) {
    return new CuratedSelector.Pick("openalex:W1", "BROAD_EN", "dev", "en", retained ? "CC-BY" : "other", retained, "10.1/x",
        "https://openalex.org/W1", GoldDocument.textHash(DOC), DOC);
  }

  private static Mention m(String id, String block, int s, int e) {
    String text = block.equals("b1") ? T1 : T2;
    String v = text.codePoints().skip(s).limit(e - s).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    return new Mention(id, block, s, e, v, v, NameKind.BINOMIAL, null, null, null, 0.5,
        new Evidence(Set.of(Generator.ITALIC), null, null), null, null, Map.of());
  }

  private static NameFinder finder(Mention... ms) {
    return new NameFinder() {
      @Override public FinderInfo info() { return new FinderInfo("t", "1", "h", null); }
      @Override public FinderResult find(Document doc, FinderOptions opts) {
        return new FinderResult(info(), List.of(ms), List.of(), List.of(), List.of(), Map.of());
      }
    };
  }

  /** An export where every task's predictions were accepted as the annotation, as a reviewer who agrees with everything. */
  private Path accepted(Path tasks) throws Exception {
    JsonNode root = FinderJson.mapper().readTree(Files.readString(tasks));
    for (JsonNode t : root) {
      ArrayNode anns = ((ObjectNode) t).putArray("annotations");
      ObjectNode a = anns.addObject();
      a.put("id", 7);
      a.put("was_cancelled", false);
      a.set("result", t.path("predictions").get(0).path("result").deepCopy());
    }
    Path out = dir.resolve("export.json");
    Files.writeString(out, FinderJson.mapper().writeValueAsString(root));
    return out;
  }

  @Test
  void exportThenImportOfPredictionsReproducesPretags() throws Exception {
    List<Mention> pre = PreTagger.pretag(DOC, List.of(
        finder(m("a", "b1", 0, 13), m("b", "b2", 10, 17)),
        finder(m("c", "b1", 0, 13), m("d", "b2", 30, 40))));
    assertThat(pre).hasSize(3);
    Path tasks = dir.resolve("tasks.json");
    LabelStudioExporter.write(List.of(pick(true)), Map.of("openalex:W1", pre), tasks);

    JsonNode t = FinderJson.mapper().readTree(Files.readString(tasks)).get(0);
    assertThat(t.path("data").path("text").asString()).isEqualTo(FlatText.of(DOC).text());
    JsonNode r0 = t.path("predictions").get(0).path("result").get(0);
    assertThat(r0.path("from_name").asString()).isEqualTo("label");
    assertThat(r0.path("to_name").asString()).isEqualTo("text");
    assertThat(r0.path("type").asString()).isEqualTo("labels");
    assertThat(r0.path("value").path("labels").get(0).asString()).isEqualTo("Name");
    assertThat(r0.path("value").path("text").asString()).isEqualTo("Ophelia yangi");
    // the second block starts after the blank line (33 + 2); "Carabus" follows "Seen in " and an emoji, which is one code point
    JsonNode r1 = t.path("predictions").get(0).path("result").get(1);
    assertThat(r1.path("value").path("start").asInt()).isEqualTo(T1.length() + 2 + 10);

    List<GoldDocument> gold = LabelStudioImporter.read(accepted(tasks));
    assertThat(gold).hasSize(1);
    GoldDocument g = gold.get(0);
    assertThat(g.corpus()).isEqualTo("curated");
    assertThat(g.split()).isEqualTo("dev");
    assertThat(g.textRetained()).isTrue();
    assertThat(g.textSha256()).isEqualTo(GoldDocument.textHash(DOC));
    assertThat(g.names()).extracting(n -> n.blockId() + ":" + n.start() + "-" + n.end() + ":" + n.text())
        .containsExactlyElementsOf(pre.stream().map(p -> p.blockId() + ":" + p.start() + "-" + p.end() + ":" + p.verbatim()).toList());
    assertThat(g.document()).isEqualTo(DOC);
    assertThat(g.acts()).isEmpty();
  }

  private static String task(String data, String results) {
    return "[{\"data\":" + data + ",\"annotations\":[" + results + "]}]";
  }

  private String data(boolean retained) throws Exception {
    ObjectNode d = FinderJson.mapper().createObjectNode();
    d.put("text", FlatText.of(DOC).text());
    d.put("docId", "openalex:W1");
    d.put("split", "test");
    d.put("license", retained ? "CC-BY" : "other");
    d.put("textRetained", retained);
    d.set("document", FinderJson.mapper().valueToTree(DOC));
    return FinderJson.mapper().writeValueAsString(d);
  }

  private static String label(String id, int s, int e, String text, String label) {
    return "{\"id\":\"" + id + "\",\"from_name\":\"label\",\"to_name\":\"text\",\"type\":\"labels\",\"value\":{\"start\":" + s
        + ",\"end\":" + e + ",\"text\":\"" + text + "\",\"labels\":[\"" + label + "\"]}}";
  }

  private static String choice(String id, String from, String value) {
    return "{\"id\":\"" + id + "\",\"from_name\":\"" + from + "\",\"to_name\":\"text\",\"type\":\"choices\",\"value\":{\"choices\":[\"" + value + "\"]}}";
  }

  private Path write(String json) throws Exception {
    Path p = dir.resolve("hand.json");
    Files.writeString(p, json);
    return p;
  }

  @Test
  void importMapsActRelationAndMainSubject() throws Exception {
    // b1 = "Ophelia yangi sp. nov. from China": name 0-13, act 14-22; b2 starts at flat 35
    String result = String.join(",",
        label("n1", 0, 13, "Ophelia yangi", "Name"),
        label("a1", 14, 22, "sp. nov.", "Act"),
        "{\"from_id\":\"a1\",\"to_id\":\"n1\",\"type\":\"relation\",\"direction\":\"right\",\"labels\":[\"act_of\"]}",
        choice("a1", "act_type", "sp. nov."),
        choice("n1", "main_subject", "yes"),
        label("n2", 35 + 10, 35 + 17, "Carabus", "Name"));
    String ann = "{\"id\":3,\"was_cancelled\":false,\"updated_at\":\"2026-01-02T00:00:00Z\",\"result\":[" + result + "]}"
        + ",{\"id\":2,\"was_cancelled\":false,\"updated_at\":\"2026-01-01T00:00:00Z\",\"result\":[]}"
        + ",{\"id\":4,\"was_cancelled\":true,\"updated_at\":\"2026-01-03T00:00:00Z\",\"result\":[]}";
    List<GoldDocument> gold = LabelStudioImporter.read(write(task(data(true), ann)));
    assertThat(gold).hasSize(1);
    GoldDocument g = gold.get(0);
    assertThat(g.names()).hasSize(2);
    assertThat(g.names().get(0)).satisfies(n -> {
      assertThat(n.blockId()).isEqualTo("b1");
      assertThat(n.start()).isZero();
      assertThat(n.end()).isEqualTo(13);
      assertThat(n.text()).isEqualTo("Ophelia yangi");
    });
    assertThat(g.names().get(1).blockId()).isEqualTo("b2");
    assertThat(g.names().get(1).text()).isEqualTo("Carabus");
    assertThat(g.names().get(1).start()).isEqualTo(10);
    assertThat(g.acts()).hasSize(1);
    assertThat(g.acts().get(0).type()).isEqualTo(ActType.SP_NOV);
    assertThat(g.acts().get(0).nameText()).isEqualTo("Ophelia yangi");
    assertThat(g.acts().get(0).blockId()).isEqualTo("b1");
    assertThat(g.acts().get(0).start()).isEqualTo(14);
    assertThat(g.mainSubjectNames()).containsExactly("Ophelia yangi");
    assertThat(g.split()).isEqualTo("test");
  }

  @Test
  void actWithoutLinkOrTypeFailsWithTheDocumentId() throws Exception {
    String noLink = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n1", 0, 13, "Ophelia yangi", "Name") + ","
        + label("a1", 14, 22, "sp. nov.", "Act") + "," + choice("a1", "act_type", "SP_NOV") + "]}";
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), noLink))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("act_of");
    String noType = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n1", 0, 13, "Ophelia yangi", "Name") + ","
        + label("a1", 14, 22, "sp. nov.", "Act")
        + ",{\"from_id\":\"a1\",\"to_id\":\"n1\",\"type\":\"relation\",\"labels\":[\"act_of\"]}]}";
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), noType)))).hasMessageContaining("act_type");
  }

  @Test
  void contextRegionsGetTheirRanksFromTheTextAreaOrTheHeading() throws Exception {
    String ctx = label("c1", 0, 13, "Ophelia yangi", "Context");
    String area = "{\"id\":\"c1\",\"from_name\":\"ranks\",\"to_name\":\"text\",\"type\":\"textarea\",\"value\":{\"text\":[\"FAMILY: Opheliidae; ORDER: Phyllodocida\"]}}";
    List<GoldDocument> g = LabelStudioImporter.read(write(task(data(true), "{\"id\":1,\"was_cancelled\":false,\"result\":[" + ctx + "," + area + "]}")));
    assertThat(g.get(0).contexts()).hasSize(1);
    assertThat(g.get(0).contexts().get(0).ranks()).containsExactly(
        new RankedName(Rank.FAMILY, "Opheliidae"), new RankedName(Rank.ORDER, "Phyllodocida"));
    // a rank heading carries its own rank
    int h = FLAT.toFlat("b3", 0);
    String heading = label("c1", h, h + 17, "Family Opheliidae", "Context");
    List<GoldDocument> hd = LabelStudioImporter.read(write(task(data(true), "{\"id\":1,\"was_cancelled\":false,\"result\":[" + heading + "]}")));
    assertThat(hd.get(0).contexts()).hasSize(1);
    assertThat(hd.get(0).contexts().get(0).blockId()).isEqualTo("b3");
    assertThat(hd.get(0).contexts().get(0).ranks()).containsExactly(new RankedName(Rank.FAMILY, "Opheliidae"));
  }

  @Test
  void contextWithoutRanksFails() throws Exception {
    String ctx = label("c1", 0, 13, "Ophelia yangi", "Context");
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), "{\"id\":1,\"was_cancelled\":false,\"result\":[" + ctx + "]}"))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("ranks");
  }

  @Test
  void badTextDocumentsAreDropped() throws Exception {
    String ann = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n1", 0, 13, "Ophelia yangi", "Name") + ","
        + choice("t", "doc", "bad_text").replace("\"id\":\"t\",", "") + "]}";
    assertThat(LabelStudioImporter.read(write(task(data(true), ann)))).isEmpty();
    // not yet annotated, or only cancelled: nothing to import
    assertThat(LabelStudioImporter.read(write(task(data(true), "")))).isEmpty();
    assertThat(LabelStudioImporter.read(write(task(data(true), "{\"id\":1,\"was_cancelled\":true,\"result\":[]}")))).isEmpty();
  }

  @Test
  void regionsAcrossBlocksAreRejected() throws Exception {
    String ann = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n1", 30, 40, "x", "Name") + "]}";
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), ann)))).hasMessageContaining("openalex:W1");
  }

  @Test
  void licenceRestrictedDocumentsComeBackWithoutText() throws Exception {
    String ann = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n1", 0, 13, "Ophelia yangi", "Name") + "]}";
    GoldDocument g = LabelStudioImporter.read(write(task(data(false), ann))).get(0);
    assertThat(g.textRetained()).isFalse();
    assertThat(g.document().blocks()).allSatisfy(b -> assertThat(b.text()).isEmpty());
    assertThat(g.textSha256()).isEqualTo(GoldDocument.textHash(DOC));
    assertThat(g.names()).hasSize(1);
  }
}
