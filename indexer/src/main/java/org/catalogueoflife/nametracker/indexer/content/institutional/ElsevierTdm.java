package org.catalogueoflife.nametracker.indexer.content.institutional;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.jspecify.annotations.Nullable;

/**
 * Elsevier's Article Retrieval API for text and data mining: {@code GET
 * https://api.elsevier.com/content/article/doi/{doi}?httpAccept=text/xml} with the API key ({@code X-ELS-APIKey}) and,
 * outside the KU IP range, the institutional token ({@code X-ELS-Insttoken}). It serves {@code 10.1016/} DOIs; the
 * answer is Elsevier's own XML ({@link ElsevierXml}). For an article the institution is not entitled to it answers the
 * metadata and abstract only, which is no full text.
 */
final class ElsevierTdm implements PublisherTdm {

  static final String PUBLISHER = "ELSEVIER";
  static final String API = "ELSEVIER_TDM";
  static final String KIND = "ELSEVIER_XML";
  static final String PREFIX = "10.1016/";

  private final String base;
  private final Map<String, String> headers;

  ElsevierTdm(String baseUrl, String apiKey, @Nullable String insttoken) {
    this.base = baseUrl;
    Map<String, String> h = new LinkedHashMap<>();
    h.put("Accept", "text/xml");
    h.put("X-ELS-APIKey", apiKey);
    if (insttoken != null) h.put("X-ELS-Insttoken", insttoken);
    this.headers = Map.copyOf(h);
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
    return doi.startsWith(PREFIX) && doi.length() > PREFIX.length();
  }

  /** The DOI is a path: each part encoded, its slashes kept. */
  @Override
  public URI url(String doi) {
    String path = URLEncoder.encode(doi, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    return URI.create(base + "/content/article/doi/" + path + "?httpAccept=text/xml");
  }

  @Override
  public Map<String, String> headers() {
    return headers;
  }

  @Override
  public Document convert(byte[] body, String docId) {
    return ElsevierXml.toDocument(body, docId);
  }
}
