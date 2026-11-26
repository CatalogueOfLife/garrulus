package org.catalogueoflife.garrulus.eval.curated;

import org.catalogueoflife.garrulus.eval.corpus.GoldDocument;
import org.catalogueoflife.garrulus.finder.api.ActType;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Evidence;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.Mention;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.api.RankedName;
import org.catalogueoflife.garrulus.finder.doc.FlatText;
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
    List<PreTagger.PreTag> pre = PreTagger.pretag(DOC, List.of(
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
    assertThat(r0.path("score").asDouble()).as("no judge score: 1.0").isEqualTo(1.0);
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
        .containsExactlyElementsOf(pre.stream().map(PreTagger.PreTag::mention)
            .map(p -> p.blockId() + ":" + p.start() + "-" + p.end() + ":" + p.verbatim()).toList());
    assertThat(g.document()).isEqualTo(DOC);
    assertThat(g.acts()).isEmpty();
  }

  private static String task(String data, String results) {
    return "[{\"data\":" + data + ",\"annotations\":[" + results + "]}]";
  }

  private String data(boolean retained) throws Exception {
    return data(DOC, retained);
  }

  private String data(Document doc, boolean retained) throws Exception {
    ObjectNode d = FinderJson.mapper().createObjectNode();
    d.put("text", FlatText.of(doc).text());
    d.put("docId", "openalex:W1");
    d.put("split", "test");
    d.put("license", retained ? "CC-BY" : "other");
    d.put("textRetained", retained);
    d.set("document", FinderJson.mapper().valueToTree(doc));
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
    String across = FLAT.text().codePoints().skip(30).limit(10).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString().replace("\n", "\\n");
    String ann = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n1", 30, 40, across, "Name") + "]}";
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), ann))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("more than one block");
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

  private String annotation(String... items) {
    return "{\"id\":1,\"was_cancelled\":false,\"result\":[" + String.join(",", items) + "]}";
  }

  @Test
  void utf16OffsetsAreConvertedWhenTheTextOnlyMatchesThatWay() throws Exception {
    // "Carabus" is at code point 10..17 of b2 (flat 45..52); after the emoji (two UTF-16 units) it is at 46..53
    GoldDocument cp = LabelStudioImporter.read(write(task(data(true), annotation(label("n", 45, 52, "Carabus", "Name"))))).get(0);
    GoldDocument u16 = LabelStudioImporter.read(write(task(data(true), annotation(label("n", 46, 53, "Carabus", "Name"))))).get(0);
    assertThat(cp.names()).hasSize(1);
    assertThat(u16.names()).isEqualTo(cp.names());
    assertThat(u16.names().get(0).start()).isEqualTo(10);
    assertThat(u16.names().get(0).end()).isEqualTo(17);
  }

  @Test
  void textMatchingNeitherInterpretationFailsWithDocAndRegionId() throws Exception {
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), annotation(label("reg7", 45, 52, "Carabuz", "Name"))))))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("openalex:W1").hasMessageContaining("reg7")
        .hasMessageContaining("Carabuz");
  }

  @Test
  void severalActOfRelationsOnOneActFail() throws Exception {
    String rel1 = "{\"from_id\":\"a1\",\"to_id\":\"n1\",\"type\":\"relation\",\"labels\":[\"act_of\"]}";
    String rel2 = "{\"from_id\":\"a1\",\"to_id\":\"n2\",\"type\":\"relation\",\"labels\":[\"act_of\"]}";
    String ann = annotation(label("n1", 0, 13, "Ophelia yangi", "Name"), label("n2", 45, 52, "Carabus", "Name"),
        label("a1", 14, 22, "sp. nov.", "Act"), choice("a1", "act_type", "SP_NOV"), rel1, rel2);
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), ann))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("a1").hasMessageContaining("more than one act_of");
  }

  private static String ranks(String id, String value) {
    return "{\"id\":\"" + id + "\",\"from_name\":\"ranks\",\"to_name\":\"text\",\"type\":\"textarea\",\"value\":{\"text\":[" + value + "]}}";
  }

  @Test
  void ranksTextAreaAcceptsLinesEntriesAndEqualsSign() throws Exception {
    String ctx = label("c1", 0, 13, "Ophelia yangi", "Context");
    List<GoldDocument> g = LabelStudioImporter.read(write(task(data(true),
        annotation(ctx, ranks("c1", "\"phylum = Annelida\\nFAMILY: Opheliidae; order: Phyllodocida\"")))));
    assertThat(g.get(0).contexts().get(0).ranks()).containsExactly(new RankedName(Rank.PHYLUM, "Annelida"),
        new RankedName(Rank.FAMILY, "Opheliidae"), new RankedName(Rank.ORDER, "Phyllodocida"));
    // several text entries are joined like several lines
    List<GoldDocument> g2 = LabelStudioImporter.read(write(task(data(true),
        annotation(ctx, ranks("c1", "\"FAMILY: Opheliidae\",\"ORDER: Phyllodocida\"")))));
    assertThat(g2.get(0).contexts().get(0).ranks()).hasSize(2);
  }

  @Test
  void unknownRankFails() throws Exception {
    String ctx = label("c1", 0, 13, "Ophelia yangi", "Context");
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(true), annotation(ctx, ranks("c1", "\"FAMLY: Opheliidae\""))))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("unknown rank").hasMessageContaining("FAMLY");
  }

  // one block: "Ophelia limacina (Rathke, 1843) and Carabus auratus, L. near Rosa canina"
  private static final String TA = "Ophelia limacina (Rathke, 1843) and Carabus auratus, L. near Rosa canina";
  private static final Document AUTH_DOC = new Document("openalex:W1", "en", List.of(
      new Block("b1", BlockType.ABSTRACT, 0, TA, List.of(), List.of())), Map.of("stratum", "BROAD_EN"));

  private static String names() {
    return String.join(",", label("n1", 0, 16, "Ophelia limacina", "Name"), label("n2", 36, 51, "Carabus auratus", "Name"),
        label("n3", 61, 72, "Rosa canina", "Name"));
  }

  @Test
  void authorshipBelongsToTheNameDirectlyBeforeIt() throws Exception {
    String ann = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + names() + ","
        + label("u1", 17, 31, "(Rathke, 1843)", "Authorship") + "," + label("u2", 53, 55, "L.", "Authorship") + "]}";
    GoldDocument g = LabelStudioImporter.read(write(task(data(AUTH_DOC, true), ann))).get(0);
    assertThat(g.names()).extracting(n -> n.text() + "|" + n.authorship())
        .containsExactly("Ophelia limacina|(Rathke, 1843)", "Carabus auratus|L.", "Rosa canina|null");
    // an authorship region is not a name
    assertThat(g.names()).hasSize(3);
  }

  @Test
  void authorshipWithoutANameRightBeforeItFails() throws Exception {
    // "near" lies between "L." and "Rosa canina", and "and" between "(Rathke, 1843)" and "Carabus auratus"
    String stray = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + label("n2", 36, 51, "Carabus auratus", "Name") + ","
        + label("u1", 17, 31, "(Rathke, 1843)", "Authorship") + "]}";
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(AUTH_DOC, true), stray))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("Authorship").hasMessageContaining("(Rathke, 1843)");
  }

  @Test
  void twoAuthorshipsOnOneNameFail() throws Exception {
    String twice = "{\"id\":1,\"was_cancelled\":false,\"result\":[" + names() + ","
        + label("u1", 17, 24, "(Rathke", "Authorship") + "," + label("u2", 17, 31, "(Rathke, 1843)", "Authorship") + "]}";
    assertThatThrownBy(() -> LabelStudioImporter.read(write(task(data(AUTH_DOC, true), twice))))
        .hasMessageContaining("openalex:W1").hasMessageContaining("Ophelia limacina").hasMessageContaining("more than one Authorship");
  }

  @Test
  void preTaggedActsRoundTripWithTypeAndLink() throws Exception {
    Mention yangi = m("a", "b1", 0, 13);
    NameFinder withAct = new NameFinder() {
      @Override public FinderInfo info() { return new FinderInfo("t", "1", "h", null); }
      @Override public FinderResult find(Document doc, FinderOptions opts) {
        return new FinderResult(info(), List.of(yangi),
            List.of(new org.catalogueoflife.garrulus.finder.api.Act("x", ActType.SP_NOV, "sp. nov.", "a", 0.9)), List.of(), List.of(), Map.of());
      }
    };
    PreTagger.PreTags pre = PreTagger.pretagAll(DOC, List.of(withAct));
    assertThat(pre.acts()).hasSize(1);
    assertThat(pre.acts().get(0).start()).isEqualTo(14);
    assertThat(pre.acts().get(0).end()).isEqualTo(22);
    Path tasks = dir.resolve("tasks.json");
    LabelStudioExporter.write(List.of(pick(true)), Map.of("openalex:W1", pre.names()), Map.of("openalex:W1", pre.acts()), tasks);
    GoldDocument g = LabelStudioImporter.read(accepted(tasks)).get(0);
    assertThat(g.acts()).hasSize(1);
    assertThat(g.acts().get(0).type()).isEqualTo(ActType.SP_NOV);
    assertThat(g.acts().get(0).nameText()).isEqualTo("Ophelia yangi");
    assertThat(g.acts().get(0).start()).isEqualTo(14);
  }

  @Test
  void everyActTypeChoiceImportsAsItsType() {
    for (ActType t : ActType.values()) {
      assertThat(LabelStudioImporter.actType(LabelStudioExporter.actChoice(t))).as(t.name()).isEqualTo(t);
    }
  }
}
