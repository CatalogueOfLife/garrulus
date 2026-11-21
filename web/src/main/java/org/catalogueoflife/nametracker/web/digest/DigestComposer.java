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
import org.catalogueoflife.nametracker.store.account.AppUser;
import org.catalogueoflife.nametracker.store.account.Hit;
import org.catalogueoflife.nametracker.store.account.Subscription;
import org.catalogueoflife.nametracker.web.api.public_.EnglishTitle;
import org.catalogueoflife.nametracker.web.api.public_.dto.WorkSummary;
import org.jspecify.annotations.Nullable;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

/**
 * The digest mail, plain text and simple HTML (inline styles, black, white and red). Pure: the same input gives
 * the same mail. Groups are ordered by subscription label; within a group the hits of one work become one paper,
 * papers with new names (a NEW_NAMES hit) or acts come first, then the newest hit first; at most 20 papers per group,
 * then "and N more" linking to the user's hits of that subscription. Names come from the hits' snapshots, composed by
 * {@link HitNames}: acts first (each with its label, e.g. "sp. nov."), then main subjects, at most 10, then "+N more".
 * Every dynamic value in the HTML is escaped.
 */
public final class DigestComposer {

  static final int MAX_PER_SUBSCRIPTION = 20;

  // black, white and one red; mail clients load no web fonts
  private static final String INK = "#000000";
  private static final String GREY = "#6E6E6E";
  private static final String RULE = "#D6D6D6";
  private static final String RED = "#D2381C";
  private static final String FONT = "'Helvetica Neue',Arial,sans-serif";

  public record Mail(String subject, String text, String html) {}

  /** One hit and the work as the public API shows it. */
  public record Paper(Hit hit, WorkSummary work) {}

  /** The papers of one subscription; the label is what the user called it. */
  public record SubscriptionHits(long subscriptionId, @Nullable String label, List<Paper> papers) {}

  /** A work in one group: its hits merged. */
  private record Entry(WorkSummary work, boolean fresh, Instant newest, HitNames names) {}

  /** A follow's XR note (a moved or missing taxon) for the footer. */
  public record XrNote(String label, String note) {}

  /**
   * The follows whose XR note was written after {@code since} (the user's previous sent digest, else digest_since), so
   * that a note appears in one digest only; ordered by label.
   */
  public static List<XrNote> newNotes(List<Subscription> subscriptions, Instant since) {
    return subscriptions.stream()
        .filter(s -> s.xrNote() != null && s.xrNoteAt() != null && s.xrNoteAt().isAfter(since))
        .sorted(Comparator.comparing((Subscription s) -> String.valueOf(s.label()).toLowerCase(Locale.ROOT))
            .thenComparing(Subscription::id))
        .map(s -> new XrNote(s.label() == null || s.label().isBlank() ? "Follow " + s.id() : s.label(), s.xrNote()))
        .toList();
  }

  private final UnsubscribeTokens tokens;

  public DigestComposer(UnsubscribeTokens tokens) {
    this.tokens = tokens;
  }

  public Mail compose(AppUser u, List<SubscriptionHits> groups, String publicUrl) {
    return compose(u, groups, List.of(), publicUrl);
  }

  public Mail compose(AppUser u, List<SubscriptionHits> groups, List<XrNote> notes, String publicUrl) {
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
    html.append("<!doctype html>\n<html><body style=\"margin:0;padding:0;background:#FFFFFF;color:").append(INK)
        .append(";font-family:").append(FONT).append(";font-size:15px;line-height:1.5\">\n")
        .append("<div style=\"max-width:640px;margin:0 auto;padding:16px\">\n")
        .append("<p style=\"margin:0 0 4px 0;font-family:").append(FONT)
        .append(";font-weight:500;font-size:24px;letter-spacing:-0.02em;color:").append(INK)
        .append("\">Garrulus</p>\n")
        .append("<p style=\"margin:0 0 16px 0\">").append(n).append(n == 1 ? " new paper" : " new papers")
        .append(" for the names, taxa and journals you follow.</p>\n");

    for (SubscriptionHits g : sorted) {
      List<Entry> entries = entries(g);
      if (entries.isEmpty()) {
        continue;
      }
      String label = label(g);
      text.append("\n== ").append(label).append(" (").append(entries.size()).append(") ==\n");
      html.append("<h2 style=\"margin:24px 0 8px 0;font-family:").append(FONT).append(";font-weight:500;font-size:19px;color:")
          .append(INK).append("\">").append(esc(label)).append(" <span style=\"font-weight:normal;color:")
          .append(GREY).append("\">(").append(entries.size()).append(")</span></h2>\n");
      for (Entry e : entries.subList(0, Math.min(MAX_PER_SUBSCRIPTION, entries.size()))) {
        paper(e, base, text, html);
      }
      int more = entries.size() - MAX_PER_SUBSCRIPTION;
      if (more > 0) {
        String hits = base + "/hits?subscription=" + g.subscriptionId();
        text.append("\nand ").append(more).append(" more: ").append(hits).append('\n');
        html.append("<p style=\"margin:8px 0\"><a style=\"color:").append(INK).append(";text-decoration:underline\" href=\"")
            .append(esc(hits)).append("\">and ").append(more).append(" more</a></p>\n");
      }
    }

    if (!notes.isEmpty()) {
      text.append("\nChanges to what you follow:\n");
      html.append("<p style=\"margin:24px 0 4px 0;font-weight:600\">Changes to what you follow</p>\n");
      for (XrNote note : notes) {
        text.append("* ").append(note.label()).append(": ").append(note.note()).append('\n');
        html.append("<p style=\"margin:0 0 4px 0;font-size:13px;color:").append(GREY).append("\"><b>")
            .append(esc(note.label())).append("</b>: ").append(esc(note.note())).append("</p>\n");
      }
    }

    text.append("\n-- \nYou get this email because you follow names, taxa or journals on Garrulus.\n")
        .append("Digest settings: ").append(settings).append('\n')
        .append("Unsubscribe from these emails: ").append(unsubscribe).append('\n');
    html.append("<hr style=\"border:none;border-top:1px solid ").append(RULE).append(";margin:24px 0 12px 0\">\n")
        .append("<p style=\"margin:0;font-size:13px;color:").append(GREY)
        .append("\">You get this email because you follow names, taxa or journals on Garrulus. ")
        .append("<a style=\"color:").append(INK).append(";text-decoration:underline\" href=\"").append(esc(settings))
        .append("\">Digest settings</a> &middot; <a style=\"color:").append(INK).append(";text-decoration:underline\" href=\"")
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
          id -> new Entry(p.work(), p.work().hasActs(), created, new HitNames()));
      e.names().add(p.hit());
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
    String english = EnglishTitle.line(w.titleEn(), w.titleEnKind());
    if (english != null) {
      text.append("  ").append(english).append('\n');
    }
    if (!meta.isEmpty()) {
      text.append("  ").append(String.join(", ", meta)).append('\n');
    }
    HitNames.Shown names = e.names().shown();
    if (!names.isEmpty()) {
      text.append("  Names: ").append(names.text()).append('\n');
    }
    text.append("  ").append(page).append('\n');
    if (doi != null) {
      text.append("  ").append(doi).append('\n');
    }

    html.append("<div style=\"margin:0 0 10px 0;padding:10px 0 0 0;border-top:1px solid ").append(RULE)
        .append("\">\n")
        .append("<a style=\"color:").append(INK).append(";font-weight:600;text-decoration:underline\" href=\"")
        .append(esc(page)).append("\">").append(esc(title)).append("</a>\n");
    if (english != null) {
      html.append("<div style=\"font-size:13px;color:").append(GREY).append("\">").append(esc(english))
          .append("</div>\n");
    }
    if (!meta.isEmpty()) {
      html.append("<div style=\"font-size:13px;color:").append(GREY).append("\">")
          .append(esc(String.join(", ", meta))).append("</div>\n");
    }
    if (!names.isEmpty()) {
      html.append("<div style=\"margin-top:4px\">");
      boolean first = true;
      for (HitNames.Name n : names.names()) {
        html.append(first ? "" : ", ").append(SciNames.html(n.name()));
        for (String act : n.acts()) {
          html.append(" <span style=\"color:").append(RED).append("\">").append(esc(act)).append("</span>");
        }
        first = false;
      }
      if (names.more() > 0) {
        html.append(", <span style=\"color:").append(GREY).append("\">+").append(names.more())
            .append(" more</span>");
      }
      html.append("</div>\n");
    }
    if (doi != null) {
      html.append("<div style=\"font-size:13px;margin-top:4px\"><a style=\"color:").append(INK)
          .append(";text-decoration:underline\" href=\"").append(esc(doi)).append("\">").append(esc(doi)).append("</a></div>\n");
    }
    html.append("</div>\n");
  }

  private static String esc(String s) {
    return HtmlUtils.htmlEscape(s, "UTF-8");
  }
}
