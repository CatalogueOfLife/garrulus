package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.ClassificationContext;
import org.catalogueoflife.garrulus.finder.api.ContextScope;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.api.RankedName;
import org.catalogueoflife.garrulus.finder.dict.ColDictionary;
import org.catalogueoflife.garrulus.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.garrulus.finder.dict.CommonWords;
import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContextExtractorTest {
  private static final String TSV = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
      + "1\tgenus\tCarabus\t\t\t\n2\torder\tColeoptera\t\t\t\n3\tfamily\tCarabidae\t\t\t\n4\tgenus\tOphelia\t\t\t\n"
      + "5\tgenus\tMajor\t\t\t\n6\tgenus\tMinor\t\t\t\n";
  private static final ContextExtractor EXTRACTOR;

  static {
    try {
      ColDictionary d = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(TSV), "1", CommonWords.of(Set.of("major", "minor")));
      EXTRACTOR = new ContextExtractor(d);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static Block block(String id, BlockType t, int level, String text) {
    return new Block(id, t, level, text, List.of(), List.of());
  }

  private static Document doc(Block... bs) {
    return new Document("d", "en", List.of(bs), Map.of());
  }

  private static ParsedCandidate pc(Block b, String name, NameKind kind, Rank rank) {
    int s = b.text().indexOf(name);
    return new ParsedCandidate(new MergedCandidate(b.id(), s, s + name.length(), name, name, Set.of(Generator.GNFINDER),
        Map.of(), null, null), name, kind, rank, null);
  }

  @Test
  void titleChainIsDocumentContext() {
    Block t = block("t", BlockType.TITLE, 0, "A new species of Carabus (Coleoptera: Carabidae) from China");
    Block p = block("p", BlockType.PARAGRAPH, 0, "Carabus nigrus is described.");
    ParsedCandidate n = pc(p, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    var r = EXTRACTOR.extract(doc(t, p), List.of(n));
    assertThat(r.contexts()).hasSize(1);
    ClassificationContext c = r.contexts().get(0);
    assertThat(c.scope()).isEqualTo(ContextScope.DOCUMENT);
    assertThat(c.verbatim()).isEqualTo("Coleoptera: Carabidae");
    assertThat(c.ranks()).containsExactly(new RankedName(Rank.ORDER, "Coleoptera"), new RankedName(Rank.FAMILY, "Carabidae"));
    assertThat(c.blockId()).isEqualTo("t");
    assertThat(c.untilBlockId()).isNull();
    assertThat(r.contextOf()).containsEntry(n, c.id());
  }

  @Test
  void rankHeadingOpensSection() {
    Block h1 = block("h1", BlockType.HEADING, 1, "Family Opheliidae Malmgren, 1867");
    Block p = block("p", BlockType.PARAGRAPH, 0, "Ophelia is common.");
    Block h2 = block("h2", BlockType.HEADING, 1, "Family Other");
    Block p2 = block("p2", BlockType.PARAGRAPH, 0, "Ophelia again.");
    ParsedCandidate n1 = pc(p, "Ophelia", NameKind.UNINOMIAL, Rank.GENUS);
    ParsedCandidate n2 = pc(p2, "Ophelia", NameKind.UNINOMIAL, Rank.GENUS);
    var r = EXTRACTOR.extract(doc(h1, p, h2, p2), List.of(n1, n2));
    ClassificationContext c = r.contexts().get(0);
    assertThat(c.scope()).isEqualTo(ContextScope.SECTION);
    assertThat(c.ranks()).containsExactly(new RankedName(Rank.FAMILY, "Opheliidae"));
    assertThat(c.verbatim()).isEqualTo("Family Opheliidae Malmgren, 1867");
    assertThat(c.untilBlockId()).isEqualTo("h2");
    assertThat(r.contextOf()).containsEntry(n1, c.id());
    assertThat(r.contextOf()).doesNotContainKey(n2);
  }

  @Test
  void invalidChainRejected() {
    Block t = block("t", BlockType.ABSTRACT, 0, "We report findings (Results: Discussion) here");
    assertThat(EXTRACTOR.extract(doc(t), List.of()).contexts()).isEmpty();
  }

  @Test
  void chainOfOnlyCommonWordsRejected() {
    Block t = block("t", BlockType.TITLE, 0, "Sizes (Major: Minor) compared");
    assertThat(EXTRACTOR.extract(doc(t), List.of()).contexts()).isEmpty();
  }

  @Test
  void germanRankWord() {
    Block h = block("h", BlockType.HEADING, 2, "FAMILIE Carabidae");
    var r = EXTRACTOR.extract(doc(h), List.of());
    assertThat(r.contexts()).hasSize(1);
    assertThat(r.contexts().get(0).ranks()).containsExactly(new RankedName(Rank.FAMILY, "Carabidae"));
    assertThat(RankWords.rank("Familie")).contains(Rank.FAMILY);
    assertThat(RankWords.rank("Überfamilie")).contains(Rank.SUPERFAMILY);
    assertThat(RankWords.rank("Results")).isEmpty();
  }

  @Test
  void rankWordWithoutKnownNameIsNoContext() {
    Block h = block("h", BlockType.HEADING, 1, "Order Matters");
    assertThat(EXTRACTOR.extract(doc(h), List.of()).contexts()).isEmpty();
  }

  @Test
  void blockInitialChain() {
    Block a = block("a", BlockType.ABSTRACT, 0, "Coleoptera: Carabidae. We describe Carabus nigrus.");
    ParsedCandidate n = pc(a, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    var r = EXTRACTOR.extract(doc(a), List.of(n));
    assertThat(r.contexts()).hasSize(1);
    assertThat(r.contexts().get(0).scope()).isEqualTo(ContextScope.DOCUMENT);
    assertThat(r.contexts().get(0).verbatim()).isEqualTo("Coleoptera: Carabidae");
    assertThat(r.contextOf()).containsKey(n);
  }

  @Test
  void treatmentHeadingOpensTreatmentContext() {
    Block h0 = block("h0", BlockType.HEADING, 1, "Taxonomy");
    Block h = block("h", BlockType.HEADING, 3, "Carabus nigrus sp. nov.");
    Block sub = block("sub", BlockType.HEADING, 3, "Material examined");
    Block p = block("p", BlockType.PARAGRAPH, 0, "Holotype of Carabus nigrus.");
    Block h2 = block("h2", BlockType.HEADING, 2, "Discussion");
    Block p2 = block("p2", BlockType.PARAGRAPH, 0, "Carabus nigrus again.");
    ParsedCandidate head = pc(h, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    ParsedCandidate n1 = pc(p, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    ParsedCandidate n2 = pc(p2, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    var r = EXTRACTOR.extract(doc(h0, h, sub, p, h2, p2), List.of(head, n1, n2));
    assertThat(r.contexts()).hasSize(1);
    ClassificationContext c = r.contexts().get(0);
    assertThat(c.scope()).isEqualTo(ContextScope.TREATMENT);
    assertThat(c.verbatim()).isEqualTo("Carabus nigrus");
    assertThat(c.ranks()).containsExactly(new RankedName(Rank.SPECIES, "Carabus nigrus"));
    assertThat(c.blockId()).isEqualTo("h");
    assertThat(c.untilBlockId()).isEqualTo("sub"); // same level heading ends the scope
    assertThat(r.contextOf()).containsEntry(head, c.id()).doesNotContainKey(n1).doesNotContainKey(n2);
  }

  @Test
  void higherRankedHeadingNameIsNoTreatment() {
    Block h = block("h", BlockType.HEADING, 1, "Carabidae");
    ParsedCandidate n = pc(h, "Carabidae", NameKind.UNINOMIAL, Rank.FAMILY);
    assertThat(EXTRACTOR.extract(doc(h), List.of(n)).contexts()).isEmpty();
  }

  @Test
  void innermostContextWins() {
    Block t = block("t", BlockType.TITLE, 0, "Beetles (Coleoptera: Carabidae) of Asia");
    Block h1 = block("h1", BlockType.HEADING, 1, "Family Carabidae");
    Block h2 = block("h2", BlockType.HEADING, 2, "Genus Carabus");
    Block p = block("p", BlockType.PARAGRAPH, 0, "Carabus nigrus.");
    Block h3 = block("h3", BlockType.HEADING, 2, "Genus Ophelia");
    Block p3 = block("p3", BlockType.PARAGRAPH, 0, "Ophelia yangi.");
    Block h4 = block("h4", BlockType.HEADING, 1, "Methods");
    Block p4 = block("p4", BlockType.PARAGRAPH, 0, "Carabus again.");
    ParsedCandidate a = pc(p, "Carabus nigrus", NameKind.BINOMIAL, Rank.SPECIES);
    ParsedCandidate b = pc(p3, "Ophelia yangi", NameKind.BINOMIAL, Rank.SPECIES);
    ParsedCandidate c = pc(p4, "Carabus", NameKind.UNINOMIAL, Rank.GENUS);
    var r = EXTRACTOR.extract(doc(t, h1, h2, p, h3, p3, h4, p4), List.of(a, b, c));
    Map<String, ClassificationContext> byBlock = new java.util.HashMap<>();
    r.contexts().forEach(x -> byBlock.put(x.blockId(), x));
    assertThat(r.contexts()).hasSize(4);
    assertThat(byBlock.get("h1").untilBlockId()).isEqualTo("h4");
    assertThat(byBlock.get("h2").untilBlockId()).isEqualTo("h3");
    assertThat(r.contextOf().get(a)).isEqualTo(byBlock.get("h2").id());
    assertThat(r.contextOf().get(b)).isEqualTo(byBlock.get("h3").id());
    assertThat(r.contextOf().get(c)).isEqualTo(byBlock.get("t").id()); // sections ended: document context
  }
}
