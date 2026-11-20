package org.catalogueoflife.nametracker.store.ident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.catalogueoflife.nametracker.store.ident.IdScheme.Entity.SOURCE;
import static org.catalogueoflife.nametracker.store.ident.IdScheme.Entity.WORK;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.store.ident.IdScheme.Entity;
import org.junit.jupiter.api.Test;

class IdentifierRegistryTest {

  private final IdentifierRegistry reg = new IdentifierRegistry(List.of());

  @Test
  void templates() {
    assertThat(reg.links(WORK, List.of(new Identifier("DOI", "10.3897/zookeys.1187.113403"))).getFirst().url())
        .isEqualTo("https://doi.org/10.3897/zookeys.1187.113403");
    assertThat(IdScheme.MAGTECH.url(WORK, "www.biodiversity-science.net:92977"))
        .isEqualTo("https://www.biodiversity-science.net/EN/abstract/abstract92977.shtml");
    assertThat(IdScheme.OPENALEX.url(SOURCE, "S199213172")).isEqualTo("https://openalex.org/sources/S199213172");
    assertThat(IdScheme.OPENALEX.url(WORK, "W1")).isEqualTo("https://openalex.org/works/W1");
    assertThat(IdScheme.BIODIVERSITY_PMC.url(WORK, "pmc/PMC10768028"))
        .isEqualTo("https://biodiversitypmc.sibils.org/pmc/PMC10768028");
    assertThat(IdScheme.BPH.url(SOURCE, "1234")).isNull();
    assertThat(IdScheme.WIKIDATA.url(WORK, "Q1")).isNull();
  }

  @Test
  void siciDoiLinkIsEncoded() {
    assertThat(IdScheme.DOI.url(WORK, "10.1002/(sici)1097-4687(199601)227:1<1::aid-jmor1>3.0.co;2-#"))
        .isEqualTo("https://doi.org/10.1002/(sici)1097-4687(199601)227:1%3C1::aid-jmor1%3E3.0.co;2-%23");
  }

  @Test
  void parse() {
    assertThat(reg.parse("pmcid:10768028")).isEqualTo(new Identifier("PMCID", "PMC10768028"));
    assertThat(reg.parse("DOI:10.1002/(SICI)1097-4687(199601)227:1<1::AID-JMOR1>3.0.CO;2-#").value())
        .startsWith("10.1002/(sici)");
    assertThat(reg.parse("NOPE:1")).isNull();
    assertThat(reg.parse("DOI:nope")).isNull();
    assertThat(reg.parse("nocolon")).isNull();
  }

  @Test
  void linksSortedAndOaiHidden() {
    var links = reg.links(WORK, List.of(new Identifier("PMID", "5"), new Identifier("OAI", "oai:x:1"),
        new Identifier("DOI", "10.1234/b"), new Identifier("DOI", "10.1234/a"), new Identifier("ISSN", "1175-5326")));
    assertThat(links).extracting(IdentifierLink::scheme).containsExactly("DOI", "DOI", "PMID");
    assertThat(links).extracting(IdentifierLink::value).containsExactly("10.1234/a", "10.1234/b", "5");
    assertThat(reg.links(SOURCE, List.of(new Identifier("DOAJ", "1175-5326"), new Identifier("ISSN", "1175-5326"),
        new Identifier("BPH", "7")))).extracting(IdentifierLink::scheme).containsExactly("ISSN", "BPH", "DOAJ");
  }

  @Test
  void unknownCodeListedWithoutUrl() {
    assertThat(reg.links(WORK, List.of(new Identifier("FOO", "1"))))
        .containsExactly(new IdentifierLink("FOO", "FOO", "1", null));
  }

  @Test
  void extraSchemeIsFound() {
    IdScheme t = IdScheme.define("TESTDB", "Test DB", Set.of(WORK), raw -> raw.strip().toLowerCase(),
        Map.of(WORK, "https://example.org/{v}"), 5, true);
    var r = new IdentifierRegistry(List.of(t));
    assertThat(r.find("TESTDB")).isSameAs(t);
    assertThat(r.parse("testdb: AbC")).isEqualTo(new Identifier("TESTDB", "abc"));
    assertThat(r.links(WORK, List.of(new Identifier("TESTDB", "abc")))).containsExactly(
        new IdentifierLink("TESTDB", "Test DB", "abc", "https://example.org/abc"));
  }

  @Test
  void duplicateCodeRejected() {
    IdScheme dup = IdScheme.define("DOI", "x", Set.of(WORK), raw -> raw, Map.of(), 1, true);
    assertThatThrownBy(() -> new IdentifierRegistry(List.of(dup))).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void noZooBank() {
    assertThat(IdScheme.builtIns()).noneMatch(s -> s.code().startsWith("ZOOBANK"));
    assertThat(reg.find("ZOOBANK")).isNull();
    assertThat(reg.find("ZOOBANK_PUB")).isNull();
  }

  @Test
  void entitiesOfSchemes() {
    assertThat(IdScheme.OPENALEX.entities()).containsExactlyInAnyOrder(Entity.WORK, Entity.SOURCE);
    assertThat(IdScheme.OAI.shown()).isFalse();
  }

  @Test
  void everyBuiltInTemplate() {
    assertThat(IdScheme.DOI.url(WORK, "10.1/x")).isEqualTo("https://doi.org/10.1/x");
    assertThat(IdScheme.CROSSREF.url(WORK, "10.1/x")).isEqualTo("https://api.crossref.org/works/10.1/x");
    assertThat(IdScheme.OPENALEX.url(WORK, "W1")).isEqualTo("https://openalex.org/works/W1");
    assertThat(IdScheme.OPENALEX.url(SOURCE, "S1")).isEqualTo("https://openalex.org/sources/S1");
    assertThat(IdScheme.PMID.url(WORK, "5")).isEqualTo("https://europepmc.org/article/MED/5");
    assertThat(IdScheme.PMCID.url(WORK, "PMC5")).isEqualTo("https://europepmc.org/articles/PMC5");
    assertThat(IdScheme.BIODIVERSITY_PMC.url(WORK, "medline/9")).isEqualTo("https://biodiversitypmc.sibils.org/medline/9");
    assertThat(IdScheme.BLR.url(WORK, "123")).isEqualTo("https://zenodo.org/records/123");
    assertThat(IdScheme.PLAZI.url(WORK, "AB")).isEqualTo("https://tb.plazi.org/GgServer/summary/AB");
    assertThat(IdScheme.BHL_PART.url(WORK, "14315")).isEqualTo("https://www.biodiversitylibrary.org/part/14315");
    assertThat(IdScheme.MAGTECH.url(WORK, "example.org:7")).isEqualTo("https://example.org/EN/abstract/abstract7.shtml");
    assertThat(IdScheme.OAI.url(WORK, "oai:x:1")).isNull();
    assertThat(IdScheme.ISSN.url(SOURCE, "1175-5326")).isEqualTo("https://portal.issn.org/resource/ISSN/1175-5326");
    assertThat(IdScheme.ISSN_L.url(SOURCE, "1175-5326")).isEqualTo("https://portal.issn.org/resource/ISSN/1175-5326");
    assertThat(IdScheme.CROSSREF_JOURNAL.url(SOURCE, "1175-5326")).isEqualTo("https://api.crossref.org/journals/1175-5326");
    assertThat(IdScheme.WIKIDATA.url(SOURCE, "Q1")).isEqualTo("https://www.wikidata.org/wiki/Q1");
    assertThat(IdScheme.IPNI_PUB.url(SOURCE, "20011462-1")).isEqualTo("https://www.ipni.org/p/20011462-1");
    assertThat(IdScheme.BPH.url(SOURCE, "1")).isNull();
    assertThat(IdScheme.BHL_TITLE.url(SOURCE, "39620")).isEqualTo("https://www.biodiversitylibrary.org/bibliography/39620");
    assertThat(IdScheme.DOAJ.url(SOURCE, "abc")).isEqualTo("https://doaj.org/toc/abc");
  }

  @Test
  void m6aCodesPinned() {
    assertThat(List.of(IdScheme.DOI, IdScheme.OPENALEX, IdScheme.OAI, IdScheme.PMID, IdScheme.PMCID, IdScheme.BLR,
        IdScheme.MAGTECH)).extracting(IdScheme::code)
        .containsExactly("DOI", "OPENALEX", "OAI", "PMID", "PMCID", "BLR", "MAGTECH");
  }

  @Test
  void partsAreEncoded() {
    IdScheme t = IdScheme.define("PARTS", "Parts", Set.of(WORK), raw -> raw,
        Map.of(WORK, "https://x.example/{0}/{1}"), 1, true);
    assertThat(t.url(WORK, "a?b#c:d?e#f\u00e9")).isEqualTo("https://x.example/a%3Fb%23c/d%3Fe%23f%C3%A9");
  }

  @Test
  void templateMustBeHttp() {
    for (String bad : List.of("javascript:{v}", "{v}", "data:text/html,{v}", "ftp://x/{v}", " https://x/{v}")) {
      assertThatThrownBy(() -> IdScheme.define("BAD", "Bad", Set.of(WORK), raw -> raw, Map.of(WORK, bad), 1, true))
          .isInstanceOf(IllegalArgumentException.class);
    }
    IdScheme.define("OK", "Ok", Set.of(WORK), raw -> raw, Map.of(WORK, "HTTP://x/{v}"), 1, true);
  }

  @Test
  void magtechHostMustBePlain() {
    assertThat(IdScheme.MAGTECH.url(WORK, "user@evil.example:93333")).isNull();
    assertThat(IdScheme.MAGTECH.url(WORK, "evil.example:8080:93333")).isNull();
    assertThat(IdScheme.MAGTECH.url(WORK, "www.biodiversity-science.net:93333"))
        .isEqualTo("https://www.biodiversity-science.net/EN/abstract/abstract93333.shtml");
  }
}
