package org.catalogueoflife.nametracker.web.feed;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.jspecify.annotations.Nullable;

/** Writes Atom 1.0 with the JDK's StAX. All text is plain text (type="text"): markup-like titles come out escaped. */
final class AtomWriter {

  static final String NS = "http://www.w3.org/2005/Atom";
  static final Instant EPOCH = Instant.EPOCH;

  /** {@code id} is the feed's own URL; {@code updated} null (an empty feed) writes the epoch. */
  record Feed(String id, String title, @Nullable Instant updated, List<Entry> entries) {}

  /** {@code doiUrl} may be null; {@code summary} is plain text. */
  record Entry(String id, String title, String link, @Nullable String doiUrl, Instant updated, String summary) {}

  private AtomWriter() {}

  static byte[] write(Feed feed) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      XMLStreamWriter w = XMLOutputFactory.newFactory().createXMLStreamWriter(out, "UTF-8");
      w.writeStartDocument("UTF-8", "1.0");
      w.writeStartElement("feed");
      w.writeDefaultNamespace(NS);
      text(w, "title", feed.title());
      text(w, "id", feed.id());
      link(w, "self", feed.id());
      text(w, "updated", (feed.updated() == null ? EPOCH : feed.updated()).toString());
      w.writeStartElement("author");
      text(w, "name", "Garrulus");
      w.writeEndElement();
      for (Entry e : feed.entries()) {
        w.writeStartElement("entry");
        text(w, "id", e.id());
        text(w, "title", e.title());
        link(w, "alternate", e.link());
        if (e.doiUrl() != null) {
          link(w, "related", e.doiUrl());
        }
        text(w, "updated", e.updated().toString());
        w.writeStartElement("summary");
        w.writeAttribute("type", "text");
        w.writeCharacters(clean(e.summary()));
        w.writeEndElement();
        w.writeEndElement();
      }
      w.writeEndElement();
      w.writeEndDocument();
      w.close();
    } catch (XMLStreamException e) {
      throw new IllegalStateException(e);
    }
    return out.toByteArray();
  }

  private static void text(XMLStreamWriter w, String name, String value) throws XMLStreamException {
    w.writeStartElement(name);
    w.writeCharacters(clean(value));
    w.writeEndElement();
  }

  private static void link(XMLStreamWriter w, String rel, String href) throws XMLStreamException {
    w.writeEmptyElement("link");
    w.writeAttribute("rel", rel);
    w.writeAttribute("href", clean(href));
  }

  /** Drops what XML 1.0 cannot carry (control characters, lone surrogates). */
  static String clean(String s) {
    StringBuilder b = new StringBuilder(s.length());
    s.codePoints().filter(c -> c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF)
        || (c >= 0xE000 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0x10FFFF)).forEach(b::appendCodePoint);
    return b.toString();
  }
}
