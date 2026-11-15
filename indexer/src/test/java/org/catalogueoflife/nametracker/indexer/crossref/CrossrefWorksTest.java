package org.catalogueoflife.nametracker.indexer.crossref;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.openalex.WorkRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CrossrefWorksTest {

  static JsonNode item(int i) throws IOException {
    try (InputStream in = CrossrefWorksTest.class.getResourceAsStream("/crossref/works-page.json")) {
      return FinderJson.mapper().readTree(in).path("message").path("items").get(i);
    }
  }

  @Test
  void keepsJatsAbstract() throws IOException {
    WorkRecord w = CrossrefWorks.parse(item(0));
    assertTrue(w.abstractJats().startsWith("<jats:p>A review of the ground beetles"), w.abstractJats());
    assertTrue(w.abstractJats().endsWith("</jats:p>"));
    assertNull(w.abstractText());
    assertEquals("10.3897/zookeys.100.1545", w.doi());
    assertEquals("Ground beetles of the Ukraine (Coleoptera, Carabidae)", w.title());
    assertEquals(LocalDate.of(2011, 5, 20), w.publicationDate());
    assertEquals((short) 2011, w.year());
    assertEquals("100", w.volume());
    assertEquals("503-515", w.pages());
    assertEquals("article", w.type());
    assertEquals("cc-by", w.license());
    assertNull(w.openalexId());
    assertNull(w.openalexHasTei());
    assertNull(w.sourceOpenalexId());
    assertTrue(w.authors().contains("Alexander Putchkov"), w.authors());
  }

  @Test
  void partialDateKeepsTheYear() {
    JsonNode n = FinderJson.mapper().readTree(
        "{\"DOI\":\"10.1/X\",\"title\":[\"T\"],\"published\":{\"date-parts\":[[2020]]},\"license\":[]}");
    WorkRecord w = CrossrefWorks.parse(n);
    assertEquals("10.1/x", w.doi());
    assertNull(w.publicationDate());
    assertEquals((short) 2020, w.year());
    assertNull(w.abstractJats());
    assertNull(w.license());
  }

  @Test
  void issnsListed() throws IOException {
    assertEquals(java.util.List.of("1313-2970", "1313-2989"), CrossrefWorks.issns(item(0)));
  }
}
