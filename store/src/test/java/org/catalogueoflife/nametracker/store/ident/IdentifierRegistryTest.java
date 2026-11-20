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
}
