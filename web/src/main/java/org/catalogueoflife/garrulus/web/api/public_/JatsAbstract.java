package org.catalogueoflife.garrulus.web.api.public_;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.catalogueoflife.garrulus.store.work.TitleText;
import org.jspecify.annotations.Nullable;

/**
 * The plain text of a Crossref JATS abstract ({@code work.abstract_jats}) for display: one paragraph per
 * {@code <jats:p>}, separated by a blank line, each cleaned like a title (markup removed, entities decoded, whitespace
 * collapsed). A heading that only says "Abstract" or "Summary" is dropped; any other heading (the sections of a
 * structured abstract: Background, Results …) leads its first paragraph as "Background: …".
 */
final class JatsAbstract {

  private static final Pattern PART = Pattern.compile("(?is)<(?:jats:)?(title|p)\\b[^<>]*>(.*?)</(?:jats:)?\\1\\s*>");
  private static final Pattern TITLE = Pattern.compile("(?is)<(?:jats:)?title\\b[^<>]*>.*?</(?:jats:)?title\\s*>");
  private static final Pattern ABSTRACT_LABEL = Pattern.compile(
      "(?iu)(abstract|summary|résumé|resumen|resumo|zusammenfassung|riassunto|аннотация|摘要)[\\s\\p{P}]*");
  private static final Pattern LABEL_END = Pattern.compile("[\\s:.\\-–—]+$");

  private JatsAbstract() {}

  /** The abstract as plain paragraphs, or null if there is no text in it. */
  static @Nullable String plain(@Nullable String jats) {
    if (jats == null || jats.isBlank()) {
      return null;
    }
    List<String> paras = new ArrayList<>();
    String label = null;
    Matcher m = PART.matcher(jats);
    while (m.find()) {
      String text = TitleText.clean(m.group(2));
      if (m.group(1).equalsIgnoreCase("title")) {
        label = text == null || ABSTRACT_LABEL.matcher(text).matches() ? null : LABEL_END.matcher(text).replaceAll("");
      } else if (text != null) {
        paras.add(label == null || label.isEmpty() ? text : label + ": " + text);
        label = null;
      }
    }
    if (paras.isEmpty()) {
      // no paragraph markup: the whole fragment without its headings
      return TitleText.clean(TITLE.matcher(jats).replaceAll(" "));
    }
    return String.join("\n\n", paras);
  }
}
