package org.catalogueoflife.garrulus.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.catalogueoflife.garrulus.store.search.TitleMentionRow;
import org.catalogueoflife.garrulus.web.api.public_.dto.MentionSpan;
import org.junit.jupiter.api.Test;

class TextSpansTest {

  static TitleMentionRow row(int start, String verbatim) {
    return row("b1", start, verbatim);
  }

  static TitleMentionRow row(String block, int start, String verbatim) {
    return row(block, start, verbatim, null);
  }

  static TitleMentionRow row(String block, int start, String verbatim, String actType) {
    return new TitleMentionRow(1, 1, 7, block, start, start + verbatim.length(), verbatim, actType);
  }

  @Test
  void locatesNamesInOrder() {
    List<MentionSpan> s = TextSpans.locate("A new species of Hemiphyllodactylus Bleeker, 1860 from Vietnam",
        List.of(row(17, "Hemiphyllodactylus")));
    assertThat(s).hasSize(1);
    assertThat(s.get(0).startCp()).isEqualTo(17);
    assertThat(s.get(0).endCp()).isEqualTo(35);
    assertThat(s.get(0).nameId()).isEqualTo(7);
  }

  @Test
  void dropsMentionNotInTitle() {
    assertThat(TextSpans.locate("Revision of Syntelia", List.of(row(0, "Carabus")))).isEmpty();
  }

  @Test
  void astralCharacterBeforeName() {
    List<MentionSpan> s = TextSpans.locate("𝔄 Abies alba", List.of(row(2, "Abies alba")));
    assertThat(s.get(0).startCp()).isEqualTo(2);
    assertThat(s.get(0).endCp()).isEqualTo(12);
  }

  @Test
  void overlappingMentionsKeepFirstLongest() {
    List<MentionSpan> s = TextSpans.locate("Abies alba in Spain", List.of(row(0, "Abies alba"), row(0, "Abies")));
    assertThat(s).hasSize(1);
    assertThat(s.get(0).startCp()).isEqualTo(0);
    assertThat(s.get(0).endCp()).isEqualTo(10);
  }

  @Test
  void repeatedNameLocatesEachOccurrence() {
    List<MentionSpan> s = TextSpans.locate("Abies and Abies", List.of(row(0, "Abies"), row(10, "Abies")));
    assertThat(s).extracting(MentionSpan::startCp, MentionSpan::endCp).containsExactly(tuple(0, 5), tuple(10, 15));
  }

  @Test
  void nullTitleGivesEmpty() {
    assertThat(TextSpans.locate(null, List.of(row(0, "Abies")))).isEmpty();
  }

  @Test
  void matchesWholeWordsOnly() {
    List<MentionSpan> s = TextSpans.locate("Museum specimens of Mus", List.of(row(20, "Mus")));
    assertThat(s).extracting(MentionSpan::startCp, MentionSpan::endCp).containsExactly(tuple(20, 23));
  }

  @Test
  void dropsMentionFoundOnlyInsideWords() {
    assertThat(TextSpans.locate("Musculus and Museums", List.of(row(0, "Mus")))).isEmpty();
    assertThat(TextSpans.locate("Abies2 alba", List.of(row(0, "Abies")))).isEmpty();
  }

  @Test
  void punctuationAndEdgesAreBoundaries() {
    List<MentionSpan> s = TextSpans.locate("(Mus), Mus-like: Mus", List.of(row(1, "Mus"), row(7, "Mus"), row(17, "Mus")));
    assertThat(s).extracting(MentionSpan::startCp).containsExactly(1, 7, 17);
  }

  @Test
  void overlapRuleComparesOffsetsWithinOneBlock() {
    // offsets are per block: the second title block's mention at 0 does not overlap the first block's at 0..10
    List<MentionSpan> s = TextSpans.locate("Abies alba and Carabus",
        List.of(row("b1", 0, "Abies alba"), row("b2", 0, "Carabus")));
    assertThat(s).extracting(MentionSpan::startCp, MentionSpan::endCp).containsExactly(tuple(0, 10), tuple(15, 22));
  }

  @Test
  void laterBlockNamesBehindTheCursorAreFound() {
    // a full-text document with a simple summary (b1) before its abstract (b2): b1's names carry the cursor to the end
    // of the displayed abstract, b2's own names before that point are found in a second pass, spans in text order
    String text = "Ahasverus advena (Coleoptera: Silvanidae) is a pest. A. advena larvae feed on mould.";
    List<MentionSpan> s = TextSpans.locate(text, List.of(
        row("b1", 0, "Ahasverus advena"), row("b1", 40, "A. advena"),
        row("b2", 0, "Ahasverus advena"), row("b2", 18, "Coleoptera"), row("b2", 30, "Silvanidae")));
    assertThat(s).extracting(MentionSpan::verbatim, MentionSpan::startCp).containsExactly(
        tuple("Ahasverus advena", 0), tuple("Coleoptera", 18), tuple("Silvanidae", 30), tuple("A. advena", 53));
  }

  @Test
  void secondPassTakesOnlyFreeOccurrences() {
    List<MentionSpan> s = TextSpans.locate("Abies and Abies",
        List.of(row("b1", 0, "Abies"), row("b1", 10, "Abies"), row("b2", 0, "Abies")));
    assertThat(s).extracting(MentionSpan::startCp).containsExactly(0, 10);
  }

  @Test
  void secondPassSkipsMentionsCoveredInTheirOwnBlock() {
    // "Abies" inside the stored "Abies alba" is covered by it, so it is not placed on another "Abies"
    List<MentionSpan> s = TextSpans.locate("Abies alba and Abies",
        List.of(row("b1", 0, "Abies alba"), row("b1", 0, "Abies")));
    assertThat(s).extracting(MentionSpan::startCp, MentionSpan::endCp).containsExactly(tuple(0, 10));
  }

  @Test
  void secondPassPutsAnActBeforeItsMarker() {
    String text = "Aus bus lives with Aus bus sp. nov. near Cus dus.";
    List<MentionSpan> s = TextSpans.locate(text,
        List.of(row("b1", 0, "Cus dus"), row("b2", 0, "Aus bus", "SP_NOV")));
    assertThat(s).extracting(MentionSpan::verbatim, MentionSpan::startCp, MentionSpan::actType)
        .containsExactly(tuple("Aus bus", 19, "SP_NOV"), tuple("Cus dus", 41, null));
  }

  @Test
  void secondPassPlacesActsBeforePlainMentions() {
    // the plain mention comes first in its block, but must not take the occurrence before the marker
    String text = "Aus bus sp. nov. and Aus bus near Cus dus.";
    List<MentionSpan> s = TextSpans.locate(text, List.of(row("b1", 0, "Cus dus"),
        row("b2", 0, "Aus bus"), row("b2", 30, "Aus bus", "SP_NOV")));
    assertThat(s).extracting(MentionSpan::verbatim, MentionSpan::startCp, MentionSpan::actType).containsExactly(
        tuple("Aus bus", 0, "SP_NOV"), tuple("Aus bus", 21, null), tuple("Cus dus", 34, null));
  }

  @Test
  void secondPassPutsAnActWithoutMarkerOnTheFirstFreeOccurrence() {
    List<MentionSpan> s = TextSpans.locate("Aus bus lives with Aus bus near Cus dus.",
        List.of(row("b1", 0, "Cus dus"), row("b2", 0, "Aus bus", "SP_NOV")));
    assertThat(s).extracting(MentionSpan::verbatim, MentionSpan::startCp)
        .containsExactly(tuple("Aus bus", 0), tuple("Cus dus", 32));
  }

  @Test
  void secondPassCountsCodePoints() {
    List<MentionSpan> s = TextSpans.locate("𝔄 Abies near Carabus",
        List.of(row("b1", 0, "Carabus"), row("b2", 0, "Abies")));
    assertThat(s).extracting(MentionSpan::verbatim, MentionSpan::startCp, MentionSpan::endCp)
        .containsExactly(tuple("Abies", 2, 7), tuple("Carabus", 13, 20));
  }
}
