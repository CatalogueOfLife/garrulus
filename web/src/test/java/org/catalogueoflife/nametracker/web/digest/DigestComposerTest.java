package org.catalogueoflife.nametracker.web.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.web.api.public_.dto.SourceRef;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkSummary;
import org.catalogueoflife.nametracker.web.digest.DigestComposer.Mail;
import org.catalogueoflife.nametracker.web.digest.DigestComposer.Paper;
import org.catalogueoflife.nametracker.web.digest.DigestComposer.SubscriptionHits;
import org.junit.jupiter.api.Test;

class DigestComposerTest {

  static final String URL = "https://garrulus.example";
  static final AppUser USER = new AppUser(5L, "a@example.org", Instant.EPOCH, null, null, "UTC", "DAILY", 1, 7,
      Instant.EPOCH, Instant.EPOCH, null);
  static final Instant T0 = Instant.parse("2026-11-16T00:00:00Z");

  final UnsubscribeTokens tokens = new UnsubscribeTokens("composer key");
  final DigestComposer composer = new DigestComposer(tokens);

  static Paper paper(long hitId, long subscriptionId, long workId, String kind, String names, boolean hasActs,
      String title) {
    Hit h = new Hit(hitId, subscriptionId, workId, kind, T0.plusSeconds(hitId), names, null);
    WorkSummary w = new WorkSummary(workId, "10.1/w" + workId, title, 2026, LocalDate.of(2026, 11, 1),
        new SourceRef(3, "Zootaxa"), List.of(), 0, "ABSTRACT_ONLY", hasActs, List.of());
    return new Paper(h, w);
  }

  static String article(String name) {
    return "[{\"nameStringId\":1,\"name\":\"" + name + "\",\"actTypes\":[]}]";
  }

  @Test
  void capsAt20WithMoreLink() {
    List<Paper> papers = new ArrayList<>();
    for (int i = 1; i <= 23; i++) {
      papers.add(paper(i, 9, 100 + i, "ARTICLE", article("Carabus x" + i), false, "Paper " + i));
    }
    Mail m = composer.compose(USER, List.of(new SubscriptionHits(9, "Carabidae", papers)), URL);
    assertThat(m.subject()).isEqualTo("Garrulus: 23 new papers");
    // newest first: hits 23..4 are listed, 3..1 are not
    assertThat(m.text()).contains("Paper 23").contains("Paper 4\n").doesNotContain("Paper 3\n")
        .doesNotContain("Paper 1\n");
    assertThat(m.text()).contains("and 3 more: " + URL + "/hits?subscription=9");
    assertThat(m.html()).contains("and 3 more").contains(URL + "/hits?subscription=9");
    assertThat(m.text()).contains(URL + "/work/123").contains("https://doi.org/10.1/w123");
    // footer: settings and unsubscribe
    assertThat(m.text()).contains(URL + "/account").contains(URL + "/unsubscribe?t=" + tokens.create(5));
    assertThat(m.html()).contains(URL + "/account").contains(URL + "/unsubscribe?t=" + tokens.create(5));
    assertThat(m.text() + m.html()).doesNotContain("a@example.org");
  }

  @Test
  void newNamesFirst() {
    Paper newest = paper(30, 9, 1, "ARTICLE", article("Carabus plain"), false, "Plain newest");
    Paper withActs = paper(10, 9, 2, "ARTICLE",
        "[{\"nameStringId\":2,\"name\":\"Carabus actus\",\"actTypes\":[\"SP_NOV\"]}]", true, "With acts");
    Paper newNames = paper(5, 9, 3, "NEW_NAMES",
        "[{\"nameStringId\":3,\"name\":\"Carabus novus\",\"actTypes\":[\"COMB_NOV\"]}]", false, "New names");
    Paper oldPlain = paper(1, 9, 4, "ARTICLE", article("Carabus vetus"), false, "Plain oldest");
    Mail m = composer.compose(USER, List.of(new SubscriptionHits(9, "Carabidae",
        List.of(oldPlain, newest, newNames, withActs))), URL);
    String t = m.text();
    assertThat(t.indexOf("With acts")).isLessThan(t.indexOf("New names"));
    assertThat(t.indexOf("New names")).isLessThan(t.indexOf("Plain newest"));
    assertThat(t.indexOf("Plain newest")).isLessThan(t.indexOf("Plain oldest"));
    // each name carries its own act label, not a generic "new"
    assertThat(t).contains("Carabus novus (comb. nov.)").contains("Carabus actus (sp. nov.)").contains("Carabus plain")
        .doesNotContain("Carabus plain (").doesNotContain("(new)");
    assertThat(m.html()).contains("Carabus novus").contains(">comb. nov.<").contains(">sp. nov.<")
        .doesNotContain(">new<");
  }

  @Test
  void groupsByLabelAndMergesKindsOfOneWork() {
    Paper a = paper(1, 7, 50, "ARTICLE", article("Aus bus"), false, "Shared paper");
    Paper n = paper(2, 7, 50, "NEW_NAMES",
        "[{\"nameStringId\":4,\"name\":\"Aus cus\",\"actTypes\":[\"SP_NOV\"]}]", false, "Shared paper");
    Paper other = paper(3, 8, 50, "ARTICLE", article("Aus bus"), false, "Shared paper");
    Mail m = composer.compose(USER, List.of(new SubscriptionHits(8, "zebra follows", List.of(other)),
        new SubscriptionHits(7, "Aus", List.of(a, n))), URL);
    // one paper in two subscriptions is one new paper
    assertThat(m.subject()).isEqualTo("Garrulus: 1 new paper");
    assertThat(m.text().indexOf("Aus (1)")).isLessThan(m.text().indexOf("zebra follows (1)"));
    String aus = m.text().substring(m.text().indexOf("Aus (1)"), m.text().indexOf("zebra follows"));
    assertThat(aus.split("Shared paper", -1)).hasSize(2);
    assertThat(aus).contains("Aus bus").contains("Aus cus (sp. nov.)");
  }

  @Test
  void checklistPaperShowsTenNamesActsAndMainSubjectsFirst() {
    StringBuilder names = new StringBuilder("[");
    for (int i = 1; i <= 30; i++) {
      boolean main = i == 12 || i == 20;
      String acts = i == 25 ? "[\"SP_NOV\"]" : i == 28 ? "[\"TYPE_DESIGNATION\",\"STAT_REV\"]" : "[]";
      names.append(i == 1 ? "" : ",").append("{\"nameStringId\":").append(i).append(",\"name\":\"Name n")
          .append(i).append("\",\"actTypes\":").append(acts).append(",\"mainSubject\":").append(main).append('}');
    }
    names.append(']');
    Paper p = paper(1, 9, 1, "ARTICLE", names.toString(), true, "A checklist");
    Mail m = composer.compose(USER, List.of(new SubscriptionHits(9, "Zootaxa", List.of(p))), URL);
    String line = m.text().lines().filter(l -> l.startsWith("  Names: ")).findFirst().orElseThrow();
    assertThat(line).isEqualTo("  Names: Name n25 (sp. nov.), Name n28 (type designation, stat. rev.), Name n12, "
        + "Name n20, Name n1, Name n2, Name n3, Name n4, Name n5, Name n6, +20 more");
    assertThat(m.html()).contains("+20 more").doesNotContain("Name n7<").doesNotContain("Name n30<");
  }

  @Test
  void doiLinksArePercentEncoded() {
    String sici = "10.1002/(SICI)1097-4636(199706)35:4<477::AID-JBM8>3.0.CO;2-#";
    Paper p = new Paper(new Hit(1L, 9, 1, "ARTICLE", T0, article("A b"), null), new WorkSummary(1, sici, "SICI paper",
        1997, null, null, List.of(), 0, "ABSTRACT_ONLY", false, List.of()));
    Mail m = composer.compose(USER, List.of(new SubscriptionHits(9, "x", List.of(p))), URL);
    String link = "https://doi.org/10.1002/(SICI)1097-4636(199706)35:4%3C477::AID-JBM8%3E3.0.CO;2-%23";
    assertThat(m.text()).contains(link + "\n");
    assertThat(m.html()).contains("href=\"" + link + "\"").doesNotContain("<477").doesNotContain("2-#");
  }

  @Test
  void subjectPlural() {
    Mail one = composer.compose(USER, List.of(new SubscriptionHits(9, "x",
        List.of(paper(1, 9, 1, "ARTICLE", article("A b"), false, "T")))), URL);
    assertThat(one.subject()).isEqualTo("Garrulus: 1 new paper");
    Mail two = composer.compose(USER, List.of(new SubscriptionHits(9, "x",
        List.of(paper(1, 9, 1, "ARTICLE", article("A b"), false, "T"),
            paper(2, 9, 2, "ARTICLE", article("A c"), false, "U")))), URL);
    assertThat(two.subject()).isEqualTo("Garrulus: 2 new papers");
  }

  @Test
  void htmlEscapesTitles() {
    Paper p = paper(1, 9, 1, "ARTICLE", article("Aus <b>bus</b>"), false, "A <script>alert(1)</script> & \"co\"");
    Mail m = composer.compose(USER, List.of(new SubscriptionHits(9, "<i>label</i>", List.of(p))), URL);
    assertThat(m.html()).doesNotContain("<script>").doesNotContain("<i>label</i>").doesNotContain("<b>bus</b>")
        .contains("A &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;co&quot;")
        .contains("&lt;i&gt;label&lt;/i&gt;");
    // plain text keeps the text as it is
    assertThat(m.text()).contains("A <script>alert(1)</script> & \"co\"");
  }
}
