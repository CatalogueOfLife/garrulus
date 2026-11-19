package org.catalogueoflife.nametracker.indexer.layer.oai;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.catalogueoflife.nametracker.indexer.http.HttpProblem;
import org.catalogueoflife.nametracker.indexer.http.PoliteHttp;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** OAI-PMH 2.0 client for {@code ListRecords}, {@code Identify} and {@code ListSets}, through {@link PoliteHttp}. */
public class OaiPmhClient {

  static final String NS = "http://www.openarchives.org/OAI/2.0/";

  /** One page of records and the resumption token of the next page (null on the last page). */
  public record OaiPage(List<OaiRecord> records, @Nullable String token) {
  }

  public record Identify(String repositoryName, String baseUrl, String granularity, String earliestDatestamp) {
  }

  /** The repository no longer knows the resumption token (it expired, or the repository was restarted). */
  public static class BadResumptionTokenException extends RuntimeException {
    public BadResumptionTokenException(String message) {
      super(message);
    }
  }

  private final PoliteHttp http;

  public OaiPmhClient(PoliteHttp http) {
    this.http = http;
  }

  /**
   * One page of {@code ListRecords}. With a token only the token is sent, else the prefix and the optional set and
   * dates. A repository without matching records gives an empty page.
   */
  public OaiPage listRecords(URI base, String prefix, @Nullable String set, @Nullable LocalDate from,
      @Nullable LocalDate until, @Nullable String token) {
    Map<String, String> q = new LinkedHashMap<>();
    q.put("verb", "ListRecords");
    if (token != null) {
      q.put("resumptionToken", token);
    } else {
      q.put("metadataPrefix", prefix);
      if (set != null && !set.isBlank()) q.put("set", set);
      if (from != null) q.put("from", from.toString());
      if (until != null) q.put("until", until.toString());
    }
    Element root = request(base, q);
    Element lr = child(root, "ListRecords");
    if (lr == null) return new OaiPage(List.of(), null);
    List<OaiRecord> records = new ArrayList<>();
    for (Element r : children(lr, "record")) records.add(record(r, prefix));
    return new OaiPage(records, token(lr));
  }

  public Identify identify(URI base) {
    Element id = child(request(base, Map.of("verb", "Identify")), "Identify");
    if (id == null) throw unreadable(base, "no Identify element", null);
    return new Identify(text(id, "repositoryName"), text(id, "baseURL"), text(id, "granularity"),
        text(id, "earliestDatestamp"));
  }

  public List<String> listSets(URI base) {
    List<String> sets = new ArrayList<>();
    String token = null;
    do {
      Map<String, String> q = new LinkedHashMap<>();
      q.put("verb", "ListSets");
      if (token != null) q.put("resumptionToken", token);
      Element ls = child(request(base, q), "ListSets");
      if (ls == null) break;
      for (Element s : children(ls, "set")) {
        String spec = text(s, "setSpec");
        if (!spec.isEmpty()) sets.add(spec);
      }
      token = token(ls);
    } while (token != null);
    return sets;
  }

  private Element request(URI base, Map<String, String> params) {
    StringBuilder sb = new StringBuilder(base.toString());
    char sep = base.getRawQuery() == null ? '?' : '&';
    for (Map.Entry<String, String> e : params.entrySet()) {
      sb.append(sep).append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
      sep = '&';
    }
    URI uri = URI.create(sb.toString());
    HttpResponse<byte[]> resp = http.get(uri, Map.of("Accept", "text/xml, application/xml"));
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      throw new HttpProblem(uri, status, false, "HTTP " + status + " from OAI-PMH endpoint: " + uri, null);
    }
    Element root;
    try {
      root = parse(resp.body()).getDocumentElement();
    } catch (Exception e) {
      throw unreadable(uri, e.getMessage(), e);
    }
    Element error = child(root, "error");
    if (error != null) {
      String code = error.getAttribute("code");
      String msg = error.getTextContent().strip();
      switch (code) {
        case "badResumptionToken" -> throw new BadResumptionTokenException(msg.isEmpty() ? code : msg);
        case "noRecordsMatch" -> {
          return root; // no ListRecords child: an empty page
        }
        default -> throw new HttpProblem(uri, status, false, "OAI-PMH error " + code + ": " + msg + ": " + uri, null);
      }
    }
    return root;
  }

  private static HttpProblem unreadable(URI uri, @Nullable String why, @Nullable Throwable cause) {
    return new HttpProblem(uri, 200, false, "Unreadable OAI-PMH response from " + uri + ": " + why, cause);
  }

  static Document parse(byte[] xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
  }

  /** The records of a ListRecords response document. */
  static List<OaiRecord> readRecords(byte[] xml, String prefix) throws Exception {
    Element lr = child(parse(xml).getDocumentElement(), "ListRecords");
    List<OaiRecord> out = new ArrayList<>();
    if (lr != null) for (Element r : children(lr, "record")) out.add(record(r, prefix));
    return out;
  }

  private static OaiRecord record(Element r, String prefix) {
    Element header = child(r, "header");
    String identifier = header == null ? "" : text(header, "identifier");
    boolean deleted = header != null && "deleted".equals(header.getAttribute("status"));
    Instant datestamp = header == null ? null : instant(text(header, "datestamp"));
    List<String> sets = new ArrayList<>();
    if (header != null) for (Element s : children(header, "setSpec")) sets.add(s.getTextContent().strip());
    Element metadata = null;
    Element md = child(r, "metadata");
    if (md != null && !deleted) {
      for (Node n = md.getFirstChild(); n != null; n = n.getNextSibling()) {
        if (n instanceof Element e) {
          metadata = e;
          break;
        }
      }
    }
    return new OaiRecord(identifier, deleted, datestamp, sets, metadata, prefix);
  }

  static @Nullable Instant instant(String s) {
    if (s.isEmpty()) return null;
    try {
      return s.length() <= 10 ? LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.parse(s);
    } catch (DateTimeParseException e) {
      try {
        return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
      } catch (DateTimeParseException e2) {
        return null;
      }
    }
  }

  private static @Nullable String token(Element list) {
    Element t = child(list, "resumptionToken");
    if (t == null) return null;
    String v = t.getTextContent().strip();
    return v.isEmpty() ? null : v;
  }

  private static String text(Element parent, String name) {
    Element e = child(parent, name);
    return e == null ? "" : e.getTextContent().strip();
  }

  /** The first child element in the OAI-PMH namespace with this local name. */
  private static @Nullable Element child(Element parent, String name) {
    List<Element> l = children(parent, name);
    return l.isEmpty() ? null : l.getFirst();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && NS.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) out.add(e);
    }
    return out;
  }
}
