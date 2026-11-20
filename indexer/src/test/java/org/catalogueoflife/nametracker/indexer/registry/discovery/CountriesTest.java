package org.catalogueoflife.nametracker.indexer.registry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountriesTest {

  @Test
  void englishNamesAndAliases() {
    assertThat(Countries.code("Germany")).isEqualTo("DE");
    assertThat(Countries.code("  indonesia ")).isEqualTo("ID");
    assertThat(Countries.code("Russia")).isEqualTo("RU");
    assertThat(Countries.code("Iran")).isEqualTo("IR");
    assertThat(Countries.code("Türkiye")).isEqualTo("TR");
    assertThat(Countries.code("Turkey")).isEqualTo("TR");
    assertThat(Countries.code("Viet Nam")).isEqualTo("VN");
    assertThat(Countries.code("Korea, Republic of")).isEqualTo("KR");
    assertThat(Countries.code("Czechia")).isEqualTo("CZ");
    assertThat(Countries.code("Taiwan")).isEqualTo("TW");
    assertThat(Countries.code("Laos")).isEqualTo("LA");
    assertThat(Countries.code("Moldova")).isEqualTo("MD");
    assertThat(Countries.code("BR")).isEqualTo("BR");
    assertThat(Countries.code("NA")).isNull();
    assertThat(Countries.code("Atlantis")).isNull();
    assertThat(Countries.code(null)).isNull();
    assertThat(Countries.code(" ")).isNull();
  }
}
