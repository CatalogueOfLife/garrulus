package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.Generator;
import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.catalogueoflife.garrulus.finder.dict.ColDictionary;
import org.catalogueoflife.garrulus.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.garrulus.finder.dict.CommonWords;
import org.gbif.nameparser.api.NameParser;
import org.gbif.nameparser.api.NameType;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParseResult;
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
  private static ColDictionary DICT;

  static {
    try {
      ColDictionary d = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(TSV), "1", CommonWords.of(Set.of()));
      DICT = d;
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
    assertThat(p.canonical()).isEqualTo("Carabus nigrus"); // lossy: subgenus dropped
    assertThat(p.c().name()).isEqualTo("Carabus (Megodontus) nigrus");
  }

  @Test
  void infragenericWithoutSpecies() {
    ParsedCandidate p = parse("Carabus (Megodontus)");
    assertThat(p.kind()).isEqualTo(NameKind.INFRAGENERIC);
    assertThat(p.canonical()).isEqualTo("Carabus infragen. Megodontus");
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
  void codeHintIsUsedAsCode() {
    // limitation: the parser reports no code for plain binomials, so this cannot show a hint overriding a parsed code
    assertThat(STAGE.parse(cand("Carabus nigrus"), NomCode.BOTANICAL).orElseThrow().code()).isEqualTo(NomCode.BOTANICAL);
    assertThat(parse("Carabus nigrus").code()).isNull();
  }

  @Test
  void markupNonScientificIsKept() {
    var p = STAGE.parse(cand("Carabus sp. A", Generator.TAXON_MARKUP, Map.of()), null);
    assertThat(p).isPresent();
    assertThat(STAGE.parse(cand("Carabus sp. A"), null)).isEmpty();
  }

  @Test
  void markupScientificKeepsParsedForm() {
    var p = STAGE.parse(cand("Carabus nigrus", Generator.TAXON_MARKUP, Map.of()), null).orElseThrow();
    assertThat(p.kind()).isEqualTo(NameKind.BINOMIAL);
    assertThat(p.rank()).isEqualTo(Rank.SPECIES);
  }

  @Test
  void otherAbbreviationsAccepted() {
    assertThat(parse("Pt. niger").kind()).isEqualTo(NameKind.ABBREVIATED);
    assertThat(parse("L. g. confertiflora").kind()).isEqualTo(NameKind.ABBREVIATED);
  }

  @Test
  void notAbbreviationsDropped() {
    for (String s : new String[] {"Fig. 3", "Sp. nov.", "Gen. nov.", "St. Louis", "Fam. Carabidae", "Mr. Smith"}) {
      assertThat(STAGE.parse(cand(s), null)).as(s).isEmpty();
    }
  }

  @Test
  void abbreviationKeptWhenParserFails() {
    // stub parser that returns no parse at all
    NameParser failing = (n, a, r, c) -> new ParseResult.Unparsable(NameType.OTHER, n);
    var stage = new ParserStage(failing, DICT);
    var p = stage.parse(cand("C. nigrus"), null).orElseThrow();
    assertThat(p.kind()).isEqualTo(NameKind.ABBREVIATED);
    assertThat(p.canonical()).isEqualTo("C. nigrus");
    assertThat(stage.parse(cand("Fig. 3"), null)).isEmpty();
  }
}
