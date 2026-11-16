package org.catalogueoflife.nametracker.web.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
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
    long recent = work("Recent <i>Aus bus</i> & co", LocalDate.now().minusDays(5), src);
    long ref = work("Only cited", LocalDate.now().minusDays(5), src);
    long minor = work("Minor mention", LocalDate.now().minusDays(5), src);
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
    workName(work("Old", LocalDate.now().minusDays(200), src), n, taxon, true, false);
    workName(work("Border", LocalDate.now().minusDays(90), src), n, taxon, true, false);
    workName(work("Future", LocalDate.now().plusDays(30), src), n, taxon, true, false);
    Document d = parse(get("/feeds/taxon/" + taxon + ".atom").body());
    assertThat(d.getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(1);
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(0).getTextContent()).contains("Border");
  }

  @Test
  void sourceNameAndGroupFeeds() throws Exception {
    long src = source();
    long n = name("Cited");
    long w = work("By source", LocalDate.now().minusDays(1), src);
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
    long w1 = work("First", LocalDate.now().minusDays(400), src);
    long w2 = work("Second", LocalDate.now().minusDays(2), src);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, 'ARTICLE', now() - interval '2 days', "
        + "'[{\"name\":\"Carabus xus\",\"nameStringId\":1}]'::jsonb)", sub, w1);
    jdbc.update("INSERT INTO hit (subscription_id, work_id, kind, created, names) VALUES (?, ?, 'NEW_NAMES', now(), "
        + "'[{\"name\":\"Aus bus sp. nov.\",\"nameStringId\":2,\"actTypes\":[\"SP_NOV\"]}]'::jsonb)", sub, w2);
    HttpResponse<String> r = get("/feeds/s/" + token + ".atom");
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.headers().firstValue("Cache-Control").orElse("")).isEqualTo("private, no-store");
    assertThat(r.headers().firstValue("Set-Cookie")).isEmpty();
    Document d = parse(r.body());
    assertThat(d.getElementsByTagNameNS(NS, "title").item(0).getTextContent()).isEqualTo("Garrulus: My beetles");
    assertThat(d.getElementsByTagNameNS(NS, "entry").getLength()).isEqualTo(2);
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(0).getTextContent()).contains("Second").contains("new: Aus bus sp. nov.");
    assertThat(d.getElementsByTagNameNS(NS, "entry").item(1).getTextContent()).contains("Carabus xus");
    assertThat(r.body()).doesNotContain("@example.org");
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
}
