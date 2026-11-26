package org.catalogueoflife.garrulus.indexer.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a page a registration reads: its {@code <meta>} and {@code <link>} tags and its text. Built for hostile
 * markup: only the first {@value #SCAN_CHARS} characters are read, in one linear pass without backtracking regular
 * expressions (unclosed tags, quotes, comments or scripts cannot make it quadratic), a tag longer than
 * {@value #MAX_TAG} characters is text, and the pass gives up when its deadline ({@link System#nanoTime()}) passes.
 *
 * @param metas every meta tag's {@code content} by its {@code name} or {@code property}, lower case
 * @param links the attributes of every link tag, attribute names lower case
 * @param text the text: no scripts, styles or comments, tags as spaces, entities decoded, whitespace collapsed
 */
record PageMarkup(Map<String, List<String>> metas, List<Map<String, String>> links, String text) {

  static final int SCAN_CHARS = 512 * 1024;
  static final int MAX_TAG = 4096;
  private static final Set<String> RAW = Set.of("script", "style", "noscript");
  private static final Pattern ENTITY =
      Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|lt|gt|quot|apos|nbsp);");
  private static final Pattern SPACE = Pattern.compile("[\\s\\u00A0]+");

  /** Thrown when the deadline passes. */
  static final class TooSlow extends RuntimeException {
    TooSlow() {
      super("reading the page took too long", null, false, false);
    }
  }

  static PageMarkup scan(String html, long until) {
    String h = html.length() > SCAN_CHARS ? html.substring(0, SCAN_CHARS) : html;
    int n = h.length();
    char[] lower = new char[n];
    for (int i = 0; i < n; i++) {
      char c = h.charAt(i);
      lower[i] = c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }
    String lc = new String(lower);
    int[] nextGt = new int[n + 1]; // the index of the next '>' at or after i, or -1
    nextGt[n] = -1;
    for (int i = n - 1; i >= 0; i--) nextGt[i] = h.charAt(i) == '>' ? i : nextGt[i + 1];

    Map<String, List<String>> metas = new HashMap<>();
    List<Map<String, String>> links = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    int i = 0;
    int steps = 0;
    while (i < n) {
      if ((++steps & 0xFFF) == 0 && System.nanoTime() > until) throw new TooSlow();
      char c = h.charAt(i);
      if (c != '<') {
        text.append(c);
        i++;
        continue;
      }
      if (lc.startsWith("<!--", i)) {
        int end = lc.indexOf("-->", i + 4);
        if (end < 0) break; // an unclosed comment hides the rest
        text.append(' ');
        i = end + 3;
        continue;
      }
      int end = nextGt[i + 1 > n ? n : i + 1];
      if (end < 0 || end - i > MAX_TAG) { // not a tag: a stray '<' or one far too long
        text.append(' ');
        i++;
        continue;
      }
      String tag = h.substring(i + 1, end);
      String name = tagName(tag);
      text.append(' ');
      i = end + 1;
      if (RAW.contains(name)) {
        int close = lc.indexOf("</" + name, i);
        if (close < 0) break; // an unclosed script hides the rest
        int closeEnd = nextGt[close];
        if (closeEnd < 0) break;
        i = closeEnd + 1;
      } else if (name.equals("meta")) {
        Map<String, String> a = attributes(tag);
        String key = a.containsKey("name") ? a.get("name") : a.get("property");
        String content = a.get("content");
        if (key != null && content != null) {
          metas.computeIfAbsent(key.strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
              .add(unescape(content).strip());
        }
      } else if (name.equals("link")) {
        links.add(attributes(tag));
      }
    }
    if (System.nanoTime() > until) throw new TooSlow();
    String t = SPACE.matcher(unescape(text)).replaceAll(" ").strip();
    return new PageMarkup(metas, links, t);
  }

  /** The element name of a tag's inside ({@code meta name=…}), lower case; empty for a closing tag or a declaration. */
  private static String tagName(String tag) {
    int j = 0;
    while (j < tag.length() && Character.isLetterOrDigit(tag.charAt(j))) j++;
    return tag.substring(0, j).toLowerCase(Locale.ROOT);
  }

  /** The attributes of a tag's inside, in one pass: names lower case, the first of a name wins. */
  static Map<String, String> attributes(String tag) {
    Map<String, String> a = new HashMap<>();
    int len = tag.length();
    int p = 0;
    while (p < len && !Character.isWhitespace(tag.charAt(p))) p++; // the element name
    while (p < len) {
      while (p < len && (Character.isWhitespace(tag.charAt(p)) || tag.charAt(p) == '/')) p++;
      int nameStart = p;
      while (p < len && "=\t\n\r\f />\"'".indexOf(tag.charAt(p)) < 0) p++;
      if (p == nameStart) {
        p++;
        continue;
      }
      String name = tag.substring(nameStart, p).toLowerCase(Locale.ROOT);
      while (p < len && Character.isWhitespace(tag.charAt(p))) p++;
      if (p >= len || tag.charAt(p) != '=') {
        a.putIfAbsent(name, "");
        continue;
      }
      p++;
      while (p < len && Character.isWhitespace(tag.charAt(p))) p++;
      String value;
      if (p < len && (tag.charAt(p) == '"' || tag.charAt(p) == '\'')) {
        int close = tag.indexOf(tag.charAt(p), p + 1);
        int stop = close < 0 ? len : close;
        value = tag.substring(p + 1, stop);
        p = stop + 1;
      } else {
        int start = p;
        while (p < len && !Character.isWhitespace(tag.charAt(p))) p++;
        value = tag.substring(start, p);
      }
      a.putIfAbsent(name, value);
    }
    return a;
  }

  static String unescape(CharSequence s) {
    Matcher m = ENTITY.matcher(s);
    StringBuilder b = new StringBuilder();
    while (m.find()) {
      String e = m.group(1);
      String r = switch (e) {
        case "amp" -> "&";
        case "lt" -> "<";
        case "gt" -> ">";
        case "quot" -> "\"";
        case "apos" -> "'";
        case "nbsp" -> " ";
        default -> {
          int cp = e.charAt(1) == 'x' || e.charAt(1) == 'X' ? Integer.parseInt(e.substring(2), 16)
              : Integer.parseInt(e.substring(1));
          yield Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : m.group();
        }
      };
      m.appendReplacement(b, Matcher.quoteReplacement(r));
    }
    m.appendTail(b);
    return b.toString();
  }
}
