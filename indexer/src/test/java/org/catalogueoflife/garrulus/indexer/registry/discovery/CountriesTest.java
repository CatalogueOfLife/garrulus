package org.catalogueoflife.garrulus.indexer.registry.discovery;

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
    assertThat(Countries.code("Namibia")).isEqualTo("NA"); // the name is Namibia, the bare cell NA is missing
    assertThat(Countries.code("Brunei")).isEqualTo("BN"); // as the Beacon writes it
    assertThat(Countries.code("Democratic Republic of the Congo")).isEqualTo("CD");
    assertThat(Countries.code("Congo - Kinshasa")).isEqualTo("CD");
    assertThat(Countries.code("Republic of the Congo")).isEqualTo("CG");
    assertThat(Countries.code("Myanmar")).isEqualTo("MM");
    assertThat(Countries.code("Burma")).isEqualTo("MM");
    assertThat(Countries.code("Côte d'Ivoire")).isEqualTo("CI");
    assertThat(Countries.code("Ivory Coast")).isEqualTo("CI");
    assertThat(Countries.code("Czech Republic")).isEqualTo("CZ");
    assertThat(Countries.code("Vietnam")).isEqualTo("VN");
    assertThat(Countries.code("Tanzania")).isEqualTo("TZ");
    assertThat(Countries.code("Bolivia")).isEqualTo("BO");
    assertThat(Countries.code("Venezuela")).isEqualTo("VE");
    assertThat(Countries.code("South Korea")).isEqualTo("KR");
    assertThat(Countries.code("NA")).isNull();
    assertThat(Countries.code("Atlantis")).isNull();
    assertThat(Countries.code(null)).isNull();
    assertThat(Countries.code(" ")).isNull();
  }
}
