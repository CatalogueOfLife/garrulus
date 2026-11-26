package org.catalogueoflife.garrulus.store.lang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LanguageCodesTest {

  @ParameterizedTest
  @CsvSource({
      "en,en", "EN,en", "' zh ',zh", "ZH,zh",
      "eng,en", "spa,es", "deu,de", "fra,fr", "zho,zh", "nld,nl", "ces,cs",
      "ger,de", "fre,fr", "chi,zh", "dut,nl", "cze,cs", "gre,el", "per,fa", "rum,ro", "slo,sk", "wel,cy", "alb,sq",
      "arm,hy", "baq,eu", "bur,my", "geo,ka", "ice,is", "mac,mk", "mao,mi", "may,ms", "tib,bo",
      "English,en", "english,en", "Spanish,es", "Uzbek,uz", "German,de", "Chinese,zh", "Portuguese,pt",
      "en_US,en", "en-GB,en", "pt-BR,pt", "zh-Hans,zh", "zh_Hant_TW,zh", "SPA-ES,es",
      "nb,no", "nn,no", "no,no", "nob,no", "Norwegian,no", "iw,he", "in,id", "fil,fil", "haw,haw"})
  void normalises(String raw, String expected) {
    assertThat(LanguageCodes.normalise(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"und", "mul", "zxx", "mis", "UND", "xx", "Klingonish", "'  '", "''", "q"})
  void undeterminedAndUnknownAreNull(String raw) {
    assertThat(LanguageCodes.normalise(raw)).isNull();
  }

  @Test
  void nullIsNull() {
    assertThat(LanguageCodes.normalise(null)).isNull();
  }

  @Test
  void scripts() {
    assertThat(LanguageCodes.isLatinScript("ru")).isFalse();
    assertThat(LanguageCodes.isLatinScript("zh")).isFalse();
    assertThat(LanguageCodes.isLatinScript("hy")).isFalse();
    assertThat(LanguageCodes.isLatinScript("en")).isTrue();
    assertThat(LanguageCodes.isLatinScript("uz")).isTrue();
    assertThat(LanguageCodes.isLatinScript("RUS")).isFalse();
    assertThat(LanguageCodes.isLatinScript("Russian")).isFalse();
  }
}
