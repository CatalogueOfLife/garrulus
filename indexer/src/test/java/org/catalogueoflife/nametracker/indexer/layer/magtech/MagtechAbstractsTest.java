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
}
