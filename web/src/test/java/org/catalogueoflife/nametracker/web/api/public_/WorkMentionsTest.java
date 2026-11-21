package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.nametracker.store.index.MentionRow;
import org.catalogueoflife.nametracker.store.search.TitleMentionRow;
import org.catalogueoflife.nametracker.web.api.public_.dto.NameDetail;
import org.catalogueoflife.nametracker.web.api.public_.dto.Snippet;
import org.junit.jupiter.api.Test;

class WorkMentionsTest {

  private static long nextId = 1;

  static MentionRow m(long name, String block, String type, int start, String verbatim, String snippet) {
    return new MentionRow(nextId++, 1, name, block, type, start, start + verbatim.length(), verbatim, null, null, 0.9f,
        null, null, snippet, "{}");
  }

  static NameDetail name(long id) {
    return new NameDetail(id, "n" + id, "BINOMIAL", null, "NONE", null, null, null, null, null, null, 0f, false, false, 1,
        List.of(), "best", List.of(), false, false, false, 0);
  }

  @Test
  void abstractRowsInReadingOrder() {
    List<TitleMentionRow> rows = WorkMentions.inBlocks(List.of(
        m(1, "b10", "ABSTRACT", 0, "Abies", "s"),
        m(2, "b2", "ABSTRACT", 9, "Picea", "s"),
        m(3, "b2", "ABSTRACT", 0, "Pinus", "s"),
        m(4, "b3", "PARAGRAPH", 0, "Larix", "s")), "ABSTRACT");
    assertThat(rows).extracting(TitleMentionRow::verbatim).containsExactly("Pinus", "Picea", "Abies");
  }

  @Test
  void oneBodySnippetPerNameInNameOrderPreferringParagraphs() {
    List<MentionRow> ms = List.of(
        m(1, "b1", "TITLE", 0, "Abies alba", "Abies alba in the title"),
        m(1, "b2", "ABSTRACT", 0, "Abies alba", "Abies alba in the abstract"),
        m(1, "b5", "TABLE_CELL", 0, "Abies alba", "Abies alba 12"),
        m(1, "b9", "PARAGRAPH", 4, "Abies alba", "Two Abies alba trees"),
        m(1, "b7", "PARAGRAPH", 4, "Abies alba", "See Abies alba first"),
        m(2, "b6", "CAPTION", 5, "Picea", "Fig. Picea cones"),
        m(3, "b99", "REFERENCE", 0, "Pinus", "Smith Pinus"));
    List<Snippet> s = WorkMentions.bodySnippets(List.of(name(2), name(3), name(1)), ms);
    assertThat(s).extracting(Snippet::nameId).containsExactly(2L, 1L);
    assertThat(s.get(0).blockType()).isEqualTo("CAPTION");
    assertThat(s.get(1).text()).isEqualTo("See Abies alba first");
    assertThat(s.get(1).mentions()).singleElement().satisfies(sp -> {
      assertThat(sp.startCp()).isEqualTo(4);
      assertThat(sp.endCp()).isEqualTo(14);
      assertThat(sp.nameId()).isEqualTo(1L);
    });
  }

  @Test
  void aSnippetWithoutTheVerbatimHasNoMark() {
    List<Snippet> s = WorkMentions.bodySnippets(List.of(name(1)),
        List.of(m(1, "b3", "PARAGRAPH", 0, "Abies alba", "cut before the name")));
    assertThat(s).singleElement().satisfies(x -> assertThat(x.mentions()).isEmpty());
  }
}
