package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.rust.NameParserRust;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ParserStageTest {
  private static final String TSV = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
      + "1\tgenus\tCarabus\t\t\t\n2\tspecies\t\tCarabus\tnigrus\t\n3\tfamily\tCarabidae\t\t\t\n";
  private static final ParserStage STAGE;

  static {
    try {
      ColDictionary d = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(TSV), "1", CommonWords.of(Set.of()));
      STAGE = new ParserStage(new NameParserRust(), d);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static MergedCandidate cand(String name, Generator g, Map<String, Object> features) {
    return new MergedCandidate("b1", 0, name.length(), name, name, Set.of(g), features, null, null);
  }

  private static MergedCandidate cand(String name) {
    return cand(name, Generator.GNFINDER, Map.of());
  }

  private static ParsedCandidate parse(String name) {
    return STAGE.parse(cand(name), null).orElseThrow();
  }

  @Test
  void binomialParsedAsSpecies() {
    ParsedCandidate p = parse("Carabus nigrus");
    assertThat(p.kind()).isEqualTo(NameKind.BINOMIAL);
    assertThat(p.rank()).isEqualTo(Rank.SPECIES);
    assertThat(p.canonical()).isEqualTo("Carabus nigrus");
  }

  @Test
  void trinomialIsInfraspecific() {
    ParsedCandidate p = parse("Carabus nigrus aureus");
    assertThat(p.kind()).isEqualTo(NameKind.TRINOMIAL);
    assertThat(p.canonical()).isEqualTo("Carabus nigrus aureus");
    assertThat(p.rank().isInfraspecific()).isTrue();
  }

  @Test
  void subgenusBinomialIsBinomial() {
    ParsedCandidate p = parse("Carabus (Megodontus) nigrus");
    assertThat(p.kind()).isEqualTo(NameKind.BINOMIAL);
    assertThat(p.rank()).isEqualTo(Rank.SPECIES);
  }

  @Test
  void infragenericWithoutSpecies() {
    ParsedCandidate p = parse("Carabus (Megodontus)");
    assertThat(p.kind()).isEqualTo(NameKind.INFRAGENERIC);
  }

  @Test
  void familyRankFromDictionary() {
    ParsedCandidate p = parse("Carabidae");
    assertThat(p.kind()).isEqualTo(NameKind.UNINOMIAL);
    assertThat(p.rank()).isEqualTo(Rank.FAMILY);
  }

  @Test
  void suffixRankWhenNotInDictionary() {
    assertThat(parse("Fooidae").rank()).isEqualTo(Rank.FAMILY);
  }

  @Test
  void suffixRankUsesCodeHint() {
    ParsedCandidate p = STAGE.parse(cand("Harpalinae"), NomCode.ZOOLOGICAL).orElseThrow();
    assertThat(p.rank()).isEqualTo(Rank.SUBFAMILY);
    assertThat(p.code()).isEqualTo(NomCode.ZOOLOGICAL);
  }

  @Test
  void genusFromDictionary() {
    assertThat(parse("Carabus").rank()).isEqualTo(Rank.GENUS);
  }

  @Test
  void unknownUninomialUnranked() {
    ParsedCandidate p = parse("Zzyzx");
    assertThat(p.kind()).isEqualTo(NameKind.UNINOMIAL);
    assertThat(p.rank()).isEqualTo(Rank.UNRANKED);
  }

  @Test
  void unexpandedAbbreviationIsAbbreviated() {
    ParsedCandidate p = parse("C. nigrus");
    assertThat(p.kind()).isEqualTo(NameKind.ABBREVIATED);
  }

  @Test
  void ambiguousAbbreviationIsAbbreviated() {
    var c = cand("C. nigrus", Generator.GNFINDER, Map.of("abbrevAmbiguous", true));
    assertThat(STAGE.parse(c, null).orElseThrow().kind()).isEqualTo(NameKind.ABBREVIATED);
  }

  @Test
  void expandedAbbreviationParsedFromExpandedName() {
    var c = new MergedCandidate("b1", 0, 9, "C. nigrus", "Carabus nigrus", Set.of(Generator.GNFINDER),
        Map.of("expandedFrom", "C. nigrus"), null, null);
    ParsedCandidate p = STAGE.parse(c, null).orElseThrow();
    assertThat(p.kind()).isEqualTo(NameKind.BINOMIAL);
    assertThat(p.canonical()).isEqualTo("Carabus nigrus");
  }

  @Test
  void unparsableDropped() {
    assertThat(STAGE.parse(cand("BOLD:AAA1234"), null)).isEmpty();
  }

  @Test
  void unparsableMarkupKeptAsUnranked() {
    Optional<ParsedCandidate> p = STAGE.parse(cand("BOLD:AAA1234", Generator.TAXON_MARKUP, Map.of()), null);
    assertThat(p).isPresent();
    assertThat(p.get().kind()).isEqualTo(NameKind.UNINOMIAL);
    assertThat(p.get().rank()).isEqualTo(Rank.UNRANKED);
  }

  @Test
  void codeHintWinsOverParsedCode() {
    assertThat(STAGE.parse(cand("Carabus nigrus"), NomCode.BOTANICAL).orElseThrow().code()).isEqualTo(NomCode.BOTANICAL);
  }
}
