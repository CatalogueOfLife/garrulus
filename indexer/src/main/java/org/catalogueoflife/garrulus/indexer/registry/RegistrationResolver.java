package org.catalogueoflife.garrulus.indexer.registry;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.indexer.cost.BudgetExhaustedException;
import org.catalogueoflife.garrulus.indexer.crossref.CrossrefClient;
import org.catalogueoflife.garrulus.indexer.openalex.OpenAlexClient;
import org.catalogueoflife.garrulus.indexer.task.ServiceOutageException;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.Issn;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns what a user registered into the journal's identity, for {@link CandidateRegistry#offer}:
 * <ul>
 *   <li>ISSN: as given (web normalised it);</li>
 *   <li>DOI of an article: Crossref's record of the work (its ISSNs, container title and publisher), else OpenAlex's
 *   (the source of its primary location), else reason {@code DOI_UNKNOWN};</li>
 *   <li>URL: the page through {@link JournalPage} (public hosts only, redirects checked hop by hop). ISSNs from the
 *   {@code citation_issn}, {@code prism.issn}, {@code prism.eissn} and {@code dc.identifier} metas, else the first
 *   three in the page's text, else from a Magtech path ({@code /EN/<ISSN>/…}); title from
 *   {@code citation_journal_title} or {@code og:site_name}. A Magtech site ("Powered by Beijing Magtech" or the Magtech
 *   path) gives a MAGTECH route hint, an Open Journal Systems page ({@code <meta name="generator">}) an OAI hint on
 *   the journal's {@code /oai}. No ISSN gives reason {@code NO_ISSN_FOUND}; the page's own reasons pass through.</li>
 * </ul>
 * Crossref and OpenAlex failures propagate (the caller defers an outage); a spent OpenAlex budget defers too.
 */
@Component
public class RegistrationResolver {

  public static final String DOI_UNKNOWN = "DOI_UNKNOWN";

  private static final Pattern MAGTECH_FOOTER = Pattern.compile("(?i)powered\\s{1,4}by\\s{1,4}beijing\\s{1,4}magtech");
  private static final Pattern MAGTECH_PATH = Pattern.compile("^/(?:CN|EN)/(\\d{4}-\\d{3}[\\dXx])/",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern OJS_PATH = Pattern.compile("^(.*?/index\\.php/[^/]+)");
  private static final List<String> ISSN_METAS = List.of("citation_issn", "prism.issn", "prism.eissn", "dc.identifier");
  static final int TEXT_ISSNS = 3;
  static final int MAX_ISSNS = 4;
  static final int MAX_TITLE = 500;
  static final Duration PARSE_BUDGET = Duration.ofSeconds(2);
  public static final String LOOKUP_FAILED = "LOOKUP_FAILED";
  private static final Logger LOG = LoggerFactory.getLogger(RegistrationResolver.class);

  /** What the registration names: the journal's ISSNs and what else was learnt, or the reason it is not known. */
  public record Resolution(List<String> issns, @Nullable String title, @Nullable String publisher,
      @Nullable String openalexId, @Nullable RouteHint route, @Nullable String reason) {
    static Resolution failed(String reason) {
      return new Resolution(List.of(), null, null, null, null, reason);
    }
  }

  private final CrossrefClient crossref;
  private final OpenAlexClient openalex;
  private final JournalPage page;

  public RegistrationResolver(CrossrefClient crossref, OpenAlexClient openalex, JournalPage page) {
    this.crossref = crossref;
    this.openalex = openalex;
    this.page = page;
  }

  public Resolution resolve(String kind, String input) {
    return switch (kind) {
      case "ISSN" -> {
        String issn = Issn.normalise(input);
        yield issn == null ? Resolution.failed(JournalPage.NO_ISSN_FOUND)
            : new Resolution(List.of(issn), null, null, null, null, null);
      }
      case "DOI" -> doi(input);
      case "URL" -> url(input);
      default -> throw new IllegalArgumentException("Unknown registration kind " + kind);
    };
  }

  private Resolution doi(String input) {
    String doi = IdScheme.DOI.normalise(input);
    if (doi == null) return Resolution.failed(DOI_UNKNOWN);
    Optional<JsonNode> work = crossref.work(doi);
    if (work.isPresent()) {
      List<String> issns = issns(work.get().path("ISSN"));
      if (!issns.isEmpty()) {
        return new Resolution(issns, first(work.get().path("container-title")), text(work.get().path("publisher")),
            null, null, null);
      }
    }
    Optional<JsonNode> oa;
    try {
      oa = openalex.work("doi:" + doi);
    } catch (BudgetExhaustedException e) {
      throw new ServiceOutageException("api.openalex.org (budget)", e.retryAt(), e);
    }
    JsonNode src = oa.map(w -> w.path("primary_location").path("source")).orElse(null);
    if (src == null || !src.isObject()) return Resolution.failed(DOI_UNKNOWN);
    LinkedHashSet<String> issns = new LinkedHashSet<>(issns(src.path("issn")));
    String l = Issn.normalise(text(src.path("issn_l")));
    if (l != null) issns.add(l);
    String id = IdScheme.OPENALEX.normalise(text(src.path("id")));
    if (issns.isEmpty() && id == null) return Resolution.failed(DOI_UNKNOWN);
    return new Resolution(List.copyOf(issns), text(src.path("display_name")),
        text(src.path("host_organization_name")), id, null, null);
  }

  private Resolution url(String input) {
    URI start;
    try {
      start = new URI(input.strip());
    } catch (URISyntaxException e) {
      return Resolution.failed(JournalPage.PAGE_UNREACHABLE);
    }
    JournalPage.Fetched f = page.fetch(start);
    if (f.reason() != null || f.html() == null) {
      return Resolution.failed(f.reason() == null ? JournalPage.PAGE_UNREACHABLE : f.reason());
    }
    return readPage(f.uri(), f.html());
  }

  /**
   * The journal a page names, read from its first 512 KB ({@link PageMarkup}) within {@link #PARSE_BUDGET}: at most
   * {@value #MAX_ISSNS} ISSNs, a title of at most {@value #MAX_TITLE} code points, a route hint. A page that takes
   * longer is {@code LOOKUP_FAILED}.
   */
  static Resolution readPage(URI uri, String html) {
    long until = System.nanoTime() + PARSE_BUDGET.toNanos();
    try {
      return read(uri, PageMarkup.scan(html, until), until);
    } catch (PageMarkup.TooSlow e) {
      LOG.warn("Registered page {}: reading it took longer than {}", uri, PARSE_BUDGET);
      return Resolution.failed(LOOKUP_FAILED);
    }
  }

  private static Resolution read(URI uri, PageMarkup page, long until) {
    Map<String, List<String>> metas = page.metas();
    CharSequence text = new Budgeted(page.text(), until);
    LinkedHashSet<String> found = new LinkedHashSet<>();
    for (String name : ISSN_METAS) {
      for (String v : metas.getOrDefault(name, List.of())) {
        String n = Issn.normalise(v);
        if (n != null) found.add(n);
        else found.addAll(Issn.all(new Budgeted(v, until)));
      }
    }
    if (found.isEmpty()) Issn.all(text).stream().limit(TEXT_ISSNS).forEach(found::add);
    Matcher magtechPath = MAGTECH_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
    String pathIssn = magtechPath.find() ? Issn.normalise(magtechPath.group(1)) : null;
    if (found.isEmpty() && pathIssn != null) found.add(pathIssn);
    if (found.isEmpty()) return Resolution.failed(JournalPage.NO_ISSN_FOUND);
    List<String> issns = found.stream().limit(MAX_ISSNS).toList();

    String title = cap(firstOf(metas, "citation_journal_title", "og:site_name"));
    RouteHint route = null;
    if (pathIssn != null || MAGTECH_FOOTER.matcher(text).find()) {
      Map<String, Object> cfg = new LinkedHashMap<>();
      cfg.put("site", origin(uri));
      cfg.put("issn", pathIssn != null ? pathIssn : issns.getFirst());
      route = new RouteHint("MAGTECH", FinderJson.mapper().writeValueAsString(cfg));
    } else if (metas.getOrDefault("generator", List.of()).stream()
        .anyMatch(g -> g.strip().toLowerCase(Locale.ROOT).startsWith("open journal systems"))) {
      Map<String, Object> cfg = new LinkedHashMap<>();
      cfg.put("base", ojsJournal(uri, page.links()) + "/oai");
      cfg.put("prefix", "oai_dc");
      cfg.put("issns", issns);
      route = new RouteHint("OAI", FinderJson.mapper().writeValueAsString(cfg));
    }
    return new Resolution(issns, title, null, null, route, null);
  }

  /** At most {@value #MAX_TITLE} code points, on one line. */
  private static @Nullable String cap(@Nullable String title) {
    if (title == null) return null;
    String t = title.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    return t.codePointCount(0, t.length()) <= MAX_TITLE ? t : t.substring(0, t.offsetByCodePoints(0, MAX_TITLE));
  }

  /** A text whose reading by a regular expression stops at the deadline. */
  private record Budgeted(CharSequence text, long until) implements CharSequence {
    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if ((index & 0x3FF) == 0 && System.nanoTime() > until) throw new PageMarkup.TooSlow();
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new Budgeted(text.subSequence(start, end), until);
    }

    @Override
    public String toString() {
      return text.toString();
    }
  }

  /** {@code scheme://host[:port]} of the page. */
  private static String origin(URI uri) {
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority();
  }

  /**
   * The journal's URL on an OJS site: the part before {@code /gateway/} of its web feed link on the same site, else up
   * to {@code /index.php/<journal>}, else the page's path without a trailing {@code /index} or slash.
   */
  static String ojsJournal(URI page, List<Map<String, String>> links) {
    String origin = origin(page);
    for (Map<String, String> a : links) {
      String href = a.get("href");
      int gateway = href == null ? -1 : href.indexOf("/gateway/plugin/");
      if (gateway > 0 && "alternate".equalsIgnoreCase(a.getOrDefault("rel", "").strip())) {
        try {
          URI feed = page.resolve(new URI(PageMarkup.unescape(href.substring(0, gateway)).strip()));
          if (feed.getScheme() != null && origin(feed).equalsIgnoreCase(origin)) return feed.toString();
        } catch (URISyntaxException | IllegalArgumentException e) {
          // an unusable link: the path rules below
        }
      }
    }
    String path = page.getRawPath() == null ? "" : page.getRawPath();
    Matcher m = OJS_PATH.matcher(path);
    if (m.find()) return origin + m.group(1);
    path = path.replaceAll("/+$", "").replaceAll("/index$", "");
    return origin + path;
  }

  private static @Nullable String firstOf(Map<String, List<String>> metas, String... names) {
    for (String n : names) {
      for (String v : metas.getOrDefault(n, List.of())) {
        if (!v.isBlank()) return v;
      }
    }
    return null;
  }

  private static List<String> issns(JsonNode arr) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    arr.forEach(n -> {
      String i = Issn.normalise(n.asString(""));
      if (i != null) out.add(i);
    });
    return List.copyOf(out);
  }

  private static @Nullable String first(JsonNode arr) {
    for (JsonNode n : arr) {
      String t = text(n);
      if (t != null) return t;
    }
    return null;
  }

  private static @Nullable String text(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) return null;
    String s = n.asString("").strip();
    return s.isEmpty() ? null : s;
  }
}
