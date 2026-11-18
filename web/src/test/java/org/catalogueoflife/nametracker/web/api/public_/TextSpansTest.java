package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.junit.jupiter.api.Test;

class TextSpansTest {

  static TitleMentionRow row(int start, String verbatim) {
    return row("b1", start, verbatim);
  }

  static TitleMentionRow row(String block, int start, String verbatim) {
    return new TitleMentionRow(1, 1, 7, block, start, start + verbatim.length(), verbatim, null);
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
}
