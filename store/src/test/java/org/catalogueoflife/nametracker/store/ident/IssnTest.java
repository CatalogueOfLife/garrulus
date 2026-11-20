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
  void storedFormIsTheNormalisedOneElseTheTrimmedUpperCaseInput() {
    assertThat(Issn.stored(" 0002-936x")).isEqualTo("0002-936X");
    assertThat(Issn.stored("13132989")).isEqualTo("1313-2989");
    assertThat(Issn.stored("1175-5327 ")).isEqualTo("1175-5327"); // a wrong check digit is kept as OpenAlex has it
    assertThat(Issn.stored("  ")).isNull();
    assertThat(Issn.stored(null)).isNull();
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

  @Test
  void normalisesLabelsAndOddSeparators() {
    for (String raw : new String[] {"ISSN: 1175-5326", "issn:1175-5326", "ISSN-L 1175-5326", "ISSN-L: 1175 5326",
        "eISSN 1175-5326", "e-ISSN: 1175-5326", "pISSN1175-5326", "P-ISSN 1175-5326", "EISSN:11755326",
        "1175\u00A05326", "1175\t5326", "\u00A01175-5326\u00A0"}) {
      assertThat(Issn.normalise(raw)).as(raw).isEqualTo("1175-5326");
    }
    assertThat(Issn.normalise("ISSN-X 1175-5326")).isNull();
  }

  @Test
  void allFindsGluedLabelAndIsNotSwallowedByANumber() {
    assertThat(Issn.all("ISSN1175-5326")).containsExactly("1175-5326");
    assertThat(Issn.all("eISSN1175-5334")).containsExactly("1175-5334");
    assertThat(Issn.all("ISSN-L1175-5326")).containsExactly("1175-5326");
    assertThat(Issn.all("2012 1175 5326")).isEmpty();
    assertThat(Issn.all("Vol 2012 1175-5326, ISSN 1175\u00A05334")).containsExactly("1175-5326", "1175-5334");
    assertThat(Issn.all("x11755326")).isEmpty();
  }

  @Test
  void allAcceptsOnlyHyphenatedOrLabelled() {
    assertThat(Issn.all("2018 1175-5326")).containsExactly("1175-5326");
    assertThat(Issn.all("2007 1175 5326")).isEmpty();
    assertThat(Issn.all("1995 1175 5326")).isEmpty();
    assertThat(Issn.all("ISSN 1175 5326")).containsExactly("1175-5326");
    assertThat(Issn.all("e-ISSN: 1175\u00A05334")).containsExactly("1175-5334");
    assertThat(Issn.all("ISSN 1175\u20135326")).containsExactly("1175-5326");
    assertThat(Issn.all("see 1175\u20135326.")).containsExactly("1175-5326");
    // valid check digits, but a page range, a DOI suffix, a date or a longer number
    assertThat(Issn.all("pp. 1175-5326")).isEmpty();
    assertThat(Issn.all("p.1175-5326")).isEmpty();
    assertThat(Issn.all("doi:10.1000/1175-5326")).isEmpty();
    assertThat(Issn.all("10.1000.1175-5326")).isEmpty();
    assertThat(Issn.all("1175-5326-1234")).isEmpty();
    assertThat(Issn.all("1175-53261")).isEmpty();
    assertThat(Issn.all("2026 1119 1175 5326")).isEmpty();
  }

  @Test
  void allStaysLinearOnHostileText() {
    // many hyphenated numbers after a long text: the page-range look-back reads only the few characters before each
    String hostile = "x".repeat(200_000) + " 1175-5326".repeat(50_000) + " ISSN" + ":".repeat(100_000);
    long start = System.nanoTime();
    assertThat(Issn.all(hostile)).containsExactly("1175-5326");
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(2));
    assertThat(Issn.all(new StringBuilder("see pp.  1175-5326 and 1175-5334"))).containsExactly("1175-5334");
  }
}
