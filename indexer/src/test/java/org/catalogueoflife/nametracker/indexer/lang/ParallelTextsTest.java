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

  private static final String EN_ABSTRACT = "We describe two new species of cockroaches from southwestern China and "
      + "compare them with their congeners. The new species differ from all known members of the genus in the shape "
      + "of the male genitalia and in the colour of the pronotum. A key to the species of the genus is provided.";
  private static final String EN_RIGHTS = "This work is licensed under a Creative Commons Attribution 4.0 "
      + "International License. Authors retain the copyright of their articles and grant the journal the right of "
      + "first publication with the work simultaneously licensed under that license.";
  private static final String ES_ABSTRACT = "Se describen dos especies nuevas de cucarachas del suroeste de China y se "
      + "comparan con sus congéneres. Las nuevas especies se diferencian de todas las demás del género por la forma "
      + "de la genitalia masculina y por el color del pronoto. Se proporciona una clave de las especies.";

  @Test
  void aParallelInTheWorksOwnLanguageIsNoTranslation() {
    // an OAI record's second dc:description: an English rights statement on an English article
    List<TextIn> versions = List.of(new TextIn("ABSTRACT", null, "PARALLEL", EN_RIGHTS),
        new TextIn("ABSTRACT", "es", "PARALLEL", ES_ABSTRACT));
    assertThat(P.of(EN_TITLE, EN_ABSTRACT, versions)).extracting(TextIn::lang).containsExactly("es");
    // the own text's language as given wins over detection
    assertThat(P.of(EN_TITLE, null, EN_ABSTRACT, "es", versions)).extracting(TextIn::lang).containsExactly("en");
    // an ORIGINAL version is no translation and stays
    assertThat(P.of(EN_TITLE, EN_ABSTRACT, List.of(new TextIn("TITLE", "en", "ORIGINAL",
        "Two new cockroach species from the southwest of China")))).hasSize(1);
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
