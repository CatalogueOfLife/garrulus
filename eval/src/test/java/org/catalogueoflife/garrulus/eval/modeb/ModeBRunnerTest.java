package org.catalogueoflife.garrulus.eval.modeb;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.catalogueoflife.garrulus.eval.corpus.GoldDocument;
import org.catalogueoflife.garrulus.eval.corpus.GoldName;
import org.catalogueoflife.garrulus.finder.api.Block;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class ModeBRunnerTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
  /** A line-break hyphen, as GROBID leaves it when the PDF has one inside a word. */
  private static final String TEI = "<TEI xmlns=\"http://www.tei-c.org/ns/1.0\"><teiHeader><fileDesc><titleStmt>"
      + "<title level=\"a\" type=\"main\">On Pterostichus niger</title></titleStmt></fileDesc></teiHeader>"
      + "<text><body><div><p>We found Pterosti-\nchus niger and north-\neast.</p></div>"
      + "<div type=\"references\"><listBibl><biblStruct><analytic><title>Abax ater</title></analytic></biblStruct></listBibl></div>"
      + "</body></text></TEI>";

  private static GoldDocument gold(String id) {
    Document d = new Document(id, "en", List.of(new Block("b1", BlockType.PARAGRAPH, 0, "Pterostichus niger", List.of(), List.of())),
        Map.of("doi", "10.3897/zookeys.1.1"));
    return new GoldDocument(id, "pensoft", "test", "CC-BY", true, GoldDocument.textHash(d), d,
        List.of(new GoldName("b1", 0, 18, "Pterostichus niger", null, null)), List.of(), List.of(), Set.of());
  }

  private static ModeBRunner runner(List<String> log) {
    HttpClient http = PdfDownloader.defaultClient();
    PdfDownloader dl = new PdfDownloader(http, 0, Duration.ZERO);
    return new ModeBRunner(ModeBRunner.PdfSource.of(dl, g -> WM.baseUrl() + "/pdf/" + g.docId()),
        new GrobidClient(URI.create(WM.baseUrl()), http, d -> { }), w -> w.equals("pterostichus"), 2, log::add);
  }

  /** Records the documents it is given. */
  private static final class Recorder implements NameFinder {
    final List<Document> seen = new ArrayList<>();

    @Override
    public FinderInfo info() {
      return new FinderInfo("rec", "1", "h", null);
    }

    @Override
    public synchronized FinderResult find(Document doc, FinderOptions opts) {
      seen.add(doc);
      return new FinderResult(info(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }
  }

  @Test
  void convertsThePdfThroughGrobidWithDehyphenationAndCachesBoth(@TempDir Path cache) throws Exception {
    WM.stubFor(get(urlEqualTo("/pdf/d1")).willReturn(aResponse().withHeader("Content-Type", "application/pdf").withBody(PDF)));
    WM.stubFor(post(urlEqualTo("/api/processFulltextDocument")).willReturn(aResponse().withBody(TEI)));
    List<String> log = Collections.synchronizedList(new ArrayList<>()); // filled from the runner's pool threads
    Recorder finder = new Recorder();

    Map<String, FinderResult> results = runner(log).run(List.of(gold("d1")), finder, cache);

    assertThat(results).containsOnlyKeys("d1");
    Document converted = finder.seen.get(0);
    assertThat(converted.id()).isEqualTo("d1");
    assertThat(converted.blocks()).extracting(Block::type).containsExactly(BlockType.TITLE, BlockType.PARAGRAPH, BlockType.REFERENCE);
    // joined where the dictionary knows the word, a real hyphen kept elsewhere
    assertThat(converted.blocks().get(1).text()).isEqualTo("We found Pterostichus niger and north-east.");
    assertThat(converted.blocks()).allMatch(b -> b.styles().isEmpty());
    assertThat(cache.resolve("d1.pdf")).hasBinaryContent(PDF);
    assertThat(Files.readString(cache.resolve("d1.tei.xml"))).isEqualTo(TEI);
    assertThat(Files.readString(cache.resolve("d1.pdf.url")).strip()).isEqualTo(WM.baseUrl() + "/pdf/d1");

    // a second run takes everything from the cache
    WM.resetRequests();
    runner(log).run(List.of(gold("d1")), new Recorder(), cache);
    WM.verify(0, getRequestedFor(urlEqualTo("/pdf/d1")));
    WM.verify(0, postRequestedFor(urlEqualTo("/api/processFulltextDocument")));
  }

  @Test
  void aCachedPdfIsNotDownloadedAgain(@TempDir Path cache) throws Exception {
    Files.write(cache.resolve("d1.pdf"), PDF);
    WM.stubFor(post(urlEqualTo("/api/processFulltextDocument")).willReturn(aResponse().withBody(TEI)));
    ModeBRunner.Converted c = runner(Collections.synchronizedList(new ArrayList<>())).convert(List.of(gold("d1")), cache);
    assertThat(c.documents()).containsOnlyKeys("d1");
    assertThat(c.sources()).containsEntry("d1", "unknown");
    WM.verify(0, getRequestedFor(urlEqualTo("/pdf/d1")));
  }

  @Test
  void failuresAreSkippedAndCountedNotFatal(@TempDir Path cache) {
    WM.stubFor(get(urlEqualTo("/pdf/ok")).willReturn(aResponse().withBody(PDF)));
    WM.stubFor(get(urlEqualTo("/pdf/gone")).willReturn(aResponse().withStatus(404)));
    WM.stubFor(get(urlEqualTo("/pdf/html")).willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<html>login</html>")));
    WM.stubFor(get(urlEqualTo("/pdf/cut")).willReturn(aResponse().withBody("%PDF-1.4 and then nothing")));
    WM.stubFor(post(urlEqualTo("/api/processFulltextDocument")).willReturn(aResponse().withBody(TEI)));
    List<String> log = Collections.synchronizedList(new ArrayList<>()); // filled from the runner's pool threads
    List<GoldDocument> docs = List.of(gold("ok"), gold("gone"), gold("html"), gold("cut"));

    ModeBRunner.Converted c = runner(log).convert(docs, cache);

    assertThat(c.documents()).containsOnlyKeys("ok");
    assertThat(c.failures()).containsOnlyKeys("gone", "html", "cut");
    assertThat(c.failures().get("gone")).contains("HTTP 404");
    assertThat(c.failures().get("html")).contains("Not a PDF");
    assertThat(c.failures().get("cut")).contains("Incomplete PDF").contains("after one retry");
    assertThat(c.scored(docs)).extracting(GoldDocument::docId).containsExactly("ok");
    assertThat(log).anyMatch(l -> l.startsWith("skipping gone"));
    // nothing half written stays in the cache
    assertThat(cache.resolve("cut.pdf")).doesNotExist();
    assertThat(cache.toFile().list()).allMatch(n -> n.startsWith("ok.")).contains("ok.pdf", "ok.pdf.url", "ok.tei.xml");
  }

  @Test
  void grobidFailureSkipsTheDocument(@TempDir Path cache) {
    WM.stubFor(get(urlEqualTo("/pdf/d1")).willReturn(aResponse().withBody(PDF)));
    WM.stubFor(post(urlEqualTo("/api/processFulltextDocument")).willReturn(aResponse().withStatus(500).withBody("pdfalto failed")));
    Map<String, String> failed = runner(Collections.synchronizedList(new ArrayList<>())).prefetch(List.of(gold("d1")), cache);
    assertThat(failed).containsOnlyKeys("d1");
    assertThat(failed.get("d1")).contains("GrobidException").contains("500");
    // the PDF stays cached, so the next run only needs GROBID
    assertThat(cache.resolve("d1.pdf")).exists();
    assertThat(cache.resolve("d1.tei.xml")).doesNotExist();
  }

  @Test
  void pmcCopyFirstThenPensoft(@TempDir Path cache) throws Exception {
    // a PMC copy for d1 only; d2 is not in PMC, d3's PMC copy fails to download
    WM.stubFor(get(urlEqualTo("/pmc/PMC1.1/PMC1.1.pdf")).willReturn(aResponse().withBody(PDF)));
    WM.stubFor(get(urlEqualTo("/pmc/PMC3.1/PMC3.1.pdf")).willReturn(aResponse().withStatus(404)));
    HttpClient http = PdfDownloader.defaultClient();
    PdfDownloader dl = new PdfDownloader(http, 0, Duration.ZERO);
    PmcPdfLocator pmc = new PmcPdfLocator(http) {
      @Override
      public java.util.Optional<String> pdfUrl(String doi) {
        return switch (doi) {
          case "10.3897/zookeys.1.1" -> java.util.Optional.of(WM.baseUrl() + "/pmc/PMC1.1/PMC1.1.pdf");
          case "10.3897/zookeys.3.3" -> java.util.Optional.of(WM.baseUrl() + "/pmc/PMC3.1/PMC3.1.pdf");
          default -> java.util.Optional.empty();
        };
      }
    };
    List<String> log = Collections.synchronizedList(new ArrayList<>()); // filled from the runner's pool threads
    ModeBRunner.PdfSource src = ModeBRunner.PdfSource.pmcThenPensoft(dl, pmc, log::add);
    assertThat(src.fetch(gold("d1")).url()).isEqualTo(WM.baseUrl() + "/pmc/PMC1.1/PMC1.1.pdf");
    // the Pensoft fallback is a real pensoft.net URL; only check that it is chosen, without downloading it
    ModeBRunner.PdfSource noDownload = ModeBRunner.PdfSource.pmcThenPensoft(new PdfDownloader(http, 0, Duration.ZERO) {
      @Override
      public byte[] download(String url) throws java.io.IOException, InterruptedException {
        if (url.contains("pensoft.net")) return PDF;
        return super.download(url);
      }
    }, pmc, log::add);
    assertThat(noDownload.fetch(goldWithDoi("d2", "10.3897/zookeys.2.2")).url()).isEqualTo("https://zookeys.pensoft.net/article/2/download/pdf/");
    assertThat(noDownload.fetch(goldWithDoi("d3", "10.3897/zookeys.3.3")).url()).isEqualTo("https://zookeys.pensoft.net/article/3/download/pdf/");
    assertThat(log).anyMatch(l -> l.contains("d3") && l.contains("trying Pensoft"));
  }

  private static GoldDocument goldWithDoi(String id, String doi) {
    GoldDocument g = gold(id);
    Document d = new Document(id, "en", g.document().blocks(), Map.of("doi", doi));
    return new GoldDocument(id, "pensoft", "test", "CC-BY", true, GoldDocument.textHash(d), d, g.names(), List.of(), List.of(), Set.of());
  }

  @Test
  void pensoftPdfUrlComesFromTheDoi() {
    assertThat(ModeBRunner.pensoftPdfUrl(gold("x"))).isEqualTo("https://zookeys.pensoft.net/article/1/download/pdf/");
    assertThat(ModeBRunner.fileName("pensoft:bdj.12.e130385")).isEqualTo("pensoft_bdj.12.e130385");
  }
}
