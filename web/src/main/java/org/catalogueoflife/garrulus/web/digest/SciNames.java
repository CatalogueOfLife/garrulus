package org.catalogueoflife.garrulus.web.digest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/** Typography of scientific names where the rank is not known: decided by the shape of the name. */
final class SciNames {

  private static final Pattern BINOMIAL_OR_TRINOMIAL = Pattern.compile(
      "^[A-Z][a-z-]+(?: \\([A-Z][a-z-]+\\))? [a-z-]+(?: (?:(?:subsp|ssp|var|subvar|f|forma|morph|ab)\\. )?[a-z-]+)?$");

  /**
   * Rank markers, qualifiers and the hybrid sign, set roman between the italic parts of a name; the same list as the
   * frontend's nameRuns (ui/nameStyle.ts). "sp." counts only after "f." (forma specialis), "x" only between two words.
   */
  private static final Set<String> MARKERS = Set.of(
      "subsp.", "ssp.", "var.", "subvar.", "f.", "forma", "pv.", "bv.", "sv.", "morph", "ab.", "cv.", "cf.", "aff.", "×");

  private static final Pattern WORD_OR_SPACE = Pattern.compile("\\s+|\\S+");

  private SciNames() {}

  /** True for binomials and trinomials (genus and below, italic); false for uninomials and everything else. */
  static boolean italicByShape(String name) {
    return name != null && BINOMIAL_OR_TRINOMIAL.matcher(name.strip()).matches();
  }

  /** The name as escaped HTML, italic by shape, its rank markers and hybrid signs roman. */
  static String html(String name) {
    return runs(name, italicByShape(name));
  }

  /**
   * The name as escaped HTML: for an italic name the parts in {@code <i>}, rank markers and hybrid signs (and the
   * spaces next to them) roman, e.g. {@code <i>Poa annua</i> var. <i>supina</i>}.
   */
  static String runs(String name, boolean italic) {
    if (!italic) {
      return esc(name);
    }
    // words and the whitespace between them; a hybrid sign written onto its epithet ("×piperita") is a word of its own
    List<String> parts = new ArrayList<>();
    Matcher m = WORD_OR_SPACE.matcher(name);
    while (m.find()) {
      String t = m.group();
      if (t.length() > 1 && t.startsWith("×")) {
        parts.add("×");
        parts.add(t.substring(1));
      } else {
        parts.add(t);
      }
    }
    List<String> words = parts.stream().filter(p -> !p.isBlank()).toList();
    boolean[] roman = new boolean[parts.size()];
    int w = 0;
    for (int i = 0; i < parts.size(); i++) {
      if (!parts.get(i).isBlank()) {
        roman[i] = isMarker(words, w++);
      }
    }
    StringBuilder out = new StringBuilder();
    Boolean open = null;
    for (int i = 0; i < parts.size(); i++) {
      String p = parts.get(i);
      // a space is italic only between two italic words
      boolean it = p.isBlank() ? i > 0 && i < parts.size() - 1 && !roman[i - 1] && !roman[i + 1] : !roman[i];
      if (open == null || open != it) {
        if (Boolean.TRUE.equals(open)) {
          out.append("</i>");
        }
        if (it) {
          out.append("<i>");
        }
        open = it;
      }
      out.append(esc(p));
    }
    if (Boolean.TRUE.equals(open)) {
      out.append("</i>");
    }
    return out.toString();
  }

  private static boolean isMarker(List<String> words, int i) {
    String w = words.get(i);
    if (MARKERS.contains(w)) {
      return true;
    }
    if (w.equals("sp.")) {
      return i > 0 && words.get(i - 1).equals("f.");
    }
    return w.equals("x") && i > 0 && i < words.size() - 1;
  }

  private static String esc(String s) {
    return HtmlUtils.htmlEscape(s, "UTF-8");
  }
}
