package org.catalogueoflife.nametracker.web.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.w3c.dom.Document;

class FeedControllerIT extends AbstractPostgresIT {

  static final AtomicInteger SEQ = new AtomicInteger();
  static final String NS = "http://www.w3.org/2005/Atom";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  HttpResponse<String> get(String path) throws Exception {
    return HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  static Document parse(String xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  long work(String title, LocalDate published, long source) {
    return jdbc.queryForObject("INSERT INTO work (doi, title, publication_date, year, state, origin, text_level, source_id, "
        + "first_indexed_at, taxgroups) VALUES (?, ?, ?, 2026, 'INDEXED', 'LIVE', 'ABSTRACT_ONLY', ?, now(), '{Insects}') RETURNING id",
        Long.class, "10.1/f" + SEQ.incrementAndGet(), title, java.sql.Date.valueOf(published), source);
  }

  long source() {
    return jdbc.queryForObject("INSERT INTO source (title, tier, origin, active) VALUES ('Zootaxa', 'CORE', 'MANUAL', true) RETURNING id", Long.class);
  }

  long name(String name) {
    return jdbc.queryForObject("INSERT INTO name_string (name, kind, rank) VALUES (?, 'SCIENTIFIC', 'species') RETURNING id", Long.class,
        name + SEQ.incrementAndGet());
  }

  void workName(long work, long nameId, String taxon, boolean main, boolean onlyRef) {
    jdbc.update("INSERT INTO work_name (work_id, name_string_id, taxon_path, main_subject, only_reference, salience, mention_count, "
        + "body_count, in_title, in_abstract, in_heading, best_snippet, placement_method) "
        + "VALUES (?, ?, ?::text[], ?, ?, 0.5, 1, 1, false, true, false, 's', 'NONE')", work, nameId, "{" + taxon + "}", main, onlyRef);
  }

  @Test
  void taxonFeedListsRecentMainSubjectWorks() throws Exception {
    long src = source();
    String taxon = "FT" + SEQ.incrementAndGet();
    long n = name("Aus bus");
    long recent = work("Recent <i>Aus bus</i> & co", LocalDate.now(ZoneOffset.UTC).minusDays(5), src);
    long ref = work("Only cited", LocalDate.now(ZoneOffset.UTC).minusDays(5), src);
    long minor = work("Minor mention", LocalDate.now(ZoneOffset.UTC).minusDays(5), src);
    workName(recent, n, taxon, true, false);
    workName(ref, n, taxon, true, true);
    workName(minor, n, taxon, false, false);

    HttpResponse<String> r = get("/feeds/taxon/" + taxon + ".atom");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/atom+xml");
    assertThat(r.headers().firstValue("Cache-Control").orElse("")).isEqualTo("public, max-age=900");
    assertThat(r.headers().firstValue("Set-Cookie")).isEmpty();
    Document d = parse(r.body());
    assertThat(d.getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(0).getTextContent()).contains("Recent <i>Aus bus</i> & co");
    assertThat(r.body()).contains("/work/" + recent).doesNotContain("@");
    assertThat(r.body()).contains("https://doi.org/10.1/f").contains("Zootaxa");
    // unknown taxon: empty feed
    assertThat(parse(get("/feeds/taxon/nosuchtaxon.atom").body()).getElementsByTagNameNS(NS, "entry").getLength()).isZero();
  }

  @Test
  void oldPaperNotInFeed() throws Exception {
    long src = source();
    String taxon = "FO" + SEQ.incrementAndGet();
    long n = name("Old one");
    workName(work("Old", LocalDate.now(ZoneOffset.UTC).minusDays(200), src), n, taxon, true, false);
    workName(work("Border", LocalDate.now(ZoneOffset.UTC).minusDays(90), src), n, taxon, true, false);
    workName(work("Future", LocalDate.now(ZoneOffset.UTC).plusDays(30), src), n, taxon, true, false);
    Document d = parse(get("/feeds/taxon/" + taxon + ".atom").body());
    assertThat(d.getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(0).getTextContent()).contains("Border");
  }

  @Test
  void sourceNameAndGroupFeeds() throws Exception {
    long src = source();
    long n = name("Cited");
    long w = work("By source", LocalDate.now(ZoneOffset.UTC).minusDays(1), src);
    workName(w, n, "X" + SEQ.incrementAndGet(), false, false);
    assertThat(parse(get("/feeds/source/" + src + ".atom").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(parse(get("/feeds/name/" + n + ".atom").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(get("/feeds/source/99999999.atom").statusCode()).isEqualTo(404);
    assertThat(get("/feeds/name/99999999.atom").statusCode()).isEqualTo(404);
    assertThat(get("/feeds/taxgroup/Nonsense.atom").statusCode()).isEqualTo(404);
    HttpResponse<String> g = get("/feeds/taxgroup/Insects.atom");
    assertThat(g.statusCode()).isEqualTo(200);
    // main subject only: the work without a main-subject name is not listed
    assertThat(g.body()).doesNotContain("By source");
  }

  @Test
  void privateFeedListsHits() throws Exception {
    long user = jdbc.queryForObject("INSERT INTO app_user (email, email_verified, digest_since) VALUES (?, now(), now()) RETURNING id",
        Long.class, "feed" + SEQ.incrementAndGet() + "@example.org");
    String token = "tok" + SEQ.incrementAndGet() + "abcdefghijklmnop";
    long sub = jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label, feed_token) VALUES (?, 'TAXON', 'T1', 'My beetles', ?) RETURNING id",
        Long.class, user, token);
    long src = source();
    long w1 = work("First", LocalDate.now(ZoneOffset.UTC).minusDays(400), src);
    long w2 = work("Second", LocalDate.now(ZoneOffset.UTC).minusDays(2), src);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, 'ARTICLE', now() - interval '2 days', "
        + "'[{\"name\":\"Carabus xus\",\"nameStringId\":1}]'::jsonb)", sub, w1);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, 'NEW_NAMES', now(), "
        + "'[{\"name\":\"Aus bus sp. nov.\",\"nameStringId\":2,\"actTypes\":[\"SP_NOV\"]}]'::jsonb)", sub, w2);
    HttpResponse<String> r = get("/feeds/s/" + token + ".atom");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Cache-Control").orElse("")).isEqualTo("private, no-store");
    assertThat(r.headers().firstValue("X-Robots-Tag").orElse("")).isEqualTo("noindex");
    assertThat(r.headers().firstValue("Referrer-Policy").orElse("")).isEqualTo("no-referrer");
    assertThat(r.headers().firstValue("Set-Cookie")).isEmpty();
    Document d = parse(r.body());
    assertThat(d.getElementsByTagNameNS(NS, "title").item(0).getTextContent()).isEqualTo("Garrulus: My beetles");
    assertThat(d.getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(2);
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(0).getTextContent()).contains("Second")
        .contains("New names: Aus bus sp. nov. (sp. nov.)");
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(1).getTextContent()).contains("Carabus xus");
    assertThat(r.body()).doesNotContain("@example.org");
  }

  long user() {
    return jdbc.queryForObject("INSERT INTO app_user (email, email_verified, digest_since) VALUES (?, now(), now()) RETURNING id",
        Long.class, "feedu" + SEQ.incrementAndGet() + "@example.org");
  }

  /** A subscription of a new user; returns its feed token. */
  String subscription(long[] subId) {
    String token = "sub" + SEQ.incrementAndGet() + "abcdefghijklmnop";
    subId[0] = jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label, feed_token) "
        + "VALUES (?, 'SOURCE', 'S1', 'Journal', ?) RETURNING id", Long.class, user(), token);
    return token;
  }

  static String text(org.w3c.dom.Node entry, String tag) {
    return ((org.w3c.dom.Element) entry).getElementsByTagNameNS(NS, tag).item(0).getTextContent();
  }

  @Test
  void privateFeedHasOneEntryPerHit() throws Exception {
    long[] sub = new long[1];
    String token = subscription(sub);
    long w = work("Twice", LocalDate.now(ZoneOffset.UTC).minusDays(2), source());
    long article = jdbc.queryForObject("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, "
        + "'ARTICLE', '2026-11-10T08:00:00Z', '[{\"name\":\"Aus bus\",\"nameStringId\":1,\"actTypes\":[]}]'::jsonb) "
        + "RETURNING id", Long.class, sub[0], w);
    long fresh = jdbc.queryForObject("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, "
        + "'NEW_NAMES', '2026-11-12T09:30:00Z', '[{\"name\":\"Aus cus\",\"nameStringId\":2,\"actTypes\":[\"COMB_NOV\"]}]'::jsonb) "
        + "RETURNING id", Long.class, sub[0], w);
    Document d = parse(get("/feeds/s/" + token + ".atom").body());
    var entries = d.getElementsByTagNameNS(NS, "entry");
    assertThat(entries.getLength()).isEqualTo(2);
    // newest hit first; ids differ per hit, updated is the hit's own time
    assertThat(text(entries.item(0), "id")).endsWith("/work/" + w + "#hit-" + fresh);
    assertThat(text(entries.item(0), "updated")).isEqualTo("2026-11-12T09:30:00Z");
    assertThat(text(entries.item(0), "summary")).contains("New names: Aus cus (comb. nov.)");
    assertThat(text(entries.item(1), "id")).endsWith("/work/" + w + "#hit-" + article);
    assertThat(text(entries.item(1), "updated")).isEqualTo("2026-11-10T08:00:00Z");
    assertThat(text(entries.item(1), "summary")).contains("Names: Aus bus");
    // the feed's updated is the newest hit
    assertThat(d.getDocumentElement().getElementsByTagNameNS(NS, "updated").item(0).getTextContent())
        .isEqualTo("2026-11-12T09:30:00Z");
  }

  @Test
  void privateFeedShowsTenNamesActsAndMainSubjectsFirst() throws Exception {
    long[] sub = new long[1];
    String token = subscription(sub);
    long w = work("A checklist", LocalDate.now(ZoneOffset.UTC).minusDays(2), source());
    StringBuilder names = new StringBuilder("[");
    for (int i = 1; i <= 25; i++) {
      String acts = i == 22 ? "[\"GEN_NOV\"]" : "[]";
      names.append(i == 1 ? "" : ",").append("{\"nameStringId\":").append(i).append(",\"name\":\"Name n").append(i)
          .append("\",\"actTypes\":").append(acts).append(",\"mainSubject\":").append(i == 15).append('}');
    }
    names.append(']');
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, names) VALUES (?, ?, 'ARTICLE', ?::jsonb)",
        sub[0], w, names.toString());
    Document d = parse(get("/feeds/s/" + token + ".atom").body());
    assertThat(text(d.getElementsByTagNameNS(NS, "entry").item(0), "summary")).contains(
        "Names: Name n22 (gen. nov.), Name n15, Name n1, Name n2, Name n3, Name n4, Name n5, Name n6, Name n7, "
            + "Name n8, +15 more");
  }

  @Test
  void doiLinksArePercentEncoded() throws Exception {
    long[] sub = new long[1];
    String token = subscription(sub);
    long src = source();
    String taxon = "FD" + SEQ.incrementAndGet();
    String sici = "10.1002/(SICI)1097-4636(199706)35:4<477::AID-JBM8>3.0.CO;2-#" + SEQ.incrementAndGet();
    long w = jdbc.queryForObject("INSERT INTO work (doi, title, publication_date, year, state, origin, text_level, "
        + "source_id, first_indexed_at) VALUES (?, 'SICI', ?, 2026, 'INDEXED', 'LIVE', 'ABSTRACT_ONLY', ?, now()) "
        + "RETURNING id", Long.class, sici, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)), src);
    workName(w, name("Sici"), taxon, true, false);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, names) VALUES (?, ?, 'ARTICLE', '[]'::jsonb)", sub[0], w);
    String encoded = "https://doi.org/10.1002/(SICI)1097-4636(199706)35:4%3C477::AID-JBM8%3E3.0.CO;2-%23";
    for (String path : new String[] {"/feeds/s/" + token + ".atom", "/feeds/taxon/" + taxon + ".atom"}) {
      Document d = parse(get(path).body());
      var links = d.getElementsByTagNameNS(NS, "link");
      String related = null;
      for (int i = 0; i < links.getLength(); i++) {
        var l = (org.w3c.dom.Element) links.item(i);
        if ("related".equals(l.getAttribute("rel"))) {
          related = l.getAttribute("href");
        }
      }
      assertThat(related).as(path).startsWith(encoded);
    }
  }

  @Test
  void undatedOldYearNotInFeed() throws Exception {
    long src = source();
    String taxon = "FY" + SEQ.incrementAndGet();
    long n = name("Undated");
    int thisYear = LocalDate.now(ZoneOffset.UTC).getYear();
    for (Object[] row : new Object[][] {{"Undated old", 2001}, {"Undated now", thisYear}, {"Undated no year", null}}) {
      long w = jdbc.queryForObject("INSERT INTO work (doi, title, year, state, origin, text_level, source_id, "
          + "first_indexed_at) VALUES (?, ?, ?, 'INDEXED', 'LIVE', 'ABSTRACT_ONLY', ?, now()) RETURNING id", Long.class,
          "10.1/y" + SEQ.incrementAndGet(), row[0], row[1], src);
      workName(w, n, taxon, true, false);
    }
    String body = get("/feeds/taxon/" + taxon + ".atom").body();
    assertThat(parse(body).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(2);
    assertThat(body).contains("Undated now").contains("Undated no year").doesNotContain("Undated old");
  }

  @Test
  void revokedTokenIs404() throws Exception {
    assertThat(get("/feeds/s/doesnotexisttoken.atom").statusCode()).isEqualTo(404);
    long user = jdbc.queryForObject("INSERT INTO app_user (email, email_verified, digest_since) VALUES (?, now(), now()) RETURNING id",
        Long.class, "feedr" + SEQ.incrementAndGet() + "@example.org");
    String token = "rev" + SEQ.incrementAndGet() + "abcdefghijklmnop";
    long sub = jdbc.queryForObject("INSERT INTO subscription (user_id, type, target, label, feed_token) VALUES (?, 'TAXON', 'T2', 'x', ?) RETURNING id",
        Long.class, user, token);
    assertThat(get("/feeds/s/" + token + ".atom").statusCode()).isEqualTo(200);
    jdbc.update("UPDATE subscription SET feed_token = 'rotated' || id WHERE id = ?", sub);
    assertThat(get("/feeds/s/" + token + ".atom").statusCode()).isEqualTo(404);
  }

  @Test
  void publicFeedMentionedIn() throws Exception {
    long src = source();
    String taxon = "FM" + SEQ.incrementAndGet();
    long n = name("Mentioned one");
    long abs = work("In abstract", LocalDate.now(ZoneOffset.UTC).minusDays(2), src);
    long ttl = work("In title", LocalDate.now(ZoneOffset.UTC).minusDays(2), src);
    workName(abs, n, taxon, true, false);
    workName(ttl, n, taxon, true, false);
    jdbc.update("UPDATE work_name SET in_abstract = false, in_title = true, body_count = 0 WHERE work_id = ?", ttl);
    Document d = parse(get("/feeds/taxon/" + taxon + ".atom?mentionedIn=TITLE").body());
    assertThat(d.getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(0).getTextContent()).contains("In title");
    assertThat(parse(get("/feeds/taxon/" + taxon + ".atom").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(2);
    assertThat(parse(get("/feeds/name/" + n + ".atom?mentionedIn=ABSTRACT").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(get("/feeds/taxon/" + taxon + ".atom?mentionedIn=NOPE").statusCode()).isEqualTo(400);
  }

  @Test
  void taxgroupFeedTestsGroupAndPartOnTheSameName() throws Exception {
    long src = source();
    long mammal = name("Mammal one");
    long beetle = name("Beetle one");
    long w = work("Mammal title, beetle body", LocalDate.now(ZoneOffset.UTC).minusDays(2), src);
    workName(w, mammal, "TXG1", true, false);
    workName(w, beetle, "TXG1", true, false);
    jdbc.update("UPDATE work_name SET in_abstract = false, in_title = true, body_count = 0, taxgroup = 'Mammalia' WHERE work_id = ? AND name_string_id = ?", w, mammal);
    jdbc.update("UPDATE work_name SET in_abstract = false, in_title = false, body_count = 3, taxgroup = 'Coleoptera' WHERE work_id = ? AND name_string_id = ?", w, beetle);
    jdbc.update("UPDATE work SET taxgroups = '{Mammalia,Coleoptera,Arthropods}' WHERE id = ?", w);
    assertThat(parse(get("/feeds/taxgroup/Arthropods.atom").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(parse(get("/feeds/taxgroup/Arthropods.atom?mentionedIn=TITLE").body()).getElementsByTagNameNS(NS, "entry").getLength()).isZero();
    assertThat(parse(get("/feeds/taxgroup/Arthropods.atom?mentionedIn=BODY").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
  }

  @Test
  void emptyMentionedInMeansAnywhere() throws Exception {
    long src = source();
    String taxon = "FE" + SEQ.incrementAndGet();
    workName(work("Anywhere", LocalDate.now(ZoneOffset.UTC).minusDays(2), src), name("Empty one"), taxon, true, false);
    assertThat(parse(get("/feeds/taxon/" + taxon + ".atom?mentionedIn=").body()).getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
  }

  @Test
  void entryShowsEnglishTitle() throws Exception {
    long src = source();
    String taxon = "FE" + SEQ.incrementAndGet();
    long n = name("Aus bus");
    long zh = work("中文标题", LocalDate.now(ZoneOffset.UTC).minusDays(2), src);
    long en = work("An English one", LocalDate.now(ZoneOffset.UTC).minusDays(3), src);
    jdbc.update("UPDATE work SET language = 'zh', title_lang = 'zh', title_en = 'Chinese title', title_en_kind = 'MACHINE' WHERE id = ?", zh);
    jdbc.update("UPDATE work SET language = 'en' WHERE id = ?", en);
    workName(zh, n, taxon, true, false);
    workName(en, n, taxon, true, false);
    Document d = parse(get("/feeds/taxon/" + taxon + ".atom").body());
    var entries = d.getElementsByTagNameNS(NS, "entry");
    assertThat(entries.getLength()).isEqualTo(2);
    String all = d.getDocumentElement().getTextContent();
    assertThat(all).contains("Zootaxa").contains("\nMachine translation: Chinese title");
    assertThat(all).doesNotContain("English title:");
  }
}
