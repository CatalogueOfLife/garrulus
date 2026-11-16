package org.catalogueoflife.nametracker.web.digest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.finder.api.FinderJson;
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkSummary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

/**
 * The digest mail, plain text and simple HTML (inline styles, brand colours of docs/brand). Pure: the same input gives
 * the same mail. Groups are ordered by subscription label; within a group the hits of one work become one paper,
 * papers with new names (a NEW_NAMES hit) or acts come first, then the newest hit first; at most 20 papers per group,
 * then "and N more" linking to the user's hits of that subscription. Names come from the hits' snapshots; a name of a
 * NEW_NAMES hit or with act types is marked new. Every dynamic value in the HTML is escaped.
 */
public final class DigestComposer {

  private static final Logger LOG = LoggerFactory.getLogger(DigestComposer.class);

  static final int MAX_PER_SUBSCRIPTION = 20;

  // docs/brand/README.md
  private static final String INK_9 = "#100F0F";
  private static final String JAY_6 = "#2A6FBA";
  private static final String FAWN_0 = "#FAF4F1";
  private static final String FAWN_2 = "#E6CDC2";
  private static final String FAWN_7 = "#82594A";

  public record Mail(String subject, String text, String html) {}

  /** One hit and the work as the public API shows it. */
  public record Paper(Hit hit, WorkSummary work) {}

  /** The papers of one subscription; the label is what the user called it. */
  public record SubscriptionHits(long subscriptionId, @Nullable String label, List<Paper> papers) {}

  /** A work in one group: its hits merged. */
  private record Entry(WorkSummary work, boolean fresh, Instant newest, Map<String, Boolean> names) {}

  private final UnsubscribeTokens tokens;

  public DigestComposer(UnsubscribeTokens tokens) {
    this.tokens = tokens;
  }

  public Mail compose(AppUser u, List<SubscriptionHits> groups, String publicUrl) {
    String base = publicUrl.strip().replaceAll("/+$", "");
    List<SubscriptionHits> sorted = groups.stream()
        .sorted(Comparator.comparing((SubscriptionHits g) -> label(g).toLowerCase(Locale.ROOT))
            .thenComparing(SubscriptionHits::subscriptionId))
        .toList();
    Set<Long> works = new HashSet<>();
    groups.forEach(g -> g.papers().forEach(p -> works.add(p.work().id())));
    int n = works.size();
    String subject = "Garrulus: " + n + " new paper" + (n == 1 ? "" : "s");
    String settings = base + "/account";
    String unsubscribe = base + "/unsubscribe?t=" + tokens.create(u.id());

    StringBuilder text = new StringBuilder();
    StringBuilder html = new StringBuilder();
    text.append(n).append(n == 1 ? " new paper" : " new papers").append(" for the names, taxa and journals you follow")
        .append(" on Garrulus.\n");
    html.append("<!doctype html>\n<html><body style=\"margin:0;padding:0;background:#FFFFFF;color:").append(INK_9)
        .append(";font-family:-apple-system,'Segoe UI',Helvetica,Arial,sans-serif;font-size:15px;line-height:1.5\">\n")
        .append("<div style=\"max-width:640px;margin:0 auto;padding:16px\">\n")
        .append("<p style=\"margin:0 0 4px 0;font-family:Georgia,'Times New Roman',serif;font-style:italic;")
        .append("font-weight:600;font-size:24px;color:").append(JAY_6).append("\">Garrulus</p>\n")
        .append("<p style=\"margin:0 0 16px 0\">").append(n).append(n == 1 ? " new paper" : " new papers")
        .append(" for the names, taxa and journals you follow.</p>\n");

    for (SubscriptionHits g : sorted) {
      List<Entry> entries = entries(g);
      if (entries.isEmpty()) {
        continue;
      }
      String label = label(g);
      text.append("\n== ").append(label).append(" (").append(entries.size()).append(") ==\n");
      html.append("<h2 style=\"margin:24px 0 8px 0;font-family:Georgia,'Times New Roman',serif;font-size:19px;color:")
          .append(INK_9).append("\">").append(esc(label)).append(" <span style=\"font-weight:normal;color:")
          .append(FAWN_7).append("\">(").append(entries.size()).append(")</span></h2>\n");
      for (Entry e : entries.subList(0, Math.min(MAX_PER_SUBSCRIPTION, entries.size()))) {
        paper(e, base, text, html);
      }
      int more = entries.size() - MAX_PER_SUBSCRIPTION;
      if (more > 0) {
        String hits = base + "/hits?subscription=" + g.subscriptionId();
        text.append("\nand ").append(more).append(" more: ").append(hits).append('\n');
        html.append("<p style=\"margin:8px 0\"><a style=\"color:").append(JAY_6).append("\" href=\"")
            .append(esc(hits)).append("\">and ").append(more).append(" more</a></p>\n");
      }
    }

    text.append("\n-- \nYou get this email because you follow names, taxa or journals on Garrulus.\n")
        .append("Digest settings: ").append(settings).append('\n')
        .append("Unsubscribe from these emails: ").append(unsubscribe).append('\n');
    html.append("<hr style=\"border:none;border-top:1px solid ").append(FAWN_2).append(";margin:24px 0 12px 0\">\n")
        .append("<p style=\"margin:0;font-size:13px;color:").append(FAWN_7)
        .append("\">You get this email because you follow names, taxa or journals on Garrulus. ")
        .append("<a style=\"color:").append(JAY_6).append("\" href=\"").append(esc(settings))
        .append("\">Digest settings</a> &middot; <a style=\"color:").append(JAY_6).append("\" href=\"")
        .append(esc(unsubscribe)).append("\">Unsubscribe</a></p>\n</div>\n</body></html>\n");
    return new Mail(subject, text.toString(), html.toString());
  }

  private static String label(SubscriptionHits g) {
    return g.label() == null || g.label().isBlank() ? "Follow " + g.subscriptionId() : g.label();
  }

  /** One entry per work, new names and acts first, then the newest hit first. */
  private static List<Entry> entries(SubscriptionHits g) {
    Map<Long, Entry> byWork = new LinkedHashMap<>();
    for (Paper p : g.papers()) {
      boolean newNames = "NEW_NAMES".equals(p.hit().kind());
      Instant created = p.hit().created() == null ? Instant.EPOCH : p.hit().created();
      Entry e = byWork.computeIfAbsent(p.work().id(),
          id -> new Entry(p.work(), p.work().hasActs(), created, new LinkedHashMap<>()));
      for (JsonNode n : names(p.hit())) {
        String name = n.path("name").asString("");
        if (name.isBlank()) {
          continue;
        }
        boolean fresh = newNames || (n.path("actTypes").isArray() && !n.path("actTypes").isEmpty());
        e.names().merge(name, fresh, Boolean::logicalOr);
      }
      boolean fresh = e.fresh() || newNames;
      Instant newest = created.isAfter(e.newest()) ? created : e.newest();
      byWork.put(p.work().id(), new Entry(e.work(), fresh, newest, e.names()));
    }
    List<Entry> out = new ArrayList<>(byWork.values());
    out.sort(Comparator.comparing(Entry::fresh).reversed()
        .thenComparing(Entry::newest, Comparator.reverseOrder())
        .thenComparing(e -> e.work().id(), Comparator.reverseOrder()));
    return out;
  }

  private static Iterable<JsonNode> names(Hit h) {
    try {
      JsonNode arr = FinderJson.mapper().readTree(h.names() == null ? "[]" : h.names());
      return arr.isArray() ? arr : List.of();
    } catch (RuntimeException e) {
      LOG.warn("Unreadable names of hit {}: {}", h.id(), e.getClass().getSimpleName());
      return List.of();
    }
  }

  private static void paper(Entry e, String base, StringBuilder text, StringBuilder html) {
    WorkSummary w = e.work();
    String title = w.title() == null || w.title().isBlank() ? "(untitled)" : w.title().strip();
    String page = base + "/work/" + w.id();
    // DOIs may hold # ? < > or spaces (SICI): percent-encoded as a path, then HTML-escaped where written
    String doi = w.doi() == null || w.doi().isBlank() ? null
        : "https://doi.org/" + UriUtils.encodePath(w.doi().strip(), StandardCharsets.UTF_8);
    List<String> meta = new ArrayList<>();
    if (w.source() != null && w.source().title() != null) {
      meta.add(w.source().title());
    }
    if (w.publicationDate() != null) {
      meta.add(w.publicationDate().toString());
    } else if (w.year() != null) {
      meta.add(w.year().toString());
    }

    text.append("\n* ").append(title).append('\n');
    if (!meta.isEmpty()) {
      text.append("  ").append(String.join(", ", meta)).append('\n');
    }
    if (!e.names().isEmpty()) {
      List<String> names = new ArrayList<>();
      e.names().forEach((name, fresh) -> names.add(fresh ? name + " (new)" : name));
      text.append("  Names: ").append(String.join(", ", names)).append('\n');
    }
    text.append("  ").append(page).append('\n');
    if (doi != null) {
      text.append("  ").append(doi).append('\n');
    }

    html.append("<div style=\"margin:0 0 10px 0;padding:10px 12px;background:").append(FAWN_0)
        .append(";border:1px solid ").append(FAWN_2).append(";border-radius:6px\">\n")
        .append("<a style=\"color:").append(JAY_6).append(";font-weight:600;text-decoration:none\" href=\"")
        .append(esc(page)).append("\">").append(esc(title)).append("</a>\n");
    if (!meta.isEmpty()) {
      html.append("<div style=\"font-size:13px;color:").append(FAWN_7).append("\">")
          .append(esc(String.join(", ", meta))).append("</div>\n");
    }
    if (!e.names().isEmpty()) {
      html.append("<div style=\"margin-top:4px\">");
      boolean first = true;
      for (Map.Entry<String, Boolean> n : e.names().entrySet()) {
        html.append(first ? "" : ", ").append("<i>").append(esc(n.getKey())).append("</i>");
        if (n.getValue()) {
          html.append(" <span style=\"background:").append(FAWN_7).append(";color:#FFFFFF;font-size:11px;")
              .append("padding:1px 5px;border-radius:4px\">new</span>");
        }
        first = false;
      }
      html.append("</div>\n");
    }
    if (doi != null) {
      html.append("<div style=\"font-size:13px;margin-top:4px\"><a style=\"color:").append(JAY_6)
          .append("\" href=\"").append(esc(doi)).append("\">").append(esc(doi)).append("</a></div>\n");
    }
    html.append("</div>\n");
  }

  private static String esc(String s) {
    return HtmlUtils.htmlEscape(s, "UTF-8");
  }
}
