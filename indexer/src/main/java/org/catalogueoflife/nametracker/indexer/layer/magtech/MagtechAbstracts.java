package org.catalogueoflife.nametracker.indexer.layer.magtech;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the HTML abstract of a Magtech RSS item into the JATS the finder reads: {@code <p>} becomes
 * {@code <jats:p>}, {@code <i>}/{@code <em>} {@code <jats:italic>}, {@code <sub>}/{@code <sup>} their JATS forms; every
 * other tag (strong, b, span, …) is dropped and its text kept. Entities are decoded and the text escaped again.
 */
final class MagtechAbstracts {

  private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)\\b[^<>]*?(/?)>");
  private static final Pattern ENTITY = Pattern.compile("&(?:#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6})|([a-zA-Z][a-zA-Z0-9]{1,31}));");
  private static final Map<String, String> NAMED = Map.ofEntries(Map.entry("amp", "&"), Map.entry("lt", "<"),
      Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
      Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("times", "×"),
      Map.entry("deg", "°"), Map.entry("plusmn", "±"), Map.entry("micro", "µ"),
      Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"),
      Map.entry("rdquo", "”"), Map.entry("hellip", "…"), Map.entry("eacute", "é"),
      Map.entry("egrave", "è"), Map.entry("uuml", "ü"), Map.entry("ouml", "ö"),
      Map.entry("auml", "ä"), Map.entry("alpha", "α"), Map.entry("beta", "β"),
      Map.entry("mu", "μ"), Map.entry("le", "≤"), Map.entry("ge", "≥"));

  private MagtechAbstracts() {
  }

  static String toJats(String html) {
    StringBuilder out = new StringBuilder();
    int pos = 0;
    Matcher m = TAG.matcher(html);
    while (m.find()) {
      text(out, html.substring(pos, m.start()));
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
      if (jats == null || !m.group(3).isEmpty()) continue; // dropped, or an empty element (<p/>)
      out.append(closing ? "</jats:" : "<jats:").append(jats).append('>');
    }
    text(out, html.substring(pos));
    return out.toString().strip();
  }

  /** Decoded and escaped; whitespace-only text between paragraphs is dropped. */
  private static void text(StringBuilder out, String raw) {
    if (raw.isEmpty()) return;
    String s = decode(raw);
    if (s.isBlank() && (out.isEmpty() || out.toString().endsWith("</jats:p>"))) {
      return;
    }
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    out.append(s.replaceAll("[ \\t\\r\\n]+", " "));
  }

  private static String decode(String s) {
    if (s.indexOf('&') < 0) return s;
    Matcher m = ENTITY.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String r = null;
      if (m.group(1) != null) r = codePoint(Integer.parseInt(m.group(1)));
      else if (m.group(2) != null) r = codePoint(Integer.parseInt(m.group(2), 16));
      else r = NAMED.get(m.group(3));
      m.appendReplacement(sb, Matcher.quoteReplacement(r == null ? m.group() : r));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String codePoint(int cp) {
    return Character.isValidCodePoint(cp) && cp != 0 ? new String(Character.toChars(cp)) : null;
  }
}
