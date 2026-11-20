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

  private static LocalDate latest(String published) {
    return CrossrefWorks.latestPublished(FinderJson.mapper().readTree("{\"published\":" + published + "}"));
  }

  /** M2-K2: a partial date counts at its latest possible day. */
  @Test
  void latestPublishedDay() {
    assertEquals(LocalDate.of(2015, 12, 31), latest("{\"date-parts\":[[2015]]}"));
    assertEquals(LocalDate.of(2024, 2, 29), latest("{\"date-parts\":[[2024, 2]]}"));
    assertEquals(LocalDate.of(2023, 2, 28), latest("{\"date-parts\":[[2023, 2]]}"));
    assertEquals(LocalDate.of(2026, 7, 4), latest("{\"date-parts\":[[2026, 7, 4]]}"));
    assertNull(latest("{\"date-parts\":[[null]]}"));
    assertNull(latest("{\"date-parts\":[[2026, 13]]}"));
    assertNull(CrossrefWorks.latestPublished(FinderJson.mapper().readTree("{}")));
  }

  @Test
  void titleLosesMarkupAndEntities() {
    JsonNode n = FinderJson.mapper().readTree("{\"DOI\":\"10.1/T\",\"title\":[\"Identification of geraniol from\\n"
        + "                    <i>Fraxinus mandschurica</i>\\n                    (Mulsant &amp; Rey)\"]}");
    assertEquals("Identification of geraniol from Fraxinus mandschurica (Mulsant & Rey)", CrossrefWorks.parse(n).title());
  }

  @Test
  void originalTitle() {
    JsonNode n = FinderJson.mapper().readTree("{\"DOI\":\"10.1/x\",\"title\":[\"Two new genera of cockroaches\"],"
        + "\"original-title\":[\"中国西南地区蜚蠊科两新属\"]}");
    assertEquals(java.util.List.of(new org.catalogueoflife.nametracker.indexer.layer.TextIn("TITLE", null, "ORIGINAL",
        "中国西南地区蜚蠊科两新属")), CrossrefWorks.texts(n));
    assertEquals("Two new genera of cockroaches", CrossrefWorks.parse(n).title());
    // equal to the title or absent: nothing
    assertTrue(CrossrefWorks.texts(FinderJson.mapper().readTree(
        "{\"title\":[\"Same\"],\"original-title\":[\"Same\"]}")).isEmpty());
    assertTrue(CrossrefWorks.texts(FinderJson.mapper().readTree("{\"title\":[\"T\"],\"original-title\":[]}")).isEmpty());
    assertTrue(CrossrefClient.SELECT.contains("original-title"));
  }
}
