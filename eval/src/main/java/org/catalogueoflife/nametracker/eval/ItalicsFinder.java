package org.catalogueoflife.nametracker.eval;

import org.catalogueoflife.nametracker.finder.api.Block;
import org.catalogueoflife.nametracker.finder.api.CodePoints;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.Evidence;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.Generator;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.catalogueoflife.nametracker.finder.api.Style;
import org.catalogueoflife.nametracker.finder.api.StyleSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The trivial baseline: every italic span of one to three words whose first word is capitalised (or an initial such as "O.")
 * becomes a mention with confidence 0.5. Surrounding whitespace and closing punctuation are trimmed off the span.
 */
public class ItalicsFinder implements NameFinder {
  private static final FinderInfo INFO = new FinderInfo("italics", "0.1", "italics-1-3-words", null);
  private static final Pattern INITIAL = Pattern.compile("\\p{Lu}\\.");

  @Override
  public FinderInfo info() {
    return INFO;
  }

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    List<Mention> out = new ArrayList<>();
    int n = 0;
    for (Block b : doc.blocks()) {
      for (StyleSpan s : b.styles()) {
        if (s.style() != Style.ITALIC) continue;
        int[] span = trim(b.text(), s.start(), s.end());
        if (span == null) continue;
        String text = CodePoints.slice(b.text(), span[0], span[1]);
        String[] words = text.split("\\s+");
        if (words.length < 1 || words.length > 3) continue;
        boolean initial = INITIAL.matcher(words[0]).matches();
        if (!initial && !Character.isUpperCase(words[0].codePointAt(0))) continue;
        NameKind kind = initial ? NameKind.ABBREVIATED
            : words.length == 1 ? NameKind.UNINOMIAL : words.length == 2 ? NameKind.BINOMIAL : NameKind.TRINOMIAL;
        out.add(new Mention("i" + (++n), b.id(), span[0], span[1], text, text, kind, null, null, null, 0.5,
            new Evidence(Set.of(Generator.ITALIC), null, null), null, null, Map.of()));
      }
    }
    return new FinderResult(INFO, out, List.of(), List.of(), List.of(), Map.of());
  }

  /** Trims whitespace and opening/closing brackets and separators; null if nothing is left. */
  private static int[] trim(String text, int start, int end) {
    int a = start;
    int b = end;
    while (a < b && isJunk(text, a)) a++;
    while (b > a && isJunk(text, b - 1)) b--;
    return a < b ? new int[] {a, b} : null;
  }

  private static boolean isJunk(String text, int cp) {
    int c = text.codePointAt(CodePoints.toCharIndex(text, cp));
    return Character.isWhitespace(c) || ",;:()[]".indexOf(c) >= 0;
  }
}
