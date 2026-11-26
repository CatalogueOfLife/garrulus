package org.catalogueoflife.garrulus.indexer.content.route;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.catalogueoflife.garrulus.finder.api.BlockType;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.doc.JatsConverter;
import org.catalogueoflife.garrulus.finder.doc.JatsOptions;
import org.catalogueoflife.garrulus.indexer.content.FetchedContent;
import org.catalogueoflife.garrulus.indexer.content.LicencePolicy;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** What the three JATS routes share: "usable XML", the conversion and the licence of the XML. */
final class JatsSupport {

  private static final Logger LOG = LoggerFactory.getLogger(JatsSupport.class);

  /** The first license element: self-closing or with content; {@code license-p} and {@code license_ref} do not match. */
  private static final Pattern LICENSE = Pattern.compile(
      "<(?:[\\w.-]+:)?license(?=[\\s>/])([^>]*?)(?:/>|>(.*?)</(?:[\\w.-]+:)?license\\s*>)", Pattern.DOTALL);
  private static final Pattern ATTR_HREF = Pattern.compile("(?:xlink:)?href\\s*=\\s*[\"']([^\"']*)[\"']");
  private static final Pattern ATTR_TYPE = Pattern.compile("license-type\\s*=\\s*[\"']([^\"']*)[\"']");
  private static final Pattern LICENSE_REF = Pattern.compile(
      "<(?:[\\w.-]+:)?license_ref[^>]*>([^<]*)<", Pattern.DOTALL);
  private static final Pattern CC_HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"']*creativecommons\\.org[^\"']*)[\"']");
  private static final Pattern CC_TEXT = Pattern.compile(
      "\\bCC[\\s_-]*(BY(?:[\\s_-]*(?:NC|ND|SA))*(?:[\\s_-]*\\d\\.\\d)?|0|ZERO)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern CC_NAME = Pattern.compile(
      "Creative\\s+Commons\\s+Attribution(?:[\\s-]*(Non-?Commercial))?(?:[\\s-]*(No-?Derivs?|No\\s+Derivatives?))?"
          + "(?:[\\s-]*(Share\\s*-?Alike))?", Pattern.CASE_INSENSITIVE);

  record Parsed(Document document, @Nullable String license) {
  }

  private JatsSupport() {
  }

  /** The body when the response is a 200 with content, else empty (404 and other 4xx included). */
  static Optional<byte[]> okBody(HttpResponse<byte[]> resp) {
    int s = resp.statusCode();
    if (s < 200 || s >= 300) return Optional.empty();
    byte[] b = resp.body();
    return b == null || b.length == 0 ? Optional.empty() : Optional.of(b);
  }

  /**
   * Converts the body when it is XML with an {@code article} root. HTML, a truncated file or anything the converter
   * rejects (a DOCTYPE with entities, UTF-16) gives empty, logged at INFO with the redacted URL.
   */
  static Optional<Parsed> parse(byte[] body, String docId, URI url) {
    URI safe = PoliteHttp.redact(url);
    if (!isArticle(body)) {
      LOG.info("Not a JATS article, ignoring {}", safe);
      return Optional.empty();
    }
    try {
      Document doc = new JatsConverter().convert(new ByteArrayInputStream(body), docId, JatsOptions.defaults())
          .document();
      return Optional.of(new Parsed(doc, licenseOf(new String(body, StandardCharsets.UTF_8))));
    } catch (RuntimeException e) {
      LOG.info("Unusable XML at {}: {}", safe, e.getMessage());
      return Optional.empty();
    }
  }

  /** True when the first element is {@code article}. A DTD is never loaded; HTML parse errors count as no. */
  static boolean isArticle(byte[] body) {
    XMLInputFactory f = XMLInputFactory.newFactory();
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    f.setXMLResolver((publicId, systemId, baseUri, ns) -> {
      throw new XMLStreamException("External resources are not resolved: " + systemId);
    });
    try {
      f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    } catch (IllegalArgumentException ignored) {
      // not supported by this implementation
    }
    XMLStreamReader r = null;
    try {
      r = f.createXMLStreamReader(new ByteArrayInputStream(body));
      while (r.hasNext()) {
        if (r.next() == XMLStreamConstants.START_ELEMENT) return "article".equals(r.getLocalName());
      }
      return false;
    } catch (XMLStreamException | RuntimeException e) {
      return false;
    } finally {
      if (r != null) {
        try {
          r.close();
        } catch (XMLStreamException ignored) {
          // nothing to do
        }
      }
    }
  }

  /** At least one PARAGRAPH block: the only thing that makes a document full text. */
  static boolean hasParagraph(Document d) {
    return d.blocks().stream().anyMatch(b -> b.type() == BlockType.PARAGRAPH);
  }

  static FetchedContent content(String route, String kind, byte[] raw, Parsed p, @Nullable String license, URI url) {
    return new FetchedContent(route, kind, raw, p.document(), license, PoliteHttp.redact(url).toString(),
        hasParagraph(p.document()));
  }

  /**
   * The normalised licence of the article's first {@code <license>} element, or null. In order: an {@code href}
   * (Pensoft), {@code license-type}, an {@code ali:license_ref} child (PMC), an inner link to creativecommons.org and the
   * wording of the {@code license-p} ("CC BY 4.0", "Creative Commons Attribution License").
   */
  static @Nullable String licenseOf(String xml) {
    Matcher m = LICENSE.matcher(xml);
    if (!m.find()) return null;
    String attrs = m.group(1) == null ? "" : m.group(1);
    String inner = m.group(2) == null ? "" : m.group(2);

    Matcher h = ATTR_HREF.matcher(attrs);
    if (h.find() && h.group(1).contains("creativecommons.org")) return LicencePolicy.normalise(h.group(1));
    Matcher t = ATTR_TYPE.matcher(attrs);
    if (t.find()) {
      String v = fromTypeName(t.group(1));
      if (v != null) return v;
    }
    Matcher ref = LICENSE_REF.matcher(inner);
    if (ref.find() && ref.group(1).contains("creativecommons.org")) return LicencePolicy.normalise(ref.group(1).trim());
    Matcher cc = CC_HREF.matcher(inner);
    if (cc.find()) return LicencePolicy.normalise(cc.group(1));
    String text = inner.replaceAll("<[^>]+>", " ");
    Matcher code = CC_TEXT.matcher(text);
    if (code.find()) return LicencePolicy.normalise("CC " + code.group(1));
    Matcher name = CC_NAME.matcher(text);
    if (name.find()) {
      return "cc-by" + (name.group(1) != null ? "-nc" : "") + (name.group(2) != null ? "-nd" : "")
          + (name.group(3) != null ? "-sa" : "");
    }
    return null;
  }

  private static @Nullable String fromTypeName(String type) {
    String v = type.trim().toLowerCase(Locale.ROOT);
    if (v.startsWith("creative-commons-attribution")) {
      return "cc-by" + (v.contains("noncommercial") ? "-nc" : "") + (v.contains("noderiv") ? "-nd" : "")
          + (v.contains("sharealike") ? "-sa" : "");
    }
    if (v.startsWith("cc")) return LicencePolicy.normalise(v);
    return null;
  }
}
