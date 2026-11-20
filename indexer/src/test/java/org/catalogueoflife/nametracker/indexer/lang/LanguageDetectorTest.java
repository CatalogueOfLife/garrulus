package org.catalogueoflife.nametracker.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

  private static final LanguageDetector D = new LanguageDetector(new LanguageProperties(0.80f, 3, 100, true));

  private static final String EN_ABSTRACT = "We describe two new species of cockroaches from southwestern China and "
      + "compare them with their congeners. The new species differ from all known members of the genus in the shape "
      + "of the male genitalia and in the colour of the pronotum. A key to the species of the genus is provided.";

  private static final String ES_ABSTRACT = "Se describen dos especies nuevas de cucarachas del suroeste de China y se "
      + "comparan con sus congéneres. Las nuevas especies se diferencian de todas las demás del género por la forma "
      + "de la genitalia masculina y por el color del pronoto. Se proporciona una clave de las especies.";

  @Test
  void chineseTitle() {
    LanguageDetector.Detection d = D.detect("中国西南地区蜚蠊科两新属");
    assertThat(d.lang()).isEqualTo("zh");
    assertThat(d.confidence()).isGreaterThanOrEqualTo(0.8f);
  }

  @Test
  void englishTitle() {
    LanguageDetector.Detection d = D.titleAndAbstract("Two new species of cockroaches from southwestern China", null,
        List.of());
    assertThat(d).isNotNull();
    assertThat(d.lang()).isEqualTo("en");
  }

  @Test
  void shortTitleUndecided() {
    assertThat(D.titleAndAbstract("Notes", null, List.of())).isNull();
    assertThat(D.titleAndAbstract("Carabus notes", "", List.of())).isNull();
    assertThat(D.titleAndAbstract(null, null, List.of())).isNull();
  }

  @Test
  void titleBeatsParallelEnglishAbstract() {
    assertThat(D.titleAndAbstract("Dos especies nuevas de cucarachas del suroeste de China", EN_ABSTRACT, List.of())
        .lang()).isEqualTo("es");
    assertThat(D.titleAndAbstract("中国西南地区蜚蠊科两新属", EN_ABSTRACT, List.of()).lang()).isEqualTo("zh");
  }

  @Test
  void parallelTextRemovedBeforeAbstractDetection() {
    // title too short to decide; the abstract has a Spanish paragraph and its English parallel
    String abs = ES_ABSTRACT + "\n\n" + EN_ABSTRACT;
    assertThat(D.titleAndAbstract("Cucarachas", abs, List.of()).lang()).isIn("es", "en");
    LanguageDetector.Detection d = D.titleAndAbstract("Cucarachas", abs, List.of(EN_ABSTRACT));
    assertThat(d).isNotNull();
    assertThat(d.lang()).isEqualTo("es");
  }

  @Test
  void uzbekMisdetectionFixed() {
    LanguageDetector.Detection d = D.titleAndAbstract("Two new species of cockroaches from southwestern China",
        EN_ABSTRACT, List.of());
    assertThat(d.lang()).isEqualTo("en");
  }

  @Test
  void markupIsStripped() {
    assertThat(D.titleAndAbstract("Two new species of <i>Blattella</i> from southwestern China", null, List.of())
        .lang()).isEqualTo("en");
  }

  @Test
  void detectTextNeedsEnoughLetters() {
    assertThat(D.detectText("Too short to say anything useful here.")).isNull();
    assertThat(D.detectText(ES_ABSTRACT).lang()).isEqualTo("es");
  }

  @Test
  void lowAccuracyModeStillDetects() {
    LanguageDetector low = new LanguageDetector(new LanguageProperties(0.80f, 3, 100, false));
    assertThat(low.titleAndAbstract("Dos especies nuevas de cucarachas del suroeste de China", null, List.of()).lang())
        .isEqualTo("es");
  }
}
