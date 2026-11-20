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
    assertThat(w.language()).isEqualTo("en");
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
  void jatsIssnsComeFromTheJournalMetaOnly() throws Exception {
    String xml = "<record xmlns=\"http://www.openarchives.org/OAI/2.0/\"><header><identifier>oai:x:1</identifier>"
        + "<datestamp>2026-01-01</datestamp></header><metadata><article xmlns=\"https://jats.nlm.nih.gov/ns/archiving/1.3/\">"
        + "<front><journal-meta><issn pub-type=\"epub\">0974-7907</issn></journal-meta><article-meta>"
        + "<title-group><article-title>T</article-title></title-group></article-meta></front>"
        + "<back><ref-list><ref><element-citation><source>Zootaxa</source><issn>1175-5326</issn></element-citation>"
        + "</ref></ref-list></back></article></metadata></record>";
    String page = "<OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\"><ListRecords>" + xml
        + "</ListRecords></OAI-PMH>";
    OaiRecord r = OaiPmhClient.readRecords(page.getBytes(StandardCharsets.UTF_8), "jats").getFirst();
    // a reference's ISSN is not the record's journal
    assertThat(OaiRecordMapper.issns(r)).containsExactly("0974-7907");
  }

  @Test
  void doiUrlKeepsAPlus() {
    assertThat(OaiRecordMapper.doiOf("https://doi.org/10.1002/(SICI)1097-4679+X%3C1::AID%3E3.0.CO;2-K"))
        .isEqualTo("10.1002/(sici)1097-4679+x<1::aid>3.0.co;2-k");
    assertThat(OaiRecordMapper.doiOf("https://doi.org/10.5555/a%20b")).isNull();
  }

  @Test
  void languageIsStoredAsIso6391() throws Exception {
    for (String[] c : new String[][] {{"English", "en"}, {"fre", "fr"}, {"ger", "de"}, {"eng", "en"}, {"und", null}}) {
      String xml = OaiPmhClientTest.fixture("pensoft-listrecords-oai_dc.xml")
          .replace("<dc:language>en</dc:language>", "<dc:language>" + c[0] + "</dc:language>");
      var rec = OaiPmhClient.readRecords(xml.getBytes(StandardCharsets.UTF_8), "oai_dc").get(1);
      assertThat(OaiRecordMapper.toHarvested(rec, 7L).orElseThrow().work().language()).as(c[0]).isEqualTo(c[1]);
    }
  }

  @Test
  void declaredLanguageIsTheRawCode() throws Exception {
    String xml = OaiPmhClientTest.fixture("pensoft-listrecords-oai_dc.xml")
        .replace("<dc:language>en</dc:language>", "<dc:language>English</dc:language>");
    var rec = OaiPmhClient.readRecords(xml.getBytes(StandardCharsets.UTF_8), "oai_dc").get(1);
    HarvestedWork hw = OaiRecordMapper.toHarvested(rec, 7L).orElseThrow();
    assertThat(hw.declaredLanguage()).isEqualTo("English");
    assertThat(hw.work().language()).isEqualTo("en");
    assertThat(hw.texts()).isEmpty();
  }

  private static HarvestedWork inline(String prefix, String metadata) throws Exception {
    String xml = "<?xml version=\"1.0\"?><OAI-PMH xmlns=\"http://www.openarchives.org/OAI/2.0/\"><ListRecords>"
        + "<record><header><identifier>oai:x:1</identifier><datestamp>2026-01-01</datestamp></header><metadata>"
        + metadata + "</metadata></record></ListRecords></OAI-PMH>";
    return OaiRecordMapper.toHarvested(OaiPmhClient.readRecords(xml.getBytes(StandardCharsets.UTF_8), prefix).getFirst(),
        7L).orElseThrow();
  }

  private static final String DCNS = "<oai-dc:dc xmlns:oai-dc=\"http://www.openarchives.org/OAI/2.0/oai_dc/\" "
      + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\">";

  @Test
  void parallelTitlesByXmlLang() throws Exception {
    HarvestedWork hw = inline("oai_dc", DCNS
        + "<dc:title xml:lang=\"es\">Dos especies nuevas de cucarachas</dc:title>"
        + "<dc:title xml:lang=\"en\">Two new species of cockroaches</dc:title>"
        + "<dc:description xml:lang=\"es\">Se describen dos especies.</dc:description>"
        + "<dc:description xml:lang=\"en\">Two species are described.</dc:description></oai-dc:dc>");
    assertThat(hw.work().title()).isEqualTo("Dos especies nuevas de cucarachas");
    assertThat(hw.work().abstractText()).isEqualTo("Se describen dos especies.");
    assertThat(hw.titleLang()).isEqualTo("es");
    assertThat(hw.abstractLang()).isEqualTo("es");
    assertThat(hw.texts()).containsExactly(
        new org.catalogueoflife.nametracker.indexer.layer.TextIn("TITLE", "en", "PARALLEL",
            "Two new species of cockroaches"),
        new org.catalogueoflife.nametracker.indexer.layer.TextIn("ABSTRACT", "en", "PARALLEL",
            "Two species are described."));
  }

  @Test
  void untaggedVersionKeptWithoutLanguageForDetection() throws Exception {
    HarvestedWork hw = inline("oai_dc", DCNS + "<dc:title>Main title of the article</dc:title>"
        + "<dc:title>Another title without any language tag</dc:title></oai-dc:dc>");
    assertThat(hw.titleLang()).isNull();
    assertThat(hw.texts()).hasSize(1);
    assertThat(hw.texts().getFirst().lang()).isNull(); // ParallelTexts detects it or drops it
  }

  @Test
  void jatsTransTitleAndAbstract() throws Exception {
    HarvestedWork hw = inline("jats", "<article xmlns=\"https://jats.nlm.nih.gov/publishing/1.1/\" xml:lang=\"es\">"
        + "<front><article-meta><title-group><article-title>Dos especies nuevas</article-title>"
        + "<trans-title-group xml:lang=\"en\"><trans-title>Two new species</trans-title></trans-title-group>"
        + "</title-group><abstract><p>Se describen <italic>dos</italic> especies.</p></abstract>"
        + "<trans-abstract xml:lang=\"en\"><title>Abstract</title><p>Two species are <italic>described</italic>.</p>"
        + "<p>Second paragraph.</p></trans-abstract></article-meta></front></article>");
    assertThat(hw.work().title()).isEqualTo("Dos especies nuevas");
    assertThat(hw.titleLang()).isEqualTo("es");
    assertThat(hw.abstractLang()).isEqualTo("es");
    assertThat(hw.work().abstractJats()).contains("<italic>dos</italic>");
    assertThat(hw.texts()).extracting(t -> t.field() + "|" + t.lang() + "|" + t.kind() + "|" + t.text())
        .containsExactly("TITLE|en|PARALLEL|Two new species",
            "ABSTRACT|en|PARALLEL|Two species are described.\n\nSecond paragraph.");
  }
}
