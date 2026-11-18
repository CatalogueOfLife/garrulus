package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.nametracker.web.api.public_.dto.ActSpan;
import org.catalogueoflife.nametracker.web.api.public_.dto.MentionSpan;
import org.junit.jupiter.api.Test;

class ActSpansTest {

  /** The span of verbatim in text (code points), carrying act. */
  static MentionSpan span(long id, String text, String verbatim, String act) {
    int at = text.indexOf(verbatim);
    int start = text.codePointCount(0, at);
    return new MentionSpan(id, id, start, start + verbatim.codePointCount(0, verbatim.length()), verbatim, act);
  }

  static String cut(String text, ActSpan a) {
    return text.substring(text.offsetByCodePoints(0, a.startCp()), text.offsetByCodePoints(0, a.endCp()));
  }

  @Test
  void theMarkerAfterTheNameAndItsAuthorship() {
    String t = "Here 𝔄 Diploneis langebertalotii Van de Vijver, sp. nov. is described.";
    List<ActSpan> a = ActSpans.locate(t, List.of(span(1, t, "Diploneis langebertalotii", "SP_NOV")));
    assertThat(a).singleElement().satisfies(x -> {
      assertThat(cut(t, x)).isEqualTo("sp. nov.");
      assertThat(x.mentionId()).isEqualTo(1);
      assertThat(x.actType()).isEqualTo("SP_NOV");
    });
  }

  @Test
  void eachMarkerGoesToTheNearestNameOfItsActType() {
    String t = "Aus bus is compared with Cus dus sp. nov. and Eus fus comb. nov.";
    List<ActSpan> a = ActSpans.locate(t, List.of(span(1, t, "Aus bus", "SP_NOV"), span(2, t, "Cus dus", "SP_NOV"),
        span(3, t, "Eus fus", "COMB_NOV")));
    assertThat(a).extracting(ActSpan::mentionId).containsExactly(2L, 3L);
    assertThat(cut(t, a.get(1))).isEqualTo("comb. nov.");
  }

  @Test
  void noMarkerForAnotherActTypeTooFarOrBeforeTheName() {
    String t = "sp. nov. Aus bus comb. nov. then a long stretch of text that runs on and on, sp. nov.";
    assertThat(ActSpans.locate(t, List.of(span(1, t, "Aus bus", "SP_NOV")))).isEmpty();
    assertThat(ActSpans.locate(t, List.of(span(1, t, "Aus bus", null)))).isEmpty();
    assertThat(ActSpans.locate(null, List.of())).isEmpty();
  }

  @Test
  void aSharedNovIsOneMarker() {
    String t = "Xus yus gen. et sp. nov. from Peru";
    List<ActSpan> a = ActSpans.locate(t, List.of(span(1, t, "Xus yus", "SP_NOV")));
    assertThat(a).singleElement().satisfies(x -> assertThat(cut(t, x)).isEqualTo("gen. et sp. nov."));
  }
}
