package org.catalogueoflife.garrulus.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

  private static final LanguageDetector D = new LanguageDetector(new LanguageProperties(0.80f, 0.10f, 3, 100, false));

  private static final String EN_ABSTRACT = "We describe two new species of cockroaches from southwestern China and "
      + "compare them with their congeners. The new species differ from all known members of the genus in the shape "
      + "of the male genitalia and in the colour of the pronotum. A key to the species of the genus is provided.";

  private static final String ES_ABSTRACT = "Se describen dos especies nuevas de cucarachas del suroeste de China y se "
      + "comparan con sus congéneres. Las nuevas especies se diferencian de todas las demás del género por la forma "
      + "de la genitalia masculina y por el color del pronoto. Se proporciona una clave de las especies.";

  private static final String NO_ABSTRACT = "Vi beskriver to nye arter av kakerlakker fra det sørvestlige Kina og "
      + "sammenligner dem med deres nærmeste slektninger. De nye artene skiller seg fra alle kjente medlemmer av "
      + "slekten i formen på hannens kjønnsorganer og i fargen på brystskjoldet. En nøkkel til artene er gitt.";

  @Test
  void everyDetectorLanguageIsInTheOneCodeForm() {
    assertThat(LanguageDetector.supportedCodes()).hasSize(40).allSatisfy(c -> {
      assertThat(org.catalogueoflife.garrulus.store.lang.LanguageCodes.normalise(c)).isEqualTo(c);
      assertThat(LanguageDetector.supports(org.catalogueoflife.garrulus.store.lang.LanguageCodes.normalise(c)))
          .isTrue();
    });
  }

  @Test
  void norwegianIsNoAsLanguageCodesHasIt() {
    for (String raw : List.of("nb", "nn", "no", "nob", "Norwegian")) {
      assertThat(LanguageDetector.supports(org.catalogueoflife.garrulus.store.lang.LanguageCodes.normalise(raw)))
          .as(raw).isTrue();
    }
    assertThat(D.detect(NO_ABSTRACT).lang()).isEqualTo("no");
  }

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
    LanguageDetector.Detection d = D.titleAndAbstract("Cucarachas", abs, List.of(EN_ABSTRACT));
    assertThat(d).isNotNull();
    assertThat(d.lang()).isEqualTo("es");
  }

  @Test
  void latinNameTitlesAreNotDetected() {
    for (String t : List.of("Carabus auratus Linnaeus", "Poa annua L., 1753", "Quercus robur L.",
        "Bellamya capillata Mabille 1887", "Carabus auratus Linnaeus, 1761", "Smith, 2001 Poa annua")) {
      assertThat(D.titleAndAbstract(t, null, List.of())).as(t).isNull();
    }
  }

  @Test
  void sentenceInitialCapitalIsNotABinomial() {
    // "Las aves de Chile" has four counted words now, but Lingua cannot tell it from Romanian (margin 0.01): it
    // stays undecided, which is the margin gate working, not the word count
    assertThat(LanguageDetector.countedWords("Las aves de Chile")).isEqualTo(4);
    String[][] titles = {{"Contribución al conocimiento de las aves de Chile", "es"},
        {"Aves da Mata Atlântica brasileira", "pt"}, {"Les oiseaux de France", "fr"}, {"The birds of Chile", "en"}};
    for (String[] t : titles) {
      LanguageDetector.Detection d = D.titleAndAbstract(t[0], null, List.of());
      assertThat(d).as(t[0]).isNotNull();
      assertThat(d.lang()).as(t[0]).isEqualTo(t[1]);
    }
    // a name with its author, and a name with nothing else, are still no language evidence
    assertThat(LanguageDetector.countedWords("Carabus auratus Linnaeus, 1761")).isZero();
    assertThat(D.titleAndAbstract("Carabus auratus Linnaeus, 1761", null, List.of())).isNull();
    assertThat(D.titleAndAbstract("Poa annua L.", null, List.of())).isNull();
  }

  @Test
  void detectionCarriesItsMargin() {
    LanguageDetector.Detection d = D.detectTitle("Two new species of cockroaches from southwestern China");
    assertThat(d.lang()).isEqualTo("en");
    assertThat(d.margin()).isGreaterThanOrEqualTo(0.10f).isLessThanOrEqualTo(d.confidence());
  }

  @Test
  void englishTitleAndAbstractAreEnglish() {
    assertThat(D.titleAndAbstract("Two new species of cockroaches from southwestern China", EN_ABSTRACT, List.of())
        .lang()).isEqualTo("en");
  }

  @Test
  void ambiguousDetectionNeedsAMargin() {
    // a lax margin accepts what the default margin rejects, so the gate is active
    LanguageDetector strict = new LanguageDetector(new LanguageProperties(0.0f, 0.99f, 3, 100, false));
    assertThat(strict.titleAndAbstract("Dos especies nuevas de cucarachas del suroeste de China", null, List.of()))
        .isNull();
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
  void highAccuracySmoke() {
    LanguageDetector high = new LanguageDetector(new LanguageProperties(0.80f, 0.10f, 3, 100, true));
    assertThat(high.titleAndAbstract("Dos especies nuevas de cucarachas del suroeste de China", null, List.of()).lang())
        .isEqualTo("es");
  }
}
