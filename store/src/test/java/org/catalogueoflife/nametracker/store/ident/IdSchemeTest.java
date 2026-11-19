package org.catalogueoflife.nametracker.store.ident;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdSchemeTest {

  @Test
  void normalises() {
    assertThat(IdScheme.DOI.normalise("https://doi.org/10.11646/ZOOTAXA.5432.1.1")).isEqualTo("10.11646/zootaxa.5432.1.1");
    assertThat(IdScheme.DOI.normalise("doi:10.1/X")).isNull();
    assertThat(IdScheme.DOI.normalise("doi:10.1234/X")).isEqualTo("10.1234/x");
    assertThat(IdScheme.DOI.normalise("not a doi")).isNull();
    assertThat(IdScheme.OPENALEX.normalise("https://openalex.org/W123")).isEqualTo("W123");
    assertThat(IdScheme.PMCID.normalise("9836452")).isEqualTo("PMC9836452");
    assertThat(IdScheme.PMID.normalise("PMID:12345")).isEqualTo("12345");
    assertThat(IdScheme.MAGTECH.normalise("https://www.biodiversity-science.net/EN/abstract/abstract93333.shtml"))
        .isEqualTo("www.biodiversity-science.net:93333");
    assertThat(IdScheme.BLR.normalise("https://zenodo.org/records/1234567")).isEqualTo("1234567");
    assertThat(IdScheme.OAI.normalise(" oai:zookeys.pensoft.net:12345 ")).isEqualTo("oai:zookeys.pensoft.net:12345");
  }

  @Test
  void idAndOf() {
    assertThat(IdScheme.id(IdScheme.PMID, "PMID:12")).isEqualTo(new Identifier("PMID", "12"));
    assertThat(IdScheme.id(IdScheme.DOI, "nope")).isNull();
    assertThat(IdScheme.id(IdScheme.DOI, null)).isNull();
    assertThat(IdScheme.of("DOI")).isSameAs(IdScheme.DOI);
  }
}
