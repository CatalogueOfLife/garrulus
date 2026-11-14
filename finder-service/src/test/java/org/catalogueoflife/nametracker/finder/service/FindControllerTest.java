package org.catalogueoflife.nametracker.finder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.catalogueoflife.nametracker.finder.api.*;
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
  void utf16JatsIs400() throws Exception {
    byte[] xml = "<article><body/></article>".getBytes(StandardCharsets.UTF_16);
    mvc.perform(post("/v1/find/jats").contentType(MediaType.APPLICATION_XML).content(xml))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("UTF-16")));
  }

  @Test
  void gnfinderDownIs503WithAJsonBody() throws Exception {
    STUB.failure = new UncheckedIOException(new java.net.ConnectException("refused"));
    mvc.perform(post("/v1/find").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
        .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(503)).andExpect(jsonPath("$.error").isNotEmpty());
  }

  @Test
  void gnfinderErrorStatusIs503() throws Exception {
    STUB.failure = new IllegalStateException("gnfinder at http://x/api/v1/find returned HTTP 500");
    mvc.perform(post("/v1/find/text").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isServiceUnavailable());
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
}
