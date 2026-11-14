package org.catalogueoflife.nametracker.finder.gnfinder;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.BlockType;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import org.catalogueoflife.nametracker.finder.pipeline.Candidate;
import org.catalogueoflife.nametracker.finder.pipeline.GenerationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class GnfinderGeneratorTest {
  @RegisterExtension
  static final WireMockExtension WM = WireMockExtension.newInstance().build();

  private static final String UMLAUT = "Über die Käfer: Carabus nigrus";

  private static String fixture(String name) throws IOException {
    try (var in = GnfinderGeneratorTest.class.getResourceAsStream("/gnfinder/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static GnfinderClient client() {
    return new GnfinderClient(URI.create(WM.baseUrl()), HttpClient.newHttpClient());
  }

  private static Block block(String id, String text) {
    return new Block(id, BlockType.PARAGRAPH, 0, text, List.of(), List.of());
  }

  private static List<Candidate> generate(GnfinderGenerator g, Document d) {
    return g.generate(d, FlatText.of(d), new GenerationContext(null));
  }

  @Test
  void largeDocumentIsChunkedAtBlockBoundaries() throws Exception {
    String json = fixture("umlaut.json");
    // the first request carries blocks b1 and b2, the second one block b3
    WM.stubFor(post(urlEqualTo("/api/v1/find")).atPriority(5).willReturn(aResponse().withBody(json)));
    WM.stubFor(post(urlEqualTo("/api/v1/find")).atPriority(1).withRequestBody(matchingJsonPath("$.text", containing("Plain text.")))
        .willReturn(aResponse().withBody(json)));
    Document d = new Document("d", "en", List.of(block("b1", UMLAUT), block("b2", "Plain text."), block("b3", UMLAUT)), Map.of());
    List<Candidate> cands = generate(new GnfinderGenerator(client(), 45), d);
    WM.verify(2, postRequestedFor(urlEqualTo("/api/v1/find")));
    assertThat(cands).hasSize(2);
    assertThat(cands).extracting(Candidate::blockId).containsExactly("b1", "b3");
    assertThat(cands).extracting(Candidate::start).containsExactly(16, 16);
    assertThat(cands).extracting(Candidate::end).containsExactly(30, 30);
    Candidate c = cands.get(1);
    assertThat(c.verbatim()).isEqualTo("Carabus nigrus");
    assertThat(c.name()).isEqualTo("Carabus nigrus");
    assertThat(c.generator()).isEqualTo(Generator.GNFINDER);
    assertThat(c.features()).containsEntry("cardinality", 2).containsEntry("annotationNomenType", "NO_ANNOT");
    assertThat((Double) c.features().get("oddsLog10")).isGreaterThan(10);
  }

  @Test
  void sendsTheConfiguredRequestAndUserAgent() throws Exception {
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody(fixture("umlaut.json"))));
    generate(new GnfinderGenerator(client(), 100_000), new Document("d", "en", List.of(block("b1", UMLAUT)), Map.of()));
    WM.verify(postRequestedFor(urlEqualTo("/api/v1/find"))
        .withRequestBody(equalToJson("{\"text\":\"" + UMLAUT + "\",\"ambiguousNames\":true,\"oddsDetails\":true,\"language\":\"detect\","
            + "\"verification\":false,\"wordsAround\":0}"))
        .withHeader("User-Agent", containing("Garrulus/"))
        .withHeader("User-Agent", containing("(+https://www.catalogueoflife.org)")));
  }

  @Test
  void spansAreTrimmedAndSpansAcrossBlocksDropped() throws Exception {
    // sample.json: "(Coleoptera:" is at 25-37 and gets trimmed to 26-36; offsets are the same in a single block
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody(fixture("sample.json"))));
    String text = "A new species of Carabus (Coleoptera: Carabidae), Carabus nigrus sp. nov., is described. C. nigrus differs from Pterostichus niger.";
    List<Candidate> cands = generate(new GnfinderGenerator(client(), 100_000), new Document("d", "en", List.of(block("b1", text)), Map.of()));
    assertThat(cands).extracting(Candidate::verbatim)
        .containsExactly("Carabus", "Coleoptera", "Carabidae", "Carabus nigrus", "C. nigrus", "Pterostichus niger");
    Candidate col = cands.get(1);
    assertThat(col.start()).isEqualTo(26);
    assertThat(col.end()).isEqualTo(36);
    assertThat(col.name()).isEqualTo("Coleoptera");
    assertThat(col.features()).containsEntry("annotationNomenType", "SP_NOV");

  }

  @Test
  void spansAcrossBlocksAreDropped() {
    String json = "{\"names\":[{\"cardinality\":1,\"verbatim\":\"Carabus\",\"name\":\"Carabus\",\"oddsLog10\":3.0,\"start\":0,\"end\":7,"
        + "\"annotationNomenType\":\"NO_ANNOT\"},{\"cardinality\":2,\"verbatim\":\"Carabus\\n\\nnigrus\",\"name\":\"Carabus nigrus\","
        + "\"oddsLog10\":3.0,\"start\":0,\"end\":15,\"annotationNomenType\":\"NO_ANNOT\"}]}";
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody(json)));
    Document d = new Document("d", "en", List.of(block("b1", "Carabus"), block("b2", "nigrus")), Map.of());
    List<Candidate> cands = generate(new GnfinderGenerator(client(), 100_000), d);
    assertThat(cands).hasSize(1);
    assertThat(cands.get(0).verbatim()).isEqualTo("Carabus");
  }

  @Test
  void leadingByteOrderMarkShiftsGnfinderOffsets() throws Exception {
    // gnfinder strips a leading BOM and counts from the next character, so the recorded offsets (16-30) belong to the text without it
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody(fixture("umlaut.json"))));
    GnfinderGenerator g = new GnfinderGenerator(client(), 100_000);
    List<Candidate> cands = generate(g, new Document("d", "en", List.of(block("b1", "\uFEFF" + UMLAUT)), Map.of()));
    assertThat(cands).hasSize(1);
    assertThat(cands.get(0).start()).isEqualTo(17);
    assertThat(cands.get(0).end()).isEqualTo(31);
    assertThat(cands.get(0).verbatim()).isEqualTo("Carabus nigrus");
    assertThat(g.misaligned()).isZero();
    WM.verify(postRequestedFor(urlEqualTo("/api/v1/find")).withRequestBody(matchingJsonPath("$.text", equalTo(UMLAUT))));
  }

  @Test
  void namesWhoseOffsetsDoNotPointAtTheirTextAreDropped() throws Exception {
    // the recorded umlaut offsets applied to a text that does not line up
    WM.stubFor(post(urlEqualTo("/api/v1/find")).willReturn(aResponse().withBody(fixture("umlaut.json"))));
    GnfinderGenerator g = new GnfinderGenerator(client(), 100_000);
    assertThat(generate(g, new Document("d", "en", List.of(block("b1", "X" + UMLAUT)), Map.of()))).isEmpty();
    assertThat(g.misaligned()).isEqualTo(1);
  }
}
