package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.eval.corpus.GoldDocument;
import org.catalogueoflife.nametracker.finder.api.ActType;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.finder.api.RankedName;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.gbif.nameparser.api.Rank;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inference of missing act types, act links and context ranks in the Label Studio importer. */
class LabelStudioInferenceTest {
  @TempDir Path dir;

  private static final String LONG_GAP = "x".repeat(90);
  private static final List<String> TEXTS = List.of(
      "Ophelia yangi (Smith, 1900) sp. nov. from China",   // b1
      "Carabus auratus L. and Carabus nigrus Fab. comb. nov.", // b2
      "Aus bus " + LONG_GAP + " gen. nov.",                // b3
      "class: Insecta; order: Hemiptera; family: Aphididae", // b4
      "Nitrospinaceae: Opheliidae, Zzzzzzz",               // b5
      "Aphididae: Hemiptera",                              // b6
      "Aus bus sp. nov. and cl. n. and nom. rest.",        // b7
      "Nitrospinaceae: Nitrospina");                       // b8
  private static final Document DOC;
  private static final FlatText FLAT;

  static {
    List<Block> blocks = new ArrayList<>();
    for (int i = 0; i < TEXTS.size(); i++) blocks.add(new Block("b" + (i + 1), BlockType.ABSTRACT, 0, TEXTS.get(i), List.of(), List.of()));
    DOC = new Document("openalex:W2", "en", blocks, Map.of());
    FLAT = FlatText.of(DOC);
  }

  /** A region over the first occurrence of {@code text} (from {@code from}) in block {@code n} (1 based). */
  private static String region(String id, int n, String text, String label) {
    return region(id, n, text, label, 0);
  }

  private static String region(String id, int n, String text, String label, int from) {
    int s = TEXTS.get(n - 1).indexOf(text, from);
    assertThat(s).isGreaterThanOrEqualTo(0);
    int fs = FLAT.toFlat("b" + n, s);
    return "{\"id\":\"" + id + "\",\"from_name\":\"label\",\"to_name\":\"text\",\"type\":\"labels\",\"value\":{\"start\":" + fs
        + ",\"end\":" + (fs + text.length()) + ",\"text\":\"" + text + "\",\"labels\":[\"" + label + "\"]}}";
  }

  private static String choice(String id, String value) {
    return "{\"id\":\"" + id + "\",\"from_name\":\"act_type\",\"to_name\":\"text\",\"type\":\"choices\",\"value\":{\"choices\":[\"" + value + "\"]}}";
  }

  private static String link(String act, String name) {
    return "{\"from_id\":\"" + act + "\",\"to_id\":\"" + name + "\",\"type\":\"relation\",\"labels\":[\"act_of\"]}";
  }

  private static String ranks(String id, String value) {
    return "{\"id\":\"" + id + "\",\"from_name\":\"ranks\",\"to_name\":\"text\",\"type\":\"textarea\",\"value\":{\"text\":[\"" + value + "\"]}}";
  }

  private Path export(String... items) throws Exception {
    ObjectNode d = FinderJson.mapper().createObjectNode();
    d.put("text", FLAT.text());
    d.put("docId", "openalex:W2");
    d.put("split", "test");
    d.put("license", "CC-BY");
    d.put("textRetained", true);
    d.set("document", FinderJson.mapper().valueToTree(DOC));
    String task = "[{\"data\":" + FinderJson.mapper().writeValueAsString(d) + ",\"annotations\":[{\"id\":1,\"was_cancelled\":false,\"result\":["
        + String.join(",", items) + "]}]}]";
    Path p = dir.resolve("export.json");
    Files.writeString(p, task);
    return p;
  }

  private static LabelStudioImporter.Options inferring() {
    return LabelStudioImporter.Options.inferring(null);
  }

  @Test
  void actTypeComesFromTheMarker() {
    assertThat(LabelStudioImporter.inferActType("sp. nov.")).isEqualTo(ActType.SP_NOV);
    assertThat(LabelStudioImporter.inferActType("comb. nov. ")).isEqualTo(ActType.COMB_NOV);
    assertThat(LabelStudioImporter.inferActType("stat. n.")).isEqualTo(ActType.STAT_NOV);
    assertThat(LabelStudioImporter.inferActType("replacement name")).isEqualTo(ActType.NOM_NOV);
    assertThat(LabelStudioImporter.inferActType("cl. n.")).isEqualTo(ActType.HIGHER_NOV);
    assertThat(LabelStudioImporter.inferActType("nom. rest.")).isEqualTo(ActType.STAT_REV);
    for (String m : List.of("subcl. n.", "subphyl. n.", "superphyl. n.", "infraphyl. n.", "subk. n.", "infrak. n.", "parvk. n.", "ord. nov.", "k. nov.")) {
      assertThat(LabelStudioImporter.inferActType(m)).as(m).isEqualTo(ActType.HIGHER_NOV);
    }
    assertThat(LabelStudioImporter.inferActType("holotype")).isNull();
  }

  @Test
  void missingActTypeAndLinkAreInferredAcrossAnAuthorshipGap() throws Exception {
    Path p = export(region("n1", 1, "Ophelia yangi", "Name"), region("a1", 1, "sp. nov.", "Act"));
    LabelStudioImporter.Options o = inferring();
    GoldDocument g = LabelStudioImporter.read(p, o).get(0);
    assertThat(g.acts()).hasSize(1);
    assertThat(g.acts().get(0).type()).isEqualTo(ActType.SP_NOV);
    assertThat(g.acts().get(0).nameText()).isEqualTo("Ophelia yangi");
    assertThat(o.inferred()).extracting(LabelStudioImporter.Inferred::kind).containsExactly("act_type", "act_of");
    assertThat(o.inferred().get(1).gap()).isEqualTo(" (Smith, 1900) ");
  }

  @Test
  void linkGoesToTheNearestNameNotAnEarlierOne() throws Exception {
    // two names before the act, the nearer one wins; no link when the act itself sits between
    Path p = export(region("n1", 2, "Carabus auratus", "Name"), region("n2", 2, "Carabus nigrus", "Name"), region("a1", 2, "comb. nov.", "Act"));
    GoldDocument g = LabelStudioImporter.read(p, inferring()).get(0);
    assertThat(g.acts().get(0).nameText()).isEqualTo("Carabus nigrus");
    assertThat(g.acts().get(0).type()).isEqualTo(ActType.COMB_NOV);
  }

  @Test
  void noLinkWhenAnotherActLiesBetweenOrTheGapIsTooLong() throws Exception {
    // b7: "Aus bus sp. nov. and cl. n. and nom. rest.": the second act has the first act in between
    Path between = export(region("n1", 7, "Aus bus", "Name"), region("a1", 7, "sp. nov.", "Act"), choice("a1", "sp. nov."), link("a1", "n1"),
        region("a2", 7, "cl. n.", "Act"));
    assertThatThrownBy(() -> LabelStudioImporter.read(between, inferring())).hasMessageContaining("not linked");
    Path far = export(region("n1", 3, "Aus bus", "Name"), region("a1", 3, "gen. nov.", "Act"));
    assertThatThrownBy(() -> LabelStudioImporter.read(far, inferring())).hasMessageContaining("not linked");
    // a Name nearer than the one before, but behind the act, does not matter; no name at all fails too
    Path none = export(region("a1", 1, "sp. nov.", "Act"));
    assertThatThrownBy(() -> LabelStudioImporter.read(none, inferring())).hasMessageContaining("not linked");
  }

  @Test
  void anotherNameBetweenBlocksTheLinkOnlyWhenItIsNearer() throws Exception {
    // "Carabus auratus L. and Carabus nigrus Fab. comb. nov.": both names are before the act, only the nearer one qualifies
    Path p = export(region("n1", 2, "Carabus auratus", "Name"), region("a1", 2, "comb. nov.", "Act"));
    // the only name is 40 code points before the act, within 80
    assertThat(LabelStudioImporter.read(p, inferring()).get(0).acts().get(0).nameText()).isEqualTo("Carabus auratus");
  }

  @Test
  void explicitValuesWin() throws Exception {
    Path p = export(region("n1", 1, "Ophelia yangi", "Name"), region("n2", 2, "Carabus auratus", "Name"), region("a1", 1, "sp. nov.", "Act"),
        choice("a1", "gen. nov."), link("a1", "n2"));
    LabelStudioImporter.Options o = inferring();
    GoldDocument g = LabelStudioImporter.read(p, o).get(0);
    assertThat(g.acts().get(0).type()).isEqualTo(ActType.GEN_NOV);
    assertThat(g.acts().get(0).nameText()).isEqualTo("Carabus auratus");
    assertThat(o.inferred()).isEmpty();
    // explicit ranks win over the inference
    Path c = export(region("c1", 4, "class: Insecta; order: Hemiptera; family: Aphididae", "Context"), ranks("c1", "FAMILY: Aphididae"));
    assertThat(LabelStudioImporter.read(c, inferring()).get(0).contexts().get(0).ranks()).containsExactly(new RankedName(Rank.FAMILY, "Aphididae"));
  }

  @Test
  void higherMarkersAndStatusRestored() throws Exception {
    Path p = export(region("n1", 7, "Aus bus", "Name"), region("a1", 7, "nom. rest.", "Act"));
    GoldDocument g = LabelStudioImporter.read(p, inferring()).get(0);
    assertThat(g.acts().get(0).type()).isEqualTo(ActType.STAT_REV);
  }

  @Test
  void contextRankPairsAreParsed() throws Exception {
    Path p = export(region("c1", 4, "class: Insecta; order: Hemiptera; family: Aphididae", "Context"));
    LabelStudioImporter.Options o = inferring();
    assertThat(LabelStudioImporter.read(p, o).get(0).contexts().get(0).ranks()).containsExactly(
        new RankedName(Rank.CLASS, "Insecta"), new RankedName(Rank.ORDER, "Hemiptera"), new RankedName(Rank.FAMILY, "Aphididae"));
    assertThat(o.inferred()).singleElement().satisfies(i -> assertThat(i.kind()).isEqualTo("ranks"));
  }

  @Test
  void contextNamesFallBackToSuffixThenUnranked() throws Exception {
    Path p = export(region("c1", 5, "Nitrospinaceae: Opheliidae, Zzzzzzz", "Context"));
    assertThat(LabelStudioImporter.read(p, inferring()).get(0).contexts().get(0).ranks()).containsExactly(
        new RankedName(Rank.FAMILY, "Nitrospinaceae"), new RankedName(Rank.FAMILY, "Opheliidae"), new RankedName(Rank.UNRANKED, "Zzzzzzz"));
  }

  private static ColDictionary dictionary(Map<String, Rank> uninomials, String... genera) {
    LongOpenHashSet g = new LongOpenHashSet();
    for (String x : genera) g.add(ColDictionary.hash(x));
    Long2ByteOpenHashMap u = new Long2ByteOpenHashMap();
    java.util.List<Rank> ranks = new java.util.ArrayList<>(new java.util.LinkedHashSet<>(uninomials.values()));
    Rank[] table = ranks.isEmpty() ? new Rank[] {Rank.FAMILY} : ranks.toArray(new Rank[0]);
    uninomials.forEach((k, v) -> u.put(ColDictionary.hash(k), (byte) ranks.indexOf(v)));
    return new ColDictionary("t", g, new LongOpenHashSet(), u, table, new LongOpenHashSet(), new LongOpenHashSet());
  }

  @Test
  void genusFromDictionaryBeatsSuffix() throws Exception {
    var dict = dictionary(Map.of(), "Nitrospina");
    Path p = export(region("c1", 8, "Nitrospinaceae: Nitrospina", "Context"));
    assertThat(LabelStudioImporter.read(p, LabelStudioImporter.Options.inferring(dict)).get(0).contexts().get(0).ranks()).containsExactly(
        new RankedName(Rank.FAMILY, "Nitrospinaceae"), new RankedName(Rank.GENUS, "Nitrospina"));
    // without the dictionary the suffix rule wins
    assertThat(LabelStudioImporter.read(p, inferring()).get(0).contexts().get(0).ranks().get(1).rank()).isNotEqualTo(Rank.GENUS);
  }

  @Test
  void dictionaryHelperEncodesTheGivenRank() {
    var dict = dictionary(Map.of("Aphidoidea", Rank.SUPERFAMILY, "Aphididae", Rank.FAMILY));
    assertThat(dict.uninomialRank("Aphidoidea")).contains(Rank.SUPERFAMILY);
    assertThat(dict.uninomialRank("Aphididae")).contains(Rank.FAMILY);
  }

  @Test
  void higherRankBeatsGenusAndTheConflictIsNoted() throws Exception {
    var dict = dictionary(Map.of("Nitrospina", Rank.FAMILY), "Nitrospina");
    Path p = export(region("c1", 8, "Nitrospinaceae: Nitrospina", "Context"));
    LabelStudioImporter.Options o = LabelStudioImporter.Options.inferring(dict);
    assertThat(LabelStudioImporter.read(p, o).get(0).contexts().get(0).ranks().get(1)).isEqualTo(new RankedName(Rank.FAMILY, "Nitrospina"));
    assertThat(o.inferred().get(0).gap()).contains("Nitrospina is also a genus");
  }

  @Test
  void strictFailsAsBefore() throws Exception {
    Path noType = export(region("n1", 1, "Ophelia yangi", "Name"), region("a1", 1, "sp. nov.", "Act"), link("a1", "n1"));
    assertThatThrownBy(() -> LabelStudioImporter.read(noType)).hasMessageContaining("act_type");
    Path noLink = export(region("n1", 1, "Ophelia yangi", "Name"), region("a1", 1, "sp. nov.", "Act"), choice("a1", "sp. nov."));
    assertThatThrownBy(() -> LabelStudioImporter.read(noLink, LabelStudioImporter.Options.STRICT)).hasMessageContaining("act_of");
    Path ctx = export(region("c1", 6, "Aphididae: Hemiptera", "Context"));
    assertThatThrownBy(() -> LabelStudioImporter.read(ctx)).hasMessageContaining("ranks");
  }
}
