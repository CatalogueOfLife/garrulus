package org.catalogueoflife.nametracker.indexer.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class LicencePolicyTest {

  @ParameterizedTest
  @CsvSource({
      "http://creativecommons.org/licenses/by/4.0/, cc-by",
      "https://creativecommons.org/licenses/by/4.0/legalcode, cc-by",
      "https://www.creativecommons.org/licenses/by-sa/3.0, cc-by-sa",
      "http://creativecommons.org/licenses/by-nc/4.0/, cc-by-nc",
      "https://creativecommons.org/licenses/by-nc-nd/4.0/, cc-by-nc-nd",
      "CC BY 4.0, cc-by",
      "cc-by-4.0, cc-by",
      "CC-BY, cc-by",
      "cc by-sa 4.0, cc-by-sa",
      "CC BY-NC-ND 4.0, cc-by-nc-nd",
      "cc-by-nc-sa-3.0, cc-by-nc-sa",
      "cc0, cc0",
      "CC0 1.0, cc0",
      "https://creativecommons.org/publicdomain/zero/1.0/, cc0",
      "public-domain, public-domain",
      "Public Domain, public-domain",
      "https://creativecommons.org/publicdomain/mark/1.0/, public-domain",
      "  Publisher-Specific-OA , publisher-specific-oa",
      "other-oa, other-oa"})
  void normalisesCommonForms(String in, String out) {
    assertThat(LicencePolicy.normalise(in)).isEqualTo(out);
  }

  @Test
  void onlyConfiguredLicencesAreOpen() {
    LicencePolicy p = new LicencePolicy(Set.of("cc-by", "cc-by-sa", "cc0", "public-domain"));
    assertThat(p.isOpen("http://creativecommons.org/licenses/by/4.0/")).isTrue();
    assertThat(p.isOpen("CC BY-SA 4.0")).isTrue();
    assertThat(p.isOpen("cc0")).isTrue();
    assertThat(p.isOpen("CC BY-NC-ND 4.0")).isFalse();
    assertThat(p.isOpen("cc-by-nc")).isFalse();
    assertThat(p.isOpen("publisher-specific-oa")).isFalse();
    assertThat(p.isOpen(null)).isFalse();
    assertThat(p.isOpen(" ")).isFalse();
  }
}
