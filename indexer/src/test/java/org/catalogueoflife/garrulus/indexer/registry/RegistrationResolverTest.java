package org.catalogueoflife.garrulus.indexer.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading a page someone registered: hostile markup must not cost more than a moment of CPU. */
class RegistrationResolverTest {

  private static final URI PAGE = URI.create("https://journal.example.org/index");
  private static final int TWO_MB = 2 * 1024 * 1024;

  private static String fill(String unit) {
    return unit.repeat(TWO_MB / unit.length() + 1).substring(0, TWO_MB);
  }

  @Test
  void hostilePagesAreReadInLinearTime() {
    List<String> pages = List.of(
        fill("<meta "), fill("<meta name=citation_issn content='"), fill("<link rel=alternate href=\""),
        fill("<script>"), fill("<style"), fill("<!--"), fill("<"), fill("<a "), fill("< a"),
        fill("&#1"), fill("&#x"), fill("ISSN "), fill("ISSN:"), fill("pp. 1175-5326 "), fill("1175-5326-"),
        "<html>" + fill("<meta content=\"") + "</html>", fill("<meta name=\"generator\" content=\"Open Journal Systems"));
    for (String html : pages) {
      long start = System.nanoTime();
      RegistrationResolver.Resolution r = RegistrationResolver.readPage(PAGE, html);
      Duration took = Duration.ofNanos(System.nanoTime() - start);
      assertThat(took).as(html.substring(0, 40)).isLessThan(Duration.ofSeconds(2));
      assertThat(r).isNotNull();
    }
  }

  @Test
  void metadataBeyondTheFirst512KbIsNotRead() {
    String late = "<html><body>" + "x".repeat(600 * 1024) + "<meta name=\"citation_issn\" content=\"1175-5326\">";
    assertThat(RegistrationResolver.readPage(PAGE, late).reason()).isEqualTo("NO_ISSN_FOUND");
    String early = "<html><head><meta name=\"citation_issn\" content=\"1175-5326\"></head>" + "x".repeat(600 * 1024);
    assertThat(RegistrationResolver.readPage(PAGE, early).issns()).containsExactly("1175-5326");
  }

  @Test
  void atMostFourIssnsAndATitleOfAtMost500CodePoints() {
    StringBuilder html = new StringBuilder("<html><head>");
    for (String issn : List.of("1175-5326", "1175-5334", "2118-9773", "1313-2989", "1313-2970", "1005-0094")) {
      html.append("<meta name=\"citation_issn\" content=\"").append(issn).append("\">");
    }
    html.append("<meta name=\"citation_journal_title\" content=\"").append("🦜".repeat(600)).append("\">");
    RegistrationResolver.Resolution r = RegistrationResolver.readPage(PAGE, html + "</head></html>");
    assertThat(r.issns()).containsExactly("1175-5326", "1175-5334", "2118-9773", "1313-2989");
    assertThat(r.title().codePointCount(0, r.title().length())).isEqualTo(500);
  }

  @Test
  void scriptsStylesAndCommentsAreNotText() {
    String html = "<html><head><script>var x = 'ISSN 1175-5326';</script><style>/* 1175-5334 */</style>"
        + "<!-- ISSN 2118-9773 --></head><body><p>ISSN&nbsp;1313-2989</p><SCRIPT type=x>1005-0094</SCRIPT></body>";
    assertThat(RegistrationResolver.readPage(PAGE, html).issns()).containsExactly("1313-2989");
  }
}
