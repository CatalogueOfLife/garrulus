package org.catalogueoflife.nametracker.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminOrcidsTest {

  @Test
  void normalisesUrlsAndLowercaseX() {
    AdminOrcids ids = new AdminOrcids(" https://orcid.org/0000-0002-1825-0097 , 0000-0001-5109-370x,,");
    assertThat(ids.any()).isTrue();
    assertThat(ids.contains("0000-0002-1825-0097")).isTrue();
    assertThat(ids.contains("https://orcid.org/0000-0002-1825-0097")).isTrue();
    assertThat(ids.contains("0000-0001-5109-370X")).isTrue();
    assertThat(ids.contains("0000-0001-5109-370x")).isTrue();
    assertThat(ids.contains("0000-0001-5109-3700")).isFalse();
    assertThat(ids.contains(null)).isFalse();
    assertThat(ids.contains("")).isFalse();
  }

  @Test
  void invalidEntriesIgnored() {
    AdminOrcids ids = new AdminOrcids(
        "0000-0002-1825-009, not-an-orcid, 0000-0002-1825-00977, https://example.org/0000-0002-1825-0097");
    assertThat(ids.any()).isFalse();
    assertThat(ids.contains("0000-0002-1825-009")).isFalse();
    assertThat(ids.contains("not-an-orcid")).isFalse();
    assertThat(AdminOrcids.anyValid("not-an-orcid")).isFalse();
    assertThat(AdminOrcids.anyValid("x, 0000-0002-1825-0097")).isTrue();

    assertThat(new AdminOrcids("").any()).isFalse();
    assertThat(new AdminOrcids(null).any()).isFalse();
    assertThat(AdminOrcids.anyValid(null)).isFalse();
    assertThat(AdminOrcids.anyValid("  ")).isFalse();
  }
}
