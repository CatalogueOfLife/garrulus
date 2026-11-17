package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.junit.jupiter.api.Test;

class TitleSpansTest {

  static TitleMentionRow row(int start, String verbatim) {
    return new TitleMentionRow(1, 1, 7, start, start + verbatim.length(), verbatim, null);
  }

  @Test
  void locatesNamesInOrder() {
    List<MentionSpan> s = TitleSpans.locate("A new species of Hemiphyllodactylus Bleeker, 1860 from Vietnam",
        List.of(row(17, "Hemiphyllodactylus")));
    assertThat(s).hasSize(1);
    assertThat(s.get(0).startCp()).isEqualTo(17);
    assertThat(s.get(0).endCp()).isEqualTo(35);
    assertThat(s.get(0).nameId()).isEqualTo(7);
  }

  @Test
  void dropsMentionNotInTitle() {
    assertThat(TitleSpans.locate("Revision of Syntelia", List.of(row(0, "Carabus")))).isEmpty();
  }

  @Test
  void astralCharacterBeforeName() {
    List<MentionSpan> s = TitleSpans.locate("𝔄 Abies alba", List.of(row(2, "Abies alba")));
    assertThat(s.get(0).startCp()).isEqualTo(2);
    assertThat(s.get(0).endCp()).isEqualTo(12);
  }

  @Test
  void overlappingMentionsKeepFirstLongest() {
    List<MentionSpan> s = TitleSpans.locate("Abies alba in Spain", List.of(row(0, "Abies alba"), row(0, "Abies")));
    assertThat(s).hasSize(1);
    assertThat(s.get(0).startCp()).isEqualTo(0);
    assertThat(s.get(0).endCp()).isEqualTo(10);
  }

  @Test
  void repeatedNameLocatesEachOccurrence() {
    List<MentionSpan> s = TitleSpans.locate("Abies and Abies", List.of(row(0, "Abies"), row(10, "Abies")));
    assertThat(s).extracting(MentionSpan::startCp, MentionSpan::endCp).containsExactly(tuple(0, 5), tuple(10, 15));
  }

  @Test
  void nullTitleGivesEmpty() {
    assertThat(TitleSpans.locate(null, List.of(row(0, "Abies")))).isEmpty();
  }
}
