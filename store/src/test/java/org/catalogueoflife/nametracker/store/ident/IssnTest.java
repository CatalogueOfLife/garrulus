package org.catalogueoflife.nametracker.store.ident;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IssnTest {

  @Test
  void normalisesSpacesHyphenAndPrefix() {
    assertThat(Issn.normalise("1175 5326")).isEqualTo("1175-5326");
    assertThat(Issn.normalise("11755326")).isEqualTo("1175-5326");
    assertThat(Issn.normalise("ISSN 1175-5334")).isEqualTo("1175-5334");
  }

  @Test
  void checkDigitX() {
    assertThat(Issn.normalise("0002-936x")).isEqualTo("0002-936X");
  }

  @Test
  void rejectsWrongCheckDigitAndJunk() {
    assertThat(Issn.normalise("1175-5327")).isNull();
    assertThat(Issn.normalise(null)).isNull();
    assertThat(Issn.normalise("")).isNull();
    assertThat(Issn.normalise("1175-53")).isNull();
    assertThat(Issn.normalise("abcd-efgh")).isNull();
  }

  @Test
  void allFindsEveryValidIssnInOrderDeduplicated() {
    assertThat(Issn.all("ISSN 1175-5326 (print), 1175-5334 (online)")).containsExactly("1175-5326", "1175-5334");
    assertThat(Issn.all("1175-5326 and 11755326 and 1175-5327")).containsExactly("1175-5326");
    assertThat(Issn.all(null)).isEmpty();
  }
}
