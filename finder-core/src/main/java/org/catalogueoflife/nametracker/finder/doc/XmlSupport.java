package org.catalogueoflife.nametracker.finder.doc;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;

/** Hardened StAX set up shared by the XML converters: no DTD, no external entities, no UTF-16, unknown entities blanked. */
final class XmlSupport {
  private XmlSupport() {}

  private static XMLInputFactory newFactory() {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    f.setProperty(XMLInputFactory.IS_COALESCING, true);
    f.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    f.setXMLResolver((publicId, systemId, baseUri, ns) -> {
      throw new XMLStreamException("External resources are not resolved: " + systemId);
    });
    try {
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    } catch (IllegalArgumentException ignored) {
      // not supported by this implementation
    }
    return f;
  }

  /** Opens a reader on a fresh factory (XMLInputFactory is not guaranteed to be thread safe and converters run concurrently). */
  static XMLStreamReader open(InputStream xml, String docId, String format) throws IOException, XMLStreamException {
    byte[] bytes = xml.readAllBytes();
    rejectUtf16(bytes, docId, format);
    return newFactory().createXMLStreamReader(new ByteArrayInputStream(blankUnknownEntities(bytes)));
  }

  private static void rejectUtf16(byte[] b, String docId, String format) {
    if (b.length >= 2 && ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF || (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)) {
      throw new IllegalArgumentException("Cannot parse " + format + " XML of " + docId + ": UTF-16 input is not supported, use UTF-8");
    }
  }

  private static final Set<String> PREDEFINED = Set.of("amp", "lt", "gt", "quot", "apos");

  /**
   * Replaces named entity references other than the five predefined ones (e.g. &amp;nbsp; without a DTD) by a space, as the
   * parser would otherwise fail. CDATA sections are copied unchanged. Works on bytes, so it assumes an ASCII compatible
   * encoding (UTF-8, ISO-8859-x); UTF-16 is rejected by {@link #open}.
   */
  static byte[] blankUnknownEntities(byte[] in) {
    byte[] out = new byte[in.length];
    int o = 0;
    byte[] cdata = "<![CDATA[".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    for (int i = 0; i < in.length; i++) {
      if (in[i] == '<' && startsWith(in, i, cdata)) {
        int end = i + cdata.length;
        while (end < in.length && !(in[end] == ']' && startsWith(in, end, new byte[]{']', ']', '>'}))) end++;
        end = Math.min(end + 3, in.length);
        System.arraycopy(in, i, out, o, end - i);
        o += end - i;
        i = end - 1;
        continue;
      }
      if (in[i] == '&' && i + 1 < in.length && Character.isLetter(in[i + 1])) {
        int j = i + 1;
        while (j < in.length && j - i < 40 && (Character.isLetterOrDigit(in[j]) || in[j] == '.' || in[j] == '-' || in[j] == '_')) j++;
        if (j < in.length && in[j] == ';' && !PREDEFINED.contains(new String(in, i + 1, j - i - 1, java.nio.charset.StandardCharsets.ISO_8859_1))) {
          out[o++] = ' ';
          i = j;
          continue;
        }
      }
      out[o++] = in[i];
    }
    return Arrays.copyOf(out, o);
  }

  private static boolean startsWith(byte[] in, int at, byte[] prefix) {
    if (at + prefix.length > in.length) return false;
    for (int k = 0; k < prefix.length; k++) if (in[at + k] != prefix[k]) return false;
    return true;
  }
}
