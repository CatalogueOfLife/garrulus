package org.catalogueoflife.garrulus.store.ident;

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

  @Test
  void normalisesNewSchemes() {
    assertThat(IdScheme.CROSSREF.normalise("https://doi.org/10.1234/ABC")).isEqualTo("10.1234/abc");
    assertThat(IdScheme.CROSSREF.normalise("x")).isNull();
    assertThat(IdScheme.PLAZI.normalise("abcdef0123456789abcdef0123456789")).isEqualTo("ABCDEF0123456789ABCDEF0123456789");
    assertThat(IdScheme.PLAZI.normalise("abc")).isNull();
    assertThat(IdScheme.BIODIVERSITY_PMC.normalise("PMC/123")).isEqualTo("pmc/PMC123");
    assertThat(IdScheme.BIODIVERSITY_PMC.normalise("pmc/PMC123")).isEqualTo("pmc/PMC123");
    assertThat(IdScheme.BIODIVERSITY_PMC.normalise("medline/99")).isEqualTo("medline/99");
    assertThat(IdScheme.BIODIVERSITY_PMC.normalise("other/9")).isNull();
    assertThat(IdScheme.BHL_PART.normalise(" 14315 ")).isEqualTo("14315");
    assertThat(IdScheme.BHL_PART.normalise("14a")).isNull();
    assertThat(IdScheme.ISSN.normalise("issn 1175 5326")).isEqualTo("1175-5326");
    assertThat(IdScheme.ISSN.normalise("1175-5327")).isNull();
    assertThat(IdScheme.ISSN_L.normalise("1175-5326")).isEqualTo("1175-5326");
    assertThat(IdScheme.CROSSREF_JOURNAL.normalise("1175-5326")).isEqualTo("1175-5326");
    assertThat(IdScheme.CROSSREF_JOURNAL.normalise("zz")).isNull();
    assertThat(IdScheme.WIKIDATA.normalise("q42")).isEqualTo("Q42");
    assertThat(IdScheme.WIKIDATA.normalise("P42")).isNull();
    assertThat(IdScheme.IPNI_PUB.normalise("20011462-1")).isEqualTo("20011462-1");
    assertThat(IdScheme.IPNI_PUB.normalise("20011462")).isNull();
    assertThat(IdScheme.BPH.normalise("1234")).isEqualTo("1234");
    assertThat(IdScheme.BPH.normalise("x")).isNull();
    assertThat(IdScheme.BHL_TITLE.normalise("39620")).isEqualTo("39620");
    assertThat(IdScheme.DOAJ.normalise("ABCDEF0123456789ABCDEF0123456789")).isEqualTo("abcdef0123456789abcdef0123456789");
    assertThat(IdScheme.DOAJ.normalise("1175-5326")).isEqualTo("1175-5326");
    assertThat(IdScheme.DOAJ.normalise("nope")).isNull();
  }
}
