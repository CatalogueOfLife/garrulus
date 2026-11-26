package org.catalogueoflife.garrulus.indexer.openalex;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.work.Work;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class OpenAlexWorksTest {

  static JsonNode fixture(String name) throws IOException {
    try (InputStream in = OpenAlexWorksTest.class.getResourceAsStream("/openalex/" + name)) {
      return FinderJson.mapper().readTree(in);
    }
  }

  @Test
  void parsesZookeysWork() throws IOException {
    WorkRecord w = OpenAlexWorks.parse(fixture("work-W7221721627.json"));
    assertEquals("W7221721627", w.openalexId());
    assertEquals("10.3897/zookeys.1294.203117", w.doi());
    assertTrue(w.title().startsWith("Integrative taxonomy reveals a new species"));
    assertEquals(LocalDate.of(2026, 10, 13), w.publicationDate());
    assertEquals((short) 2026, w.year());
    assertEquals("1294", w.volume());
    assertNull(w.issue());
    assertEquals("333-353", w.pages());
    assertEquals("article", w.type());
    assertEquals("en", w.language());
    assertEquals("gold", w.oaStatus());
    assertEquals("cc-by", w.license());
    assertEquals("https://zookeys.pensoft.net/article/203117/download/pdf/", w.bestOaUrl());
    assertEquals("PMC13684320", w.pmcid());
    assertTrue(w.openalexHasTei());
    assertEquals("S199213172", w.sourceOpenalexId());
    assertTrue(w.abstractText().startsWith("A new species of the arcid"), w.abstractText());
    assertNull(w.abstractJats());

    JsonNode authors = FinderJson.mapper().readTree(w.authors());
    assertEquals(4, authors.size());
    assertEquals("Manal Al-Kandari", authors.get(0).get("name").asString());
    assertEquals("0000-0003-0073-7929", authors.get(0).get("orcid").asString());
    assertNull(authors.get(3).get("orcid"), "absent orcid is omitted");
  }

  @Test
  void toWorkFillsOriginAndAbstractSource() throws IOException {
    Work w = OpenAlexWorks.parse(fixture("work-W7221721627.json")).toWork(7L, "LIVE");
    assertEquals(7L, w.sourceId());
    assertEquals("LIVE", w.origin());
    assertEquals("OPENALEX_INVERTED", w.abstractSource());
    assertEquals("W7221721627", w.openalexId());
    assertEquals("PMC13684320", w.pmcid());
    assertTrue(w.openalexHasTei());
    assertNull(w.state());
  }

  @Test
  void licenceFallsBackToPrimaryLocationAndNormalises() {
    JsonNode n = FinderJson.mapper().readTree("""
        {"id":"https://openalex.org/W1","doi":"HTTPS://DOI.ORG/10.1/ABC",
         "best_oa_location":null,"primary_location":{"license":"CC-BY-SA"},
         "ids":{"pmcid":"https://www.ncbi.nlm.nih.gov/pmc/articles/PMC123/"},
         "has_content":{"grobid_xml":false}}""");
    WorkRecord w = OpenAlexWorks.parse(n);
    assertEquals("10.1/abc", w.doi());
    assertEquals("cc-by-sa", w.license());
    assertEquals("PMC123", w.pmcid());
    assertFalse(w.openalexHasTei());
    assertNull(w.abstractText());
    assertNull(w.sourceOpenalexId());
  }

  @Test
  void titleLosesMarkupAndEntities() {
    JsonNode n = FinderJson.mapper().readTree(
        "{\"id\":\"https://openalex.org/W1\",\"title\":\"Fruit set of plum (\\n <i>Prunus salicina</i>\\n ) in Mulsant &amp; Rey\"}");
    assertEquals("Fruit set of plum (Prunus salicina) in Mulsant & Rey", OpenAlexWorks.parse(n).title());
  }
}
