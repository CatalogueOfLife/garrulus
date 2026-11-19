package org.catalogueoflife.nametracker.indexer.layer.oai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.Work;
import org.junit.jupiter.api.Test;

class OaiRecordMapperTest {

  private static List<OaiRecord> records(String fixture, String prefix) throws Exception {
    return OaiPmhClient.readRecords(OaiPmhClientTest.fixture(fixture).getBytes(StandardCharsets.UTF_8), prefix);
  }

  private static HarvestedWork map(String fixture, String prefix, int i) throws Exception {
    return OaiRecordMapper.toHarvested(records(fixture, prefix).get(i), 7L).orElseThrow();
  }

  @Test
  void pensoftOaiDcHasDoiAbstractAuthors() throws Exception {
    HarvestedWork hw = map("pensoft-listrecords-oai_dc.xml", "oai_dc", 1);
    Work w = hw.work();
    assertThat(hw.layer()).isEqualTo(Layer.OAI);
    assertThat(w.sourceId()).isEqualTo(7L);
    assertThat(w.doi()).startsWith("10.3897/bdj.14.e205769");
    assertThat(hw.ids()).extracting(Identifier::scheme).containsExactly("DOI");
    assertThat(w.title()).startsWith("Global review of bull shark");
    assertThat(w.authors()).contains("\"name\":\"Mariana Haueisen\"", "\"orcid\":\"0000-0002-8790-594X\"",
        "Luiz Fernando Salvador-Jr.");
    assertThat(w.abstractText()).isNotBlank();
    assertThat(w.abstractSource()).isEqualTo("OAI");
    assertThat(w.abstractJats()).isNull();
    assertThat(w.year()).isEqualTo((short) 2026);
    assertThat(w.publicationDate()).isNull(); // dc:date is a year only
    assertThat(w.language()).isEqualTo("en");
    assertThat(w.license()).isNull(); // "CC BY 4.0" is no URL
    assertThat(w.origin()).isNull();
  }

  @Test
  void jottJatsKeepsItalicAbstractAndLicence() throws Exception {
    HarvestedWork hw = map("jott-listrecords-jats.xml", "jats", 0);
    Work w = hw.work();
    assertThat(w.title()).startsWith("A preliminary study to investigate behavioural differences among elephants");
    assertThat(w.doi()).isEqualTo("10.11609/jott.6161.18.4.28657-28661");
    assertThat(hw.ids()).extracting(Identifier::scheme).containsExactly("DOI", "OAI");
    assertThat(hw.ids().get(1).value()).isEqualTo("oai:ojs.pkp.sfu.ca:article/6161");
    assertThat(w.abstractJats()).startsWith("<p>").contains("<italic>Elephas maximus</italic>").doesNotContain("xmlns");
    assertThat(w.abstractJatsSource()).isEqualTo("OAI");
    assertThat(w.abstractText()).isNull();
    assertThat(w.abstractSource()).isNull();
    assertThat(w.license()).isEqualTo("cc-by");
    assertThat(w.publicationDate()).isEqualTo(LocalDate.of(2026, 4, 26));
    assertThat(w.year()).isEqualTo((short) 2026);
    assertThat(w.volume()).isEqualTo("18");
    assertThat(w.issue()).isEqualTo("4");
    assertThat(w.pages()).isEqualTo("28657-28661");
    assertThat(w.language()).isEqualTo("en");
    assertThat(w.authors()).contains("Tharindu Muthukumarana", "0000-0001-5471-6838");
  }

  @Test
  void magnoliaWithoutRightsHasNoLicence() throws Exception {
    HarvestedWork hw = map("magnolia-listrecords-oai_dc.xml", "oai_dc", 0);
    Work w = hw.work();
    assertThat(w.license()).isNull();
    assertThat(w.doi()).isEqualTo("10.11646/zootaxa.5741.1.1");
    assertThat(hw.ids()).extracting(Identifier::scheme).containsExactly("DOI", "OAI");
    assertThat(w.publicationDate()).isEqualTo(LocalDate.of(2026, 1, 5));
    assertThat(w.authors()).contains("C.A. VIRAKTAMATH");
    assertThat(w.language()).isEqualTo("eng");
    assertThat(w.abstractText()).startsWith("Leafhoppers of the tribes");
  }

  @Test
  void deletedRecordMapsToEmpty() throws Exception {
    OaiRecord r = records("deleted-record.xml", "oai_dc").getFirst();
    assertThat(r.deleted()).isTrue();
    assertThat(OaiRecordMapper.toHarvested(r, 7L)).isEmpty();
  }

  @Test
  void recordWithoutTitleMapsToEmpty() throws Exception {
    OaiRecord r = records("magnolia-listrecords-oai_dc.xml", "oai_dc").getFirst();
    OaiRecord untitled = new OaiRecord(r.identifier(), false, r.datestamp(), r.sets(),
        OaiPmhClient.parse("<dc xmlns=\"http://purl.org/dc/elements/1.1/\"/>".getBytes(StandardCharsets.UTF_8))
            .getDocumentElement(), "oai_dc");
    Optional<HarvestedWork> hw = OaiRecordMapper.toHarvested(untitled, 7L);
    assertThat(hw).isEmpty();
  }

  @Test
  void firstDoiShapedIdentifierWins() throws Exception {
    String xml = "<oai_dc:dc xmlns:oai_dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" "
        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>T</dc:title>"
        + "<dc:identifier>hdl:10013/epic.1234</dc:identifier>"
        + "<dc:identifier>https://journal.example.org/article/view/12</dc:identifier>"
        + "<dc:identifier>https://doi.org/10.5555/First.One</dc:identifier>"
        + "<dc:identifier>10.5555/second</dc:identifier>"
        + "<dc:rights>https://creativecommons.org/licenses/by-nc/4.0/</dc:rights></oai_dc:dc>";
    OaiRecord r = new OaiRecord("oai:journal.example.org:12", false, null, List.of(),
        OaiPmhClient.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement(), "oai_dc");
    Work w = OaiRecordMapper.toHarvested(r, 1L).orElseThrow().work();
    assertThat(w.doi()).isEqualTo("10.5555/first.one");
    assertThat(w.license()).isEqualTo("cc-by-nc");
  }

  @Test
  void doiOfUnderstandsTheCommonForms() {
    assertThat(OaiRecordMapper.doiOf("info:doi/10.1234/Abc")).isEqualTo("10.1234/abc");
    assertThat(OaiRecordMapper.doiOf("info:doi:10.1234/abc")).isEqualTo("10.1234/abc");
    assertThat(OaiRecordMapper.doiOf("doi:10.1234/abc")).isEqualTo("10.1234/abc");
    assertThat(OaiRecordMapper.doiOf("DOI: 10.1234/abc")).isEqualTo("10.1234/abc");
    assertThat(OaiRecordMapper.doiOf("https://dx.doi.org/10.1234/abc")).isEqualTo("10.1234/abc");
    assertThat(OaiRecordMapper.doiOf("http://doi.org/10.1234/abc")).isEqualTo("10.1234/abc");
    assertThat(OaiRecordMapper.doiOf("https://x.org/proxy/doi.org/10.1234/abc")).isNull();
    assertThat(OaiRecordMapper.doiOf("https://notdoi.org/10.1234/abc")).isNull();
    assertThat(OaiRecordMapper.doiOf("hdl:10013/epic.1234")).isNull();
  }

  @Test
  void issnsOfDublinCoreAndJatsRecords() throws Exception {
    assertThat(OaiRecordMapper.issns(records("magnolia-listrecords-oai_dc.xml", "oai_dc").get(0)))
        .containsExactlyInAnyOrder("1175-5334", "1175-5326");
    assertThat(OaiRecordMapper.issns(records("jott-listrecords-jats.xml", "jats").get(0)))
        .containsExactlyInAnyOrder("0974-7907", "0974-7893");
    // a page range that looks like an ISSN fails the check digit
    assertThat(OaiRecordMapper.validIssn("1234-1250")).isFalse();
    assertThat(OaiRecordMapper.validIssn("1809-127X")).isTrue();
  }

  @Test
  void doiUrlKeepsAPlus() {
    assertThat(OaiRecordMapper.doiOf("https://doi.org/10.1002/(SICI)1097-4679+X%3C1::AID%3E3.0.CO;2-K"))
        .isEqualTo("10.1002/(sici)1097-4679+x<1::aid>3.0.co;2-k");
    assertThat(OaiRecordMapper.doiOf("https://doi.org/10.5555/a%20b")).isNull();
  }
}
