package org.catalogueoflife.nametracker.indexer.layer.magtech;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MagtechAbstractsTest {

  @Test
  void italicsBecomeJatsItalic() {
    String jats = MagtechAbstracts.toJats(
        "<p id=\"p1\">The plant <i>Petrocosmea qinlingensis</i> and <em>P.</em> sp. H<sub>2</sub>O (<i>P</i> &lt; 0.05) &amp; more</p>");
    assertThat(jats).isEqualTo("<jats:p>The plant <jats:italic>Petrocosmea qinlingensis</jats:italic> and "
        + "<jats:italic>P.</jats:italic> sp. H<jats:sub>2</jats:sub>O (<jats:italic>P</jats:italic> &lt; 0.05) &amp; more</jats:p>");
  }

  @Test
  void strongLabelKeptAsText() {
    String jats = MagtechAbstracts.toJats("<p><strong>Aims:</strong> To test.</p> <p><b>Methods:</b> <span>Plots</span>&nbsp;were.</p>");
    assertThat(jats).isEqualTo("<jats:p>Aims: To test.</jats:p><jats:p>Methods: Plots were.</jats:p>");
  }

  @Test
  void unbalancedHtmlGivesWellFormedJats() throws Exception {
    for (String html : new String[] {"<p>a<p>b", "<p>a <i>b", "<p>a</i> b</p>", "<p><i>a<sub>b</i>c</sub></p>",
        "no paragraph <i>x</i>", "<p>a</p></p></p>", "<p>x < y & z</p>"}) {
      String jats = MagtechAbstracts.toJats(html);
      org.catalogueoflife.nametracker.indexer.layer.oai.OaiPmhClient.parse(
          ("<r xmlns:jats=\"http://jats.nlm.nih.gov\">" + jats + "</r>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var doc = org.catalogueoflife.nametracker.finder.doc.AbstractConverter.fromParts("w:1", "T", null, jats);
      assertThat(doc.blocks()).as(html).isNotEmpty();
    }
    assertThat(MagtechAbstracts.toJats("<p>a<p>b")).isEqualTo("<jats:p>a</jats:p><jats:p>b</jats:p>");
    assertThat(MagtechAbstracts.toJats("<p>a <i>b")).isEqualTo("<jats:p>a <jats:italic>b</jats:italic></jats:p>");
    assertThat(MagtechAbstracts.toJats("<p>a</i> b</p>")).isEqualTo("<jats:p>a b</jats:p>");
  }

  @Test
  void blockTagsBecomeSpacesAndEntitiesDecodeFully() {
    assertThat(MagtechAbstracts.toJats("<p>a<br>b<div>c</div><ul><li>d</li><li>e</li></ul></p>"))
        .isEqualTo("<jats:p>a b c d e </jats:p>");
    assertThat(MagtechAbstracts.toJats("<p>&Aacute;lvarez &eacute;t &#946;</p>")).isEqualTo("<jats:p>\u00c1lvarez \u00e9t \u03b2</jats:p>");
  }
}
