package org.catalogueoflife.garrulus.indexer.layer.magtech;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.garrulus.indexer.http.HttpProblem;
import org.catalogueoflife.garrulus.indexer.http.PoliteHttp;
import org.catalogueoflife.garrulus.indexer.layer.oai.OaiPmhClient;
import org.catalogueoflife.garrulus.store.work.TitleText;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reads the RSS feeds and the article pages of a Magtech journal site through {@link PoliteHttp}. */
public class MagtechClient {

  /** One feed item; the title and the description keep their HTML, {@code pubDate} is the feed's own text. */
  public record MagtechItem(String title, URI link, String descriptionHtml, String authors, @Nullable String pubDate) {
  }

  /**
   * What the {@code citation_*} meta tags of an article page add to the feed. {@code language} is
   * {@code citation_language}, which the English page gives as {@code en} for a Chinese article too: not evidence.
   * {@code titleLanguage} is the {@code xml:lang} of a {@code citation_title} that is not English: the language the
   * article is written in; {@code originalTitle} is that tag's title text.
   */
  public record ArticleMeta(@Nullable String doi, @Nullable LocalDate date, @Nullable String language,
      @Nullable String titleLanguage, @Nullable String originalTitle) {

    public ArticleMeta(@Nullable String doi, @Nullable LocalDate date, @Nullable String language,
        @Nullable String titleLanguage) {
      this(doi, date, language, titleLanguage, null);
    }
  }

  private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern NAME = Pattern.compile("\\bname\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
  private static final Pattern CONTENT = Pattern.compile("\\bcontent\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
  private static final Pattern LANG = Pattern.compile("\\b(?:xml:)?lang\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");

  private final PoliteHttp http;

  public MagtechClient(PoliteHttp http) {
    this.http = http;
  }

  /** The items of an RSS feed; {@link NotAFeedException} when the body is not RSS (even with status 200). */
  public List<MagtechItem> feed(URI feed) {
    HttpResponse<byte[]> resp = http.get(feed, Map.of("Accept", "application/rss+xml, text/xml, application/xml"));
    ok(feed, resp);
    Document doc;
    try {
      doc = OaiPmhClient.parse(resp.body());
    } catch (Exception e) {
      throw new NotAFeedException(feed, "unreadable XML (" + e.getMessage() + ")");
    }
    Element root = doc.getDocumentElement();
    if (!"rss".equals(root.getLocalName() == null ? root.getTagName() : root.getLocalName())) {
      throw new NotAFeedException(feed, "root element is " + root.getTagName());
    }
    List<MagtechItem> items = new ArrayList<>();
    for (Element channel : children(root, "channel")) {
      for (Element item : children(channel, "item")) {
        String title = text(item, "title");
        String link = text(item, "link");
        if (title.isEmpty() || link.isEmpty()) continue;
        URI uri;
        try {
          uri = URI.create(link);
        } catch (IllegalArgumentException e) {
          continue;
        }
        String pub = text(item, "pubDate");
        items.add(new MagtechItem(title, uri, text(item, "description"), text(item, "author"),
            pub.isEmpty() ? null : pub));
      }
    }
    return items;
  }

  /** The DOI, publication date and language of an article page, read from its {@code citation_*} meta tags. */
  public ArticleMeta articleMeta(URI page) {
    HttpResponse<byte[]> resp = http.get(page, Map.of("Accept", "text/html"));
    ok(page, resp);
    String html = new String(resp.body(), StandardCharsets.UTF_8);
    int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
    Map<String, String> meta = new HashMap<>();
    String titleLanguage = null;
    String originalTitle = null;
    Matcher m = META.matcher(head < 0 ? html : html.substring(0, head));
    while (m.find()) {
      String tag = m.group();
      Matcher n = NAME.matcher(tag);
      Matcher c = CONTENT.matcher(tag);
      if (n.find() && c.find()) {
        String value = TitleText.clean(c.group(1) != null ? c.group(1) : c.group(2));
        if (value != null) meta.putIfAbsent(n.group(1).toLowerCase(Locale.ROOT), value);
        if (titleLanguage == null && "citation_title".equalsIgnoreCase(n.group(1))) {
          Matcher l = LANG.matcher(tag);
          if (l.find()) {
            String lang = (l.group(1) != null ? l.group(1) : l.group(2)).strip();
            if (!lang.isEmpty() && !lang.toLowerCase(Locale.ROOT).startsWith("en") && value != null) {
              titleLanguage = lang;
              originalTitle = value;
            }
          }
        }
      }
    }
    String date = meta.getOrDefault("citation_publication_date", meta.get("citation_date"));
    return new ArticleMeta(meta.get("citation_doi"), date(date), meta.get("citation_language"), titleLanguage,
        originalTitle);
  }

  static @Nullable LocalDate date(@Nullable String s) {
    if (s == null) return null;
    Matcher m = DATE.matcher(s.strip());
    if (!m.find()) return null;
    try {
      return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    } catch (java.time.DateTimeException e) {
      return null;
    }
  }

  private static void ok(URI uri, HttpResponse<byte[]> resp) {
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      throw new HttpProblem(uri, status, false, "HTTP " + status + " from Magtech site: " + uri, null);
    }
  }

  private static String text(Element parent, String name) {
    List<Element> l = children(parent, name);
    return l.isEmpty() ? "" : l.getFirst().getTextContent().strip();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> out = new ArrayList<>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element e && name.equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName())) out.add(e);
    }
    return out;
  }
}
