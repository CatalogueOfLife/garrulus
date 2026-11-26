package org.catalogueoflife.garrulus.indexer.lang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.catalogueoflife.garrulus.store.lang.LanguageEvidence;
import org.junit.jupiter.api.Test;

class LanguageDeciderTest {

  private static final LanguageDecider D = new LanguageDecider(new LanguageProperties(0.80f, 0.10f, 3, 100, false));

  private static LanguageEvidence ev(String kind, String source, String lang, Float conf) {
    return new LanguageEvidence(1L, kind, source, lang, conf, null);
  }

  @Test
  void fulltextWins() {
    var r = D.decide(List.of(ev("FULLTEXT", "GROBID", "fr", 0.95f), ev("DECLARED", "OAI", "en", null),
        ev("DETECTED", "TITLE", "en", 0.99f)), List.of());
    assertThat(r).isEqualTo(new LanguageDecider.Decision("fr", "FULLTEXT"));
  }

  @Test
  void lowConfidenceFulltextIsSkipped() {
    var r = D.decide(List.of(ev("FULLTEXT", "GROBID", "fr", 0.5f), ev("DECLARED", "OAI", "en", null)), List.of());
    assertThat(r).isEqualTo(new LanguageDecider.Decision("en", "DECLARED"));
  }

  @Test
  void declaredOrder() {
    var r = D.decide(List.of(ev("DECLARED", "OAI", "en", null), ev("DECLARED", "MAGTECH", "zh", null),
        ev("DECLARED", "EUROPEPMC", "fr", null)), List.of());
    assertThat(r).isEqualTo(new LanguageDecider.Decision("zh", "DECLARED"));
    assertThat(D.decide(List.of(ev("DECLARED", "EUROPEPMC", "fr", null), ev("DECLARED", "OAI", "de", null),
        ev("DECLARED", "SCIELO", "pt", null)), List.of()).lang()).isEqualTo("pt");
  }

  @Test
  void declaredCodesAreNormalised() {
    assertThat(D.decide(List.of(ev("DECLARED", "OAI", "ger", null)), List.of()).lang()).isEqualTo("de");
    assertThat(D.decide(List.of(ev("DECLARED", "OAI", "und", null)), List.of()).source()).isEqualTo("UNDECIDED");
  }

  @Test
  void detectedBeatsOpenAlex() {
    var r = D.decide(List.of(ev("OPENALEX", "OPENALEX", "uz", null), ev("DETECTED", "TITLE", "en", 0.95f)),
        List.of());
    assertThat(r).isEqualTo(new LanguageDecider.Decision("en", "DETECTED"));
  }

  @Test
  void lowConfidenceDetectionSkipped() {
    var r = D.decide(List.of(ev("OPENALEX", "OPENALEX", "pt", null), ev("DETECTED", "TITLE", "en", 0.05f)),
        List.of());
    assertThat(r).isEqualTo(new LanguageDecider.Decision("pt", "OPENALEX"));
  }

  @Test
  void detectedIsGatedByItsMarginNotByTheMinimumConfidence() {
    // the stored value is the margin: 0.15 is clear (min-margin 0.10) although it is below min-confidence 0.80
    assertThat(D.decide(List.of(ev("DETECTED", "TITLE", "en", 0.15f)), List.of()))
        .isEqualTo(new LanguageDecider.Decision("en", "DETECTED"));
    assertThat(D.decide(List.of(ev("DETECTED", "TITLE", "en", 0.09f)), List.of()).source()).isEqualTo("UNDECIDED");
  }

  @Test
  void legacyIsReportedAsOpenalex() {
    var r = D.decide(List.of(ev("LEGACY", "V21", "es", null)), List.of("pt"));
    assertThat(r).isEqualTo(new LanguageDecider.Decision("es", "OPENALEX"));
  }

  @Test
  void journalOnlyWithOneLanguage() {
    assertThat(D.decide(List.of(), List.of("pt"))).isEqualTo(new LanguageDecider.Decision("pt", "JOURNAL"));
    assertThat(D.decide(List.of(), List.of("pt", "es"))).isEqualTo(new LanguageDecider.Decision(null, "UNDECIDED"));
    assertThat(D.decide(List.of(), List.of("Portuguese")).lang()).isEqualTo("pt");
  }

  @Test
  void detectedTitleBeatsDetectedAbstractWhateverTheConfidence() {
    var r = D.decide(List.of(ev("DETECTED", "ABSTRACT", "en", 0.99f), ev("DETECTED", "TITLE", "es", 0.85f)), List.of());
    assertThat(r).isEqualTo(new LanguageDecider.Decision("es", "DETECTED"));
  }

  @Test
  void openalexLanguageOutsideTheDetectorIsKeptOnlyWithTheJournal() {
    // Uzbek is not among the 40 detected languages: a stray OpenAlex "uz" on a Latin-name title is not trusted
    assertThat(D.decide(List.of(ev("OPENALEX", "OPENALEX", "uz", null)), List.of("en")))
        .isEqualTo(new LanguageDecider.Decision("en", "JOURNAL"));
    assertThat(D.decide(List.of(ev("OPENALEX", "OPENALEX", "uz", null)), List.of()))
        .isEqualTo(new LanguageDecider.Decision(null, "UNDECIDED"));
    assertThat(D.decide(List.of(ev("LEGACY", "V21", "uz", null)), List.of("en", "fr")))
        .isEqualTo(new LanguageDecider.Decision(null, "UNDECIDED"));
    assertThat(D.decide(List.of(ev("OPENALEX", "OPENALEX", "uz", null)), List.of("uz", "ru")))
        .isEqualTo(new LanguageDecider.Decision("uz", "OPENALEX"));
    // a detector language stays without the journal
    assertThat(D.decide(List.of(ev("OPENALEX", "OPENALEX", "pt", null)), List.of()))
        .isEqualTo(new LanguageDecider.Decision("pt", "OPENALEX"));
  }

  @Test
  void undecidedWithoutEvidence() {
    assertThat(D.decide(List.of(), List.of())).isEqualTo(new LanguageDecider.Decision(null, "UNDECIDED"));
  }
}
