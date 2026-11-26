package org.catalogueoflife.garrulus.web.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

class AtomWriterTest {

  static final String NS = "http://www.w3.org/2005/Atom";

  @Test
  void atomIsWellFormedAndEscapes() throws Exception {
    AtomWriter.Entry e = new AtomWriter.Entry("http://x/work/1", "A <i>Aus bus</i> & the \"rest\"", "http://x/work/1",
        "https://doi.org/10.1/a&b", Instant.parse("2026-11-01T10:00:00Z"), "Journal <b>\nnew: Aus bus sp. nov.\u0001");
    byte[] xml = AtomWriter.write(new AtomWriter.Feed("http://x/feeds/a.atom", "Garrulus: Aus <bus>",
        Instant.parse("2026-11-01T10:00:00Z"), List.of(e)));
    String s = new String(xml, java.nio.charset.StandardCharsets.UTF_8);
    assertThat(s).contains("&lt;i&gt;Aus bus&lt;/i&gt; &amp; the");
    assertThat(s).doesNotContain("<i>");
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    assertThat(d.getDocumentElement().getNamespaceURI()).isEqualTo(NS);
    assertThat(d.getDocumentElement().getLocalName()).isEqualTo("feed");
    NodeList titles = d.getElementsByTagNameNS(NS, "title");
    assertThat(titles.item(0).getTextContent()).isEqualTo("Garrulus: Aus <bus>");
    assertThat(titles.item(1).getTextContent()).isEqualTo("A <i>Aus bus</i> & the \"rest\"");
    assertThat(d.getElementsByTagNameNS(NS, "name").item(0).getTextContent()).isEqualTo("Garrulus");
    assertThat(d.getElementsByTagNameNS(NS, "summary").item(0).getTextContent()).contains("new: Aus bus sp. nov.");
  }

  @Test
  void emptyFeedHasEpochUpdated() throws Exception {
    byte[] xml = AtomWriter.write(new AtomWriter.Feed("http://x/f", "Garrulus: none", null, List.of()));
    assertThat(new String(xml, java.nio.charset.StandardCharsets.UTF_8)).contains("<updated>1970-01-01T00:00:00Z</updated>");
  }
}
