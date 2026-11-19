package org.catalogueoflife.nametracker.indexer.layer.magtech;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.nametracker.indexer.layer.oai.OaiPmhClient;
import org.catalogueoflife.nametracker.store.work.TitleText;

/**
 * Turns the HTML abstract of a Magtech RSS item into the JATS the finder reads: {@code <p>} becomes
 * {@code <jats:p>}, {@code <i>}/{@code <em>} {@code <jats:italic>}, {@code <sub>}/{@code <sup>} their JATS forms; every
 * other tag (strong, b, span, …) is dropped and its text kept. Entities are decoded and the text escaped again.
 */
final class MagtechAbstracts {

  private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b[^<>]*?(/?)>");
  private static final Set<String> BLOCKS = Set.of("br", "div", "li", "ul", "ol", "tr", "td", "th", "table", "tbody",
      "thead", "h1", "h2", "h3", "h4", "h5", "h6", "section", "blockquote", "pre", "hr", "dd", "dt", "dl");

  private MagtechAbstracts() {
  }

  static String toJats(String html) {
    String jats = build(html);
    try {
      OaiPmhClient.parse(("<r xmlns:jats=\"http://jats.nlm.nih.gov\">" + jats + "</r>").getBytes(StandardCharsets.UTF_8));
      return jats;
    } catch (Exception e) { // the safety net: the plain text in one paragraph
      String plain = TitleText.clean(html);
      return plain == null ? "" : "<jats:p>" + escape(plain) + "</jats:p>";
    }
  }

  /** Tags are paired on a stack: unmatched closers are dropped, open inline tags are closed at paragraph ends. */
  private static String build(String html) {
    StringBuilder out = new StringBuilder();
    Deque<String> open = new ArrayDeque<>(); // the open JATS elements, innermost first; "p" is the outermost
    int pos = 0;
    Matcher m = TAG.matcher(html);
    while (m.find()) {
      text(out, open, html.substring(pos, m.start()));
      pos = m.end();
      boolean closing = !m.group(1).isEmpty();
      String name = m.group(2).toLowerCase(Locale.ROOT);
      String jats = switch (name) {
        case "p" -> "p";
        case "i", "em" -> "italic";
        case "sub" -> "sub";
        case "sup" -> "sup";
        default -> null;
      };
      if (jats == null) {
        if (BLOCKS.contains(name)) space(out, open);
        continue;
      }
      if (!m.group(3).isEmpty()) continue; // an empty element (<p/>)
      if (closing) {
        if (open.contains(jats)) closeTo(out, open, jats);
      } else if (jats.equals("p")) {
        closeTo(out, open, "p");
        open.push("p");
        out.append("<jats:p>");
      } else {
        if (open.isEmpty()) {
          open.push("p");
          out.append("<jats:p>");
        }
        open.push(jats);
        out.append("<jats:").append(jats).append('>');
      }
    }
    text(out, open, html.substring(pos));
    while (!open.isEmpty()) out.append("</jats:").append(open.pop()).append('>');
    return out.toString().strip();
  }

  /** Closes elements up to and including the named one, when it is open. */
  private static void closeTo(StringBuilder out, Deque<String> open, String name) {
    if (!open.contains(name)) return;
    while (true) {
      String e = open.pop();
      out.append("</jats:").append(e).append('>');
      if (e.equals(name)) return;
    }
  }

  private static void space(StringBuilder out, Deque<String> open) {
    if (!open.isEmpty() && out.charAt(out.length() - 1) != ' ') out.append(' ');
  }

  /** Decoded and escaped; text outside a paragraph opens one, whitespace-only text there is dropped. */
  private static void text(StringBuilder out, Deque<String> open, String raw) {
    if (raw.isEmpty()) return;
    String s = TitleText.decode(raw);
    if (s.isBlank() && open.isEmpty()) return;
    if (open.isEmpty()) {
      open.push("p");
      out.append("<jats:p>");
    }
    out.append(escape(s).replaceAll("[ \\t\\r\\n]+", " "));
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
