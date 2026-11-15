package org.catalogueoflife.nametracker.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.nametracker.finder.api.*;
import org.catalogueoflife.nametracker.finder.gnfinder.GnfinderUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@WebMvcTest(FindController.class)
@Import({FinderWebConfig.class, FindControllerTest.Stub.class})
class FindControllerTest {
  static final StubFinder STUB = new StubFinder();

  static class Stub {
    @Bean
    NameFinder nameFinder() {
      return STUB;
    }
  }

  /** Returns the golden result and records what it was asked. */
  static class StubFinder implements NameFinder {
    final FinderResult golden = FinderJson.mapper().readValue(TestFiles.read("contract/find-response.json"), FinderResult.class);
    final List<Document> docs = new ArrayList<>();
    final List<FinderOptions> options = new ArrayList<>();
    RuntimeException failure;

    @Override
    public FinderInfo info() {
      return golden.info();
    }

    @Override
    public FinderResult find(Document doc, FinderOptions opts) {
      if (failure != null) throw failure;
      docs.add(doc);
      options.add(opts);
      return golden;
    }
  }

  /** find-request.json holds the document; the request wraps it. */
  static final String REQUEST = "{\"document\":" + TestFiles.read("contract/find-request.json") + "}";

  @Autowired
  MockMvc mvc;

  @BeforeEach
  void reset() {
    STUB.docs.clear();
    STUB.options.clear();
    STUB.failure = null;
  }

  private static JsonNode tree(String json) {
    return FinderJson.mapper().readTree(json);
  }

  @Test
  void findReturnsExactlyTheGoldenJson() throws Exception {
    MvcResult r = mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn();
    assertThat(tree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))).isEqualTo(tree(TestFiles.read("contract/find-response.json")));
    assertThat(STUB.docs.getFirst().id()).isEqualTo("doc-1");
    assertThat(STUB.options.getFirst()).isEqualTo(FinderOptions.defaults());
  }

  @Test
  void findPassesTheOptionsOn() throws Exception {
    String body = "{\"document\":{\"id\":\"d\",\"blocks\":[]},\"options\":{\"includeRejected\":true,\"codeHint\":\"ZOOLOGICAL\"}}";
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    assertThat(STUB.options.getFirst().includeRejected()).isTrue();
    assertThat(STUB.options.getFirst().codeHint()).isEqualTo(org.gbif.nameparser.api.NomCode.ZOOLOGICAL);
  }

  @Test
  void textIsWrappedAsOneParagraph() throws Exception {
    mvc.perform(post("/v1/find/text").queryParam("language", "de").contentType(MediaType.TEXT_PLAIN).content("Carabus nigrus Müller"))
        .andExpect(status().isOk());
    Document d = STUB.docs.getFirst();
    assertThat(d.id()).isEqualTo("text");
    assertThat(d.language()).isEqualTo("de");
    assertThat(d.blocks()).hasSize(1);
    Block b = d.blocks().getFirst();
    assertThat(b.id()).isEqualTo("b1");
    assertThat(b.type()).isEqualTo(BlockType.PARAGRAPH);
    assertThat(b.text()).isEqualTo("Carabus nigrus Müller");
  }

  @Test
  void textLanguageIsOptional() throws Exception {
    mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isOk());
    assertThat(STUB.docs.getFirst().language()).isNull();
  }

  @Test
  void jatsKeepsTheItalics() throws Exception {
    String xml = "<article><body><sec><title>T</title><p>See <italic>Carabus nigrus</italic> here.</p></sec></body></article>";
    mvc.perform(post("/v1/find/jats").contentType(MediaType.APPLICATION_XML).content(xml)).andExpect(status().isOk());
    Document d = STUB.docs.getFirst();
    assertThat(d.blocks()).anySatisfy(b -> {
      assertThat(b.text()).contains("Carabus nigrus");
      assertThat(b.styles()).isNotEmpty();
    });
  }

  @Test
  void malformedJatsIs400WithAMessage() throws Exception {
    mvc.perform(post("/v1/find/jats").contentType(MediaType.APPLICATION_XML).content("<article><body>"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void jatsEntitiesAreNeverResolved() throws Exception {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE article [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
        + "<article><body><p>Carabus &xxe; nigrus</p></body></article>";
    int status = mvc.perform(post("/v1/find/jats").contentType(MediaType.APPLICATION_XML).content(xml)).andReturn().getResponse().getStatus();
    // either refused or the entity ignored; never resolved
    assertThat(status).isIn(200, 400);
    if (status == 200) {
      assertThat(STUB.docs.getFirst().blocks()).allSatisfy(b -> assertThat(b.text()).doesNotContain("root:"));
      assertThat(STUB.docs.getFirst().blocks().getFirst().text()).isEqualTo("Carabus nigrus");
    }
  }

  @Test
  void utf16JatsIs400() throws Exception {
    byte[] xml = "<article><body/></article>".getBytes(StandardCharsets.UTF_16);
    mvc.perform(post("/v1/find/jats").contentType(MediaType.APPLICATION_XML).content(xml))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("UTF-16")));
  }

  @Test
  void gnfinderDownIs503WithAJsonBody() throws Exception {
    STUB.failure = new GnfinderUnavailableException("gnfinder at http://x/api/v1/find is not available: ConnectException",
        new java.net.ConnectException("refused"));
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(503)).andExpect(jsonPath("$.error").value(ApiErrors.GNFINDER_UNAVAILABLE))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http"))));
  }

  @Test
  void gnfinderErrorStatusIs503() throws Exception {
    STUB.failure = new GnfinderUnavailableException("gnfinder at http://x/api/v1/find returned HTTP 500");
    mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void aWrappedGnfinderFailureIs503() throws Exception {
    STUB.failure = new IllegalStateException("pipeline failed", new GnfinderUnavailableException("gnfinder at x returned HTTP 502"));
    mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value(ApiErrors.GNFINDER_UNAVAILABLE));
  }

  @Test
  void otherIoProblemsAreNot503() {
    // only the dedicated type means "gnfinder is down"; anything else is a server error
    STUB.failure = new UncheckedIOException(new IOException("disk"));
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content("x")))
        .hasRootCauseInstanceOf(IOException.class);
  }

  @Test
  void malformedJsonIs400() throws Exception {
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content("{nope"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void bodiesOver20MbAre413() throws Exception {
    byte[] big = new byte[20 * 1024 * 1024 + 1];
    java.util.Arrays.fill(big, (byte) 'a');
    mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content(big))
        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.status").value(413));
  }

  // ---- size limits and request validation

  /** The pipeline's chunk size: a single block above it would be one oversize gnfinder call and a quadratic pipeline. */
  static final int LIMIT = org.catalogueoflife.nametracker.finder.pipeline.PipelineConfig.defaults().maxChunkCodePoints();

  @Test
  void textAboveTheBlockLimitIs413() throws Exception {
    mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content("a".repeat(LIMIT + 1)))
        .andExpect(status().isPayloadTooLarge()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(413))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("/v1/find")));
    assertThat(STUB.docs).isEmpty();
    // the limit counts code points: an astral character is one, not two
    mvc.perform(post("/v1/find/text").contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
        .content("\uD83D\uDC1B".repeat(LIMIT).getBytes(StandardCharsets.UTF_8))).andExpect(status().isOk());
  }

  private static String document(String... blocks) {
    return "{\"document\":{\"id\":\"d\",\"blocks\":[" + String.join(",", blocks) + "]}}";
  }

  private static String block(String id, String text, String styles) {
    return "{\"id\":" + (id == null ? "null" : "\"" + id + "\"") + ",\"type\":\"PARAGRAPH\",\"level\":0,\"text\":"
        + (text == null ? "null" : "\"" + text + "\"") + ",\"styles\":[" + styles + "],\"sectionPath\":[]}";
  }

  @Test
  void aBlockAboveTheLimitIs413() throws Exception {
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON)
            .content(document(block("b1", "short", ""), block("b2", "a".repeat(LIMIT + 1), ""))))
        .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.status").value(413))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("b2")));
    assertThat(STUB.docs).isEmpty();
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(document(block("b1", "a".repeat(LIMIT), ""))))
        .andExpect(status().isOk());
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidDocuments() {
    String italic = "\"style\":\"ITALIC\"";
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("duplicate block id", document(block("b1", "a", ""), block("b1", "b", "")), "b1"),
        org.junit.jupiter.params.provider.Arguments.of("no block id", document(block(null, "a", "")), "id"),
        org.junit.jupiter.params.provider.Arguments.of("null text", document(block("b1", null, "")), "b1"),
        org.junit.jupiter.params.provider.Arguments.of("span past the end", document(block("b1", "abc", "{\"start\":1,\"end\":4," + italic + "}")), "b1"),
        org.junit.jupiter.params.provider.Arguments.of("negative start", document(block("b1", "abc", "{\"start\":-1,\"end\":2," + italic + "}")), "b1"),
        org.junit.jupiter.params.provider.Arguments.of("start after end", document(block("b1", "abc", "{\"start\":2,\"end\":1," + italic + "}")), "b1"),
        // code points: the emoji is one, so [0, 2) is inside "🐛a" while [0, 3) is not
        org.junit.jupiter.params.provider.Arguments.of("span past the end in code points",
            document(block("b1", "\uD83D\uDC1Ba", "{\"start\":0,\"end\":3," + italic + "}")), "b1"));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.MethodSource("invalidDocuments")
  void invalidDocumentsAre400(String label, String body, String mentioned) throws Exception {
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(mentioned)));
    assertThat(STUB.docs).isEmpty();
  }

  @Test
  void validSpansAreAccepted() throws Exception {
    String body = document(block("b1", "\uD83D\uDC1Ba", "{\"start\":0,\"end\":2,\"style\":\"ITALIC\"},{\"start\":2,\"end\":2,\"style\":\"BOLD\"}"));
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk());
  }

  @Test
  void theServiceBindsToLoopbackByDefault() throws Exception {
    java.util.Map<String, Object> yml;
    try (var in = getClass().getResourceAsStream("/application.yml")) {
      yml = new org.yaml.snakeyaml.Yaml().load(in);
    }
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> server = (java.util.Map<String, Object>) yml.get("server");
    assertThat(server.get("address")).isEqualTo("${FINDER_SERVICE_ADDRESS:127.0.0.1}");
  }

  @Test
  void infoReturnsTheFinderInfo() throws Exception {
    MvcResult r = mvc.perform(get("/v1/info")).andExpect(status().isOk()).andReturn();
    assertThat(tree(r.getResponse().getContentAsString())).isEqualTo(FinderJson.mapper().valueToTree(STUB.golden.info()));
  }

  @Test
  void openapiYamlIsServed() throws Exception {
    MvcResult r = mvc.perform(get("/v1/openapi.yaml")).andExpect(status().isOk()).andReturn();
    assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Garrulus finder API");
    assertThat(r.getResponse().getContentType()).contains("yaml");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theContractDocumentsEveryEndpoint() throws Exception {
    String yaml = mvc.perform(get("/v1/openapi.yaml")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    java.util.Map<String, Object> doc = new org.yaml.snakeyaml.Yaml().load(yaml);
    assertThat(((java.util.Map<String, Object>) doc.get("paths")).keySet())
        .containsExactlyInAnyOrder("/v1/find", "/v1/find/text", "/v1/find/jats", "/v1/openapi.yaml", "/v1/info");
  }
}
