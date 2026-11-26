package org.catalogueoflife.garrulus.indexer.content.institutional;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Predicate;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.doc.TeiConverter;

/**
 * Wiley's text and data mining API: {@code GET https://api.wiley.com/onlinelibrary/tdm/v1/articles/{doi, URL-encoded}}
 * with the institution's {@code Wiley-TDM-Client-Token}, for {@code 10.1002/} and {@code 10.1111/} DOIs. It answers
 * with the article PDF, which the local GROBID turns into TEI, read like the OpenAlex TEI (line-break hyphens undone
 * with the COL dictionary). This is a TDM API, not a PDF downloaded from an article page.
 */
final class WileyTdm implements PublisherTdm {

  static final String PUBLISHER = "WILEY";
  static final String API = "WILEY_TDM";
  static final String KIND = "TEI";
  private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

  private final String base;
  private final Map<String, String> headers;
  private final GrobidPdfClient grobid;
  private final Predicate<String> isKnownWord;

  WileyTdm(String baseUrl, String token, GrobidPdfClient grobid, Predicate<String> isKnownWord) {
    this.base = baseUrl;
    this.headers = Map.of("Accept", "application/pdf", "Wiley-TDM-Client-Token", token);
    this.grobid = grobid;
    this.isKnownWord = isKnownWord;
  }

  @Override
  public String publisher() {
    return PUBLISHER;
  }

  @Override
  public String api() {
    return API;
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public boolean claims(String doi) {
    return (doi.startsWith("10.1002/") || doi.startsWith("10.1111/")) && doi.length() > "10.1002/".length();
  }

  @Override
  public URI url(String doi) {
    return URI.create(base + "/onlinelibrary/tdm/v1/articles/"
        + URLEncoder.encode(doi, StandardCharsets.UTF_8).replace("+", "%20"));
  }

  @Override
  public Map<String, String> headers() {
    return headers;
  }

  @Override
  public Document convert(byte[] body, String docId) {
    if (!isPdf(body)) throw new IllegalArgumentException("Not a PDF");
    byte[] tei = grobid.processFulltext(body);
    return new TeiConverter().convert(new ByteArrayInputStream(tei), docId, isKnownWord);
  }

  private static boolean isPdf(byte[] b) {
    if (b.length < PDF_MAGIC.length) return false;
    for (int i = 0; i < PDF_MAGIC.length; i++) {
      if (b[i] != PDF_MAGIC[i]) return false;
    }
    return true;
  }
}
