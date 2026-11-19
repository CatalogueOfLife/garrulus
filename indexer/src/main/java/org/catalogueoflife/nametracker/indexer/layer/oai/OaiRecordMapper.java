package org.catalogueoflife.nametracker.indexer.layer.oai;

import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.indexer.layer.HarvestedWork;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.work.Layer;
import org.catalogueoflife.nametracker.store.work.TitleText;
import org.catalogueoflife.nametracker.store.work.Work;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Maps an {@link OaiRecord} of prefix {@code oai_dc} or {@code jats} to a {@link HarvestedWork} of the OAI layer.
 * Nothing is returned for a deleted record, one without metadata, an unknown prefix and a record without a title.
 * Further titles of other languages stay in the record's metadata element (for M6c).
 */
public final class OaiRecordMapper {

  static final String DC = "http://purl.org/dc/elements/1.1/";
  private static final Pattern DATE = Pattern.compile("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?.*$", Pattern.DOTALL);
  private static final Pattern CC = Pattern.compile("creativecommons\\.org/licenses/([a-z\\-]+)", Pattern.CASE_INSENSITIVE);

  private OaiRecordMapper() {
  }

  public static Optional<HarvestedWork> toHarvested(OaiRecord r, long sourceId) {
    if (r.deleted() || r.metadata() == null) return Optional.empty();
    Parsed p = switch (r.prefix()) {
      case "oai_dc" -> dc(r.metadata());
      case "jats" -> jats(r.metadata());
      default -> null;
    };
    if (p == null || p.title == null) return Optional.empty();
    List<Identifier> ids = new ArrayList<>();
    Identifier doi = IdScheme.id(IdScheme.DOI, p.doi);
    if (doi != null) ids.add(doi);
    Identifier oai = IdScheme.id(IdScheme.OAI, r.identifier());
    if (oai != null) ids.add(oai);
    Short year = p.year == null ? null : (short) p.year.intValue();
    boolean jats = p.abstractJats != null;
    Work w = new Work(null, null, doi == null ? null : doi.value(), sourceId, p.title, p.date, year, p.volume, p.issue,
        p.pages, p.authors, "article", p.language, null, p.license, null, jats ? null : p.abstractText,
        !jats && p.abstractText != null ? "OAI" : null, null, null, null, null, null, null, null, null, p.abstractJats,
        null, null, null, null, jats ? "OAI" : null, null);
    return Optional.of(new HarvestedWork(w, Layer.OAI, ids));
  }

  private static final class Parsed {
    String title;
    String authors;
    String doi;
    LocalDate date;
    Integer year;
    String volume;
    String issue;
    String pages;
    String abstractText;
    String abstractJats;
    String license;
    String language;
  }

  // ---- Dublin Core ----

  private static Parsed dc(Element dc) {
    Parsed p = new Parsed();
    p.title = TitleText.clean(firstText(dc, DC, "title"));
    List<Map<String, String>> authors = new ArrayList<>();
    for (Element c : elements(dc, DC, "creator")) {
      String name = creator(c.getTextContent().strip());
      if (name.isEmpty()) continue;
      Map<String, String> m = new LinkedHashMap<>();
      m.put("name", name);
      m.put("orcid", orcid(c.getAttribute("id")));
      authors.add(m);
    }
    p.authors = FinderJson.mapper().writeValueAsString(authors);
    for (Element i : elements(dc, DC, "identifier")) {
      String d = doiOf(i.getTextContent().strip());
      if (d != null) {
        p.doi = d;
        break;
      }
    }
    String date = firstText(dc, DC, "date");
    if (date != null) date(date, p);
    p.abstractText = firstText(dc, DC, "description");
    for (Element rt : elements(dc, DC, "rights")) {
      String l = license(rt.getTextContent().strip());
      if (l != null) {
        p.license = l;
        break;
      }
    }
    p.language = firstText(dc, DC, "language");
    return p;
  }

  /** The ISSNs a record carries: {@code dc:source} and {@code dc:identifier} of oai_dc, {@code issn} of JATS. */
  public static Set<String> issns(OaiRecord r) {
    Set<String> out = new java.util.LinkedHashSet<>();
    if (r.metadata() == null) return out;
    List<Element> fields = new ArrayList<>();
    if ("jats".equals(r.prefix())) {
      fields.addAll(descendants(r.metadata(), "issn"));
    } else {
      fields.addAll(elements(r.metadata(), DC, "source"));
      fields.addAll(elements(r.metadata(), DC, "identifier"));
    }
    for (Element e : fields) {
      Matcher m = ISSN.matcher(e.getTextContent());
      while (m.find()) {
        String issn = m.group(1).toUpperCase(Locale.ROOT);
        if (validIssn(issn)) out.add(issn);
      }
    }
    return out;
  }

  private static final Pattern ISSN = Pattern.compile("(?<![\\d-])(\\d{4}-\\d{3}[\\dXx])(?![\\d-])");

  /** An ISSN {@code NNNN-NNNC} whose check digit is right (a page range such as 1234-1250 is not). */
  static boolean validIssn(String s) {
    if (s.length() != 9 || s.charAt(4) != '-') return false;
    String d = s.substring(0, 4) + s.substring(5);
    int sum = 0;
    for (int i = 0; i < 7; i++) {
      char c = d.charAt(i);
      if (c < '0' || c > '9') return false;
      sum += (c - '0') * (8 - i);
    }
    int check = (11 - sum % 11) % 11;
    char last = Character.toUpperCase(d.charAt(7));
    return check == 10 ? last == 'X' : last == (char) ('0' + check);
  }

  /** "Family, Given" becomes "Given Family"; anything else stays as it is. */
  static String creator(String s) {
    int i = s.indexOf(',');
    if (i > 0 && s.indexOf(',', i + 1) < 0) {
      String family = s.substring(0, i).strip();
      String given = s.substring(i + 1).strip();
      if (!given.isEmpty()) return given + " " + family;
      return family;
    }
    return s;
  }

  private static final Pattern DOI_PREFIX = Pattern.compile("^(?:info:doi[/:]|doi:)\\s*", Pattern.CASE_INSENSITIVE);

  /**
   * The DOI of an identifier value: a bare DOI, {@code info:doi/…}, {@code info:doi:…}, {@code doi:…} or a URL on
   * doi.org (or a subdomain such as dx.doi.org); null for any other value.
   */
  static @Nullable String doiOf(String v) {
    String s = v.strip();
    String lower = s.toLowerCase(Locale.ROOT);
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
      try {
        URI u = URI.create(s);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("doi.org") && !host.endsWith(".doi.org")) return null;
        // the decoded path: percent escapes resolved, a '+' kept (it is a character of DOIs, not a space)
        String path = u.getPath() == null ? "" : u.getPath();
        s = path.startsWith("/") ? path.substring(1) : path;
      } catch (IllegalArgumentException e) {
        return null;
      }
    } else {
      s = DOI_PREFIX.matcher(s).replaceFirst("");
    }
    s = s.strip();
    return s.startsWith("10.") ? IdScheme.DOI.normalise(s) : null;
  }

  /**
   * The Creative Commons code ({@code cc-by}) of a licence URL. {@code work.license} holds such codes only, so any
   * other URL, and any text that is no URL, gives null.
   */
  static @Nullable String license(@Nullable String url) {
    if (url == null) return null;
    String u = url.strip().toLowerCase(Locale.ROOT);
    if (!u.startsWith("http")) return null;
    Matcher m = CC.matcher(u);
    if (m.find()) return "cc-" + m.group(1).replaceAll("-+$", "");
    if (u.contains("creativecommons.org/publicdomain/zero")) return "cc0";
    if (u.contains("creativecommons.org/publicdomain/mark")) return "public-domain";
    return null;
  }

  private static @Nullable String orcid(@Nullable String s) {
    if (s == null) return null;
    int i = s.indexOf("orcid.org/");
    String o = (i >= 0 ? s.substring(i + "orcid.org/".length()) : s).strip();
    return o.matches("\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dX]") ? o : null;
  }

  private static void date(String s, Parsed p) {
    Matcher m = DATE.matcher(s.strip());
    if (!m.matches()) return;
    int y = Integer.parseInt(m.group(1));
    p.year = y;
    if (m.group(2) != null && m.group(3) != null) {
      try {
        p.date = LocalDate.of(y, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
      } catch (DateTimeException e) {
        p.date = null;
      }
    }
  }

  // ---- JATS ----

  private static Parsed jats(Element article) {
    Parsed p = new Parsed();
    Element meta = first(article, "article-meta");
    if (meta == null) return p;
    Element tg = first(meta, "title-group");
    Element at = tg == null ? null : first(tg, "article-title");
    p.title = at == null ? null : TitleText.clean(plain(at));
    List<Map<String, String>> authors = new ArrayList<>();
    for (Element c : descendants(meta, "contrib")) {
      String type = c.getAttribute("contrib-type");
      if (!type.isEmpty() && !type.equals("author")) continue;
      String name = contribName(c);
      if (name == null) continue;
      Map<String, String> m = new LinkedHashMap<>();
      m.put("name", name);
      m.put("orcid", contribOrcid(c));
      authors.add(m);
    }
    p.authors = FinderJson.mapper().writeValueAsString(authors);
    for (Element id : children(meta, "article-id")) {
      if ("doi".equals(id.getAttribute("pub-id-type"))) {
        p.doi = doiOf(id.getTextContent().strip());
        break;
      }
    }
    pubDate(meta, p);
    p.volume = firstChildText(meta, "volume");
    p.issue = firstChildText(meta, "issue");
    String fp = firstChildText(meta, "fpage");
    String lp = firstChildText(meta, "lpage");
    p.pages = fp == null ? null : lp == null || lp.equals(fp) ? fp : fp + "-" + lp;
    Element abs = null;
    for (Element a : children(meta, "abstract")) {
      if (a.getAttribute("abstract-type").isEmpty()) {
        abs = a;
        break;
      }
    }
    if (abs != null) {
      String x = inner(abs).strip();
      if (!x.isEmpty()) p.abstractJats = x;
    }
    Element permissions = first(meta, "permissions");
    if (permissions != null) {
      for (Element l : descendants(permissions, "license")) {
        String href = l.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        if (href.isEmpty()) href = l.getAttribute("xlink:href");
        String lic = license(href);
        if (lic != null) {
          p.license = lic;
          break;
        }
      }
    }
    String lang = article.getAttributeNS("http://www.w3.org/XML/1998/namespace", "lang");
    p.language = lang.isEmpty() ? null : lang;
    return p;
  }

  private static @Nullable String contribName(Element c) {
    Element name = first(c, "name");
    if (name != null) {
      String given = firstChildText(name, "given-names");
      String sur = firstChildText(name, "surname");
      String n = given == null ? sur : sur == null ? given : given + " " + sur;
      if (n != null) return n;
    }
    Element sn = first(c, "string-name");
    if (sn != null && !sn.getTextContent().isBlank()) return sn.getTextContent().strip().replaceAll("\\s+", " ");
    Element collab = first(c, "collab");
    if (collab != null && !collab.getTextContent().isBlank()) return collab.getTextContent().strip();
    return null;
  }

  private static @Nullable String contribOrcid(Element c) {
    for (Element e : descendants(c, "contrib-id")) {
      String o = orcid(e.getTextContent());
      if (o != null) return o;
    }
    for (Element e : descendants(c, "uri")) {
      String o = orcid(e.getTextContent());
      if (o != null) return o;
    }
    return null;
  }

  /** The publication date: the {@code pub}/{@code epub} one, else the first with a year. */
  private static void pubDate(Element meta, Parsed p) {
    Element best = null;
    for (Element d : children(meta, "pub-date")) {
      boolean pub = "pub".equals(d.getAttribute("date-type")) || "epub".equals(d.getAttribute("pub-type"));
      if (best == null || pub && !"pub".equals(best.getAttribute("date-type"))) best = d;
      if (pub) break;
    }
    if (best == null) return;
    String y = firstChildText(best, "year");
    if (y == null || !y.matches("\\d{4}")) return;
    p.year = Integer.parseInt(y);
    String mo = firstChildText(best, "month");
    String d = firstChildText(best, "day");
    if (mo != null && d != null) {
      try {
        p.date = LocalDate.of(p.year, Integer.parseInt(mo), Integer.parseInt(d));
      } catch (DateTimeException | NumberFormatException e) {
        p.date = null;
      }
    }
  }

  // ---- DOM helpers ----

  /** The inner markup of an element as JATS: elements by local name, no attributes, no namespaces. */
  static String inner(Element e) {
    StringBuilder sb = new StringBuilder();
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) serialise(n, sb);
    return sb.toString();
  }

  private static void serialise(Node n, StringBuilder sb) {
    if (n instanceof Element e) {
      String name = e.getLocalName();
      if (!e.hasChildNodes()) {
        sb.append('<').append(name).append("/>");
        return;
      }
      sb.append('<').append(name).append('>');
      for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) serialise(c, sb);
      sb.append("</").append(name).append('>');
    } else if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
      sb.append(n.getNodeValue().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }
  }

  private static String plain(Element e) {
    return e.getTextContent().strip().replaceAll("\\s+", " ");
  }

  private static @Nullable String firstText(Element parent, String ns, String name) {
    for (Element e : elements(parent, ns, name)) {
      String s = e.getTextContent().strip();
      if (!s.isEmpty()) return s;
    }
    return null;
  }

  private static List<Element> elements(Element parent, String ns, String name) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && name.equals(e.getLocalName())) out.add(e);
    }
    return out;
  }

  /** Child elements by local name, whatever their namespace. */
  private static List<Element> children(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && name.equals(e.getLocalName())) out.add(e);
    }
    return out;
  }

  private static @Nullable String firstChildText(Element parent, String name) {
    for (Element e : children(parent, name)) {
      String s = e.getTextContent().strip();
      if (!s.isEmpty()) return s;
    }
    return null;
  }

  private static @Nullable Element first(Element parent, String name) {
    List<Element> l = descendants(parent, name);
    return l.isEmpty() ? null : l.getFirst();
  }

  private static List<Element> descendants(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    collect(parent, name, out);
    return out;
  }

  private static void collect(Element parent, String name, List<Element> out) {
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e) {
        if (name.equals(e.getLocalName())) out.add(e);
        collect(e, name, out);
      }
    }
  }
}
