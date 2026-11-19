package org.catalogueoflife.nametracker.store.work;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Plain text from a work title as publisher metadata delivers it: HTML/XML entities decoded, markup ({@code <i>},
 * {@code <jats:italic>}, {@code <mml:mi>}, comments …) removed with the text inside kept, every whitespace run one
 * space, trimmed. Only what looks like a tag ({@code <} followed by a letter, {@code /} or {@code !}) is removed, so
 * "pH < 5" survives. When markup was removed, the spaces it leaves inside brackets and before punctuation go too:
 * "( Prunus salicina )" becomes "(Prunus salicina)". Idempotent; a clean title stays as it is (whitespace aside).
 */
public final class TitleText {

  private static final Pattern TAG = Pattern.compile("<[a-zA-Z/!][^<>]*>");
  /** Tags that separate words: replaced by a space, all others by nothing (CO<sub>2</sub> is CO2). */
  private static final Pattern BREAK_TAG = Pattern.compile("(?i)</?(br|p|div|title|jats:title|jats:p)\\b[^<>]*>");
  private static final Pattern ENTITY = Pattern.compile("&(?:#([0-9]{1,7})|#[xX]([0-9a-fA-F]{1,6})|([a-zA-Z][a-zA-Z0-9]{1,31}));");
  private static final Pattern ACCENTED = Pattern.compile("([a-zA-Z])(acute|grave|circ|uml|tilde|cedil|ring|caron)");
  private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");
  private static final Pattern AFTER_OPEN = Pattern.compile("([(\\[]) ");
  private static final Pattern BEFORE_CLOSE = Pattern.compile(" ([)\\],;:.])");
  private static final int MAX_ROUNDS = 4;

  private static final Map<String, String> NAMED = new HashMap<>();

  static {
    String[] pairs = {
        "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", "\u00A0", "ndash", "–", "mdash", "—",
        "hellip", "…", "lsquo", "‘", "rsquo", "’", "sbquo", "‚", "ldquo", "“", "rdquo", "”", "bdquo", "„",
        "laquo", "«", "raquo", "»", "times", "×", "divide", "÷", "deg", "°", "micro", "µ", "plusmn", "±",
        "middot", "·", "shy", "\u00AD", "thinsp", "\u2009", "ensp", "\u2002", "emsp", "\u2003",
        "copy", "©", "reg", "®", "trade", "™", "minus", "−", "prime", "′", "Prime", "″", "le", "≤", "ge", "≥",
        "szlig", "ß", "aelig", "æ", "AElig", "Æ", "oslash", "ø", "Oslash", "Ø", "oelig", "œ", "OElig", "Œ",
        "eth", "ð", "ETH", "Ð", "thorn", "þ", "THORN", "Þ", "iexcl", "¡", "iquest", "¿", "sect", "§", "para", "¶",
        "dagger", "†", "Dagger", "‡", "bull", "•", "female", "♀", "male", "♂", "frac12", "½", "frac14", "¼",
        "frac34", "¾", "sup1", "¹", "sup2", "²", "sup3", "³", "ordf", "ª", "ordm", "º",
    };
    for (int i = 0; i < pairs.length; i += 2) NAMED.put(pairs[i], pairs[i + 1]);
    String[] greek = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda",
        "mu", "nu", "xi", "omicron", "pi", "rho", "sigmaf", "sigma", "tau", "upsilon", "phi", "chi", "psi", "omega"};
    for (int i = 0; i < greek.length; i++) {
      NAMED.put(greek[i], Character.toString(0x03B1 + i));
      // no capital final sigma: U+03A2 is unassigned
      if (!greek[i].equals("sigmaf")) {
        NAMED.put(Character.toUpperCase(greek[i].charAt(0)) + greek[i].substring(1), Character.toString(0x0391 + i));
      }
    }
  }

  private TitleText() {
  }

  /** The clean title, or null when nothing is left. */
  public static @Nullable String clean(@Nullable String raw) {
    if (raw == null) return null;
    String s = raw;
    boolean markup = false;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      String stripped = TAG.matcher(BREAK_TAG.matcher(s).replaceAll(" ")).replaceAll("");
      markup |= !stripped.equals(s);
      String decoded = decode(stripped);
      if (decoded.equals(s)) break;
      s = decoded;
    }
    s = SPACES.matcher(s).replaceAll(" ").strip();
    if (markup) {
      s = AFTER_OPEN.matcher(s).replaceAll("$1");
      s = BEFORE_CLOSE.matcher(s).replaceAll("$1");
    }
    return s.isEmpty() ? null : s;
  }

  /** Decodes numeric and the known named entities; anything else stays as it is. */
  public static String decode(String s) {
    if (s.indexOf('&') < 0) return s;
    Matcher m = ENTITY.matcher(s);
    StringBuilder sb = new StringBuilder(s.length());
    while (m.find()) {
      String r = null;
      if (m.group(1) != null) r = codePoint(Integer.parseInt(m.group(1)));
      else if (m.group(2) != null) r = codePoint(Integer.parseInt(m.group(2), 16));
      else r = named(m.group(3));
      m.appendReplacement(sb, Matcher.quoteReplacement(r == null ? m.group() : r));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static @Nullable String codePoint(int cp) {
    if (cp <= 0 || cp > Character.MAX_CODE_POINT || (cp >= 0xD800 && cp <= 0xDFFF)) return null;
    return Character.toString(cp);
  }

  private static @Nullable String named(String name) {
    String r = NAMED.get(name);
    if (r != null) return r;
    Matcher a = ACCENTED.matcher(name);
    if (!a.matches()) return null;
    String mark = switch (a.group(2)) {
      case "acute" -> "\u0301";
      case "grave" -> "\u0300";
      case "circ" -> "\u0302";
      case "uml" -> "\u0308";
      case "tilde" -> "\u0303";
      case "cedil" -> "\u0327";
      case "ring" -> "\u030A";
      default -> "\u030C"; // caron
    };
    String composed = Normalizer.normalize(a.group(1) + mark, Normalizer.Form.NFC);
    return composed.length() == 1 ? composed : null;
  }
}
