package org.catalogueoflife.nametracker.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.nametracker.indexer.layer.TextIn;
import org.junit.jupiter.api.Test;

class ParallelTextsTest {

  private static final ParallelTexts P = new ParallelTexts(
      new LanguageDetector(new LanguageProperties(0.80f, 0.10f, 3, 100, false)));

  private static final String EN_TITLE = "Two new species of cockroaches from southwestern China";
  private static final String ES_TITLE = "Dos especies nuevas de cucarachas del suroeste de China";

  @Test
  void duplicateOfPrimaryDropped() {
    List<TextIn> out = P.of(EN_TITLE, "<p>An abstract.</p>", List.of(
        new TextIn("TITLE", "en", "PARALLEL", " two NEW species of cockroaches from southwestern China. "),
        new TextIn("ABSTRACT", "en", "PARALLEL", "An  abstract."),
        new TextIn("TITLE", "es", "PARALLEL", ES_TITLE)));
    assertThat(out).containsExactly(new TextIn("TITLE", "es", "PARALLEL", ES_TITLE));
  }

  @Test
  void onePerFieldAndLanguage() {
    List<TextIn> out = P.of("Titel", null, List.of(
        new TextIn("TITLE", "es", "PARALLEL", ES_TITLE),
        new TextIn("TITLE", "spa", "PARALLEL", "Otra versión del título en español"),
        new TextIn("TITLE", "es", "ORIGINAL", ES_TITLE),
        new TextIn("TITLE", "en", "PARALLEL", EN_TITLE)));
    assertThat(out).extracting(TextIn::lang, TextIn::kind, TextIn::text).containsExactly(
        org.assertj.core.groups.Tuple.tuple("es", "PARALLEL", ES_TITLE),
        org.assertj.core.groups.Tuple.tuple("es", "ORIGINAL", ES_TITLE),
        org.assertj.core.groups.Tuple.tuple("en", "PARALLEL", EN_TITLE));
  }

  @Test
  void untaggedVersionDetectedOrDropped() {
    List<TextIn> out = P.of("x", null, List.of(
        new TextIn("TITLE", null, "PARALLEL", ES_TITLE),
        new TextIn("TITLE", null, "PARALLEL", "Notes"),
        new TextIn("TITLE", "und", "PARALLEL", "Notes again")));
    assertThat(out).containsExactly(new TextIn("TITLE", "es", "PARALLEL", ES_TITLE));
  }
}
