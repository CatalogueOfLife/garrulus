package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.dict.ColDictionary;
import org.catalogueoflife.nametracker.finder.dict.ColDictionaryBuilder;
import org.catalogueoflife.nametracker.finder.dict.CommonWords;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EpithetGuardTest {
  private static final String TSV = "col:ID\tcol:rank\tcol:uninomial\tcol:genericName\tcol:specificEpithet\tcol:infraspecificEpithet\n"
      + "1\tspecies\t\tAphrodes\tbicincta\t\n2\tsubspecies\t\tCarabus\tnigrus\tmontanus\n3\tvariety\t\tRosa\tcanina\tglandulosa\n";
  private static final EpithetGuard GUARD;

  static {
    try {
      ColDictionary d = ColDictionaryBuilder.fromNameUsageTsv(new StringReader(TSV), "1", CommonWords.of(List.of()));
      GUARD = new EpithetGuard(d);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** A gnfinder candidate for the first occurrence of span in text, as gnfinder names it. */
  private static Candidate gn(String text, String span, int cardinality) {
    int s = CodePoints.toCodePoint(text, text.indexOf(span));
    return new Candidate("b1", s, s + CodePoints.length(span), span, span, Generator.GNFINDER,
        Map.of("oddsLog10", 9.5, "cardinality", cardinality));
  }

  private static List<Candidate> guard(String text, Candidate... cs) {
    Document d = new Document("d", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, text, List.of(), List.of())), Map.of());
    List<Candidate> out = GUARD.apply(List.of(cs), d).candidates();
    for (Candidate c : out) assertThat(CodePoints.slice(text, c.start(), c.end())).isEqualTo(c.verbatim());
    return out;
  }

  @Test
  void bareThirdWordThatIsNoEpithetIsCut() {
    // dev: gnfinder gave "Aphrodes bicincta nymph" with odds 9.5
    String t = "The Aphrodes bicincta nymph was found.";
    List<Candidate> out = guard(t, gn(t, "Aphrodes bicincta nymph", 3));
    assertThat(out).extracting(Candidate::verbatim).containsExactly("Aphrodes bicincta");
    assertThat(out.get(0).name()).isEqualTo("Aphrodes bicincta");
    assertThat(out.get(0).features()).containsEntry("cardinality", 2).containsEntry("oddsLog10", 9.5);
  }

  @Test
  void stopWordsAndActMarkersAreCutAtAnyPosition() {
    // dev: "Lathraea clandestina has", "Arecophila has", "H. rosae has"; a gnfinder "gen" before "nov."
    String t = "Lathraea clandestina has a host. Arecophila has two. H. rosae has one. Gemenesis gen. nov. too.";
    List<Candidate> out = guard(t, gn(t, "Lathraea clandestina has", 3), gn(t, "Arecophila has", 2), gn(t, "H. rosae has", 3),
        gn(t, "Gemenesis gen", 2));
    assertThat(out).extracting(Candidate::verbatim).containsExactly("Lathraea clandestina", "Arecophila", "H. rosae", "Gemenesis");
    assertThat(out).extracting(Candidate::name).containsExactly("Lathraea clandestina", "Arecophila", "H. rosae", "Gemenesis");
  }

  @Test
  void validNamesAreKept() {
    String t = "Carabus nigrus montanus, Rosa canina var. glandulosa, Ophelia yangi and Carabus (Megodontus) nigrus.";
    Candidate[] cs = {gn(t, "Carabus nigrus montanus", 3), gn(t, "Rosa canina var. glandulosa", 3), gn(t, "Ophelia yangi", 2),
        gn(t, "Carabus (Megodontus) nigrus", 2)};
    assertThat(guard(t, cs)).containsExactly(cs);
  }

  @Test
  void loneAbbreviationIsDroppedAndOtherGeneratorsUntouched() {
    String t = "H. has gone, Aus bus nymph stays.";
    Candidate dictionary = new Candidate("b1", 13, 26, "Aus bus nymph", "Aus bus nymph", Generator.DICTIONARY, Map.of());
    EpithetGuard.Result r = GUARD.apply(List.of(gn(t, "H. has", 2), dictionary),
        new Document("d", "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, t, List.of(), List.of())), Map.of()));
    assertThat(r.candidates()).containsExactly(dictionary);
    assertThat(r.trimmed()).isEqualTo(1);
  }
}
