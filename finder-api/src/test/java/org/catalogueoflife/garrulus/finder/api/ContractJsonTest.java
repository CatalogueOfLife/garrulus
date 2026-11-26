package org.catalogueoflife.garrulus.finder.api;

import org.gbif.nameparser.api.Rank;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContractJsonTest {
  private static final ObjectMapper M = FinderJson.mapper();

  private static String resource(String name) throws IOException {
    try (InputStream in = ContractJsonTest.class.getResourceAsStream("/contract/" + name)) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void goldenResponseRoundTrips() throws Exception {
    String json = resource("find-response.json");
    FinderResult r = M.readValue(json, FinderResult.class);
    assertThat(r.mentions()).hasSize(2);
    assertThat(r.mentions().get(0).evidence().generators())
        .containsExactlyInAnyOrder(Generator.GNFINDER, Generator.DICTIONARY);
    assertThat(r.mentions().get(0).rank()).isEqualTo(Rank.SPECIES);
    JsonNode expected = M.readTree(json);
    JsonNode actual = M.readTree(M.writeValueAsString(r));
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void goldenOffsetsSliceTheDocument() throws Exception {
    Document doc = M.readValue(resource("find-request.json"), Document.class);
    FinderResult r = M.readValue(resource("find-response.json"), FinderResult.class);
    for (Mention m : r.mentions()) {
      Block b = doc.blocks().stream().filter(x -> x.id().equals(m.blockId())).findFirst().orElseThrow();
      assertThat(CodePoints.slice(b.text(), m.start(), m.end())).isEqualTo(m.verbatim());
      if (m.actId() != null) {
        assertThat(r.acts()).anyMatch(a -> a.id().equals(m.actId()) && a.mentionId().equals(m.id()));
      }
      if (m.contextId() != null) {
        assertThat(r.contexts()).anyMatch(c -> c.id().equals(m.contextId()));
      }
    }
    for (Act a : r.acts()) {
      assertThat(r.mentions()).anyMatch(m -> m.id().equals(a.mentionId()));
    }
    for (ClassificationContext c : r.contexts()) {
      assertThat(doc.blocks()).anyMatch(b -> b.id().equals(c.blockId()));
    }
  }

  @Test
  void unknownPropertiesAreIgnored() throws Exception {
    JsonNode tree = M.readTree(resource("find-response.json"));
    ((tools.jackson.databind.node.ObjectNode) tree).put("future", 1);
    FinderResult r = M.treeToValue(tree, FinderResult.class);
    assertThat(r.mentions()).hasSize(2);
  }

  @Test
  void nullsAreOmitted() throws Exception {
    Mention m = new Mention("m", "b", 0, 3, "abc", "Abc", NameKind.UNINOMIAL, null, null, null, 0.5,
        new Evidence(Set.of(Generator.ITALIC), null, null), null, null, null);
    String json = M.writeValueAsString(m);
    assertThat(json).doesNotContain("authorship").doesNotContain("rank").doesNotContain("null");
    assertThat(m.hints()).isEmpty();
  }

  @Test
  void listsAreNeverNull() {
    Document d = new Document("d", null, null, null);
    assertThat(d.blocks()).isEmpty();
    assertThat(d.meta()).isEmpty();
    FinderResult r = new FinderResult(new FinderInfo("x", "1", "h", null), null, null, null, null, null);
    assertThat(r.mentions()).isEmpty();
    assertThat(r.acts()).isEmpty();
    assertThat(r.contexts()).isEmpty();
    assertThat(r.rejected()).isEmpty();
    assertThat(r.diagnostics()).isEmpty();
    Block b = new Block("b", BlockType.PARAGRAPH, 0, "t", null, null);
    assertThat(b.styles()).isEmpty();
    assertThat(b.sectionPath()).isEmpty();
    assertThat(FinderOptions.defaults().includeRejected()).isFalse();
    assertThat(List.of()).isEmpty();
    assertThat(Map.of()).isEmpty();
  }
}
