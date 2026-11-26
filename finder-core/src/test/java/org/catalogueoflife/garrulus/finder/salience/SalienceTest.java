package org.catalogueoflife.garrulus.finder.salience;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.garrulus.finder.salience.Salience.Inputs;
import org.junit.jupiter.api.Test;

class SalienceTest {

  final Salience s = new Salience(SalienceProperties.DEFAULTS);

  @Test
  void titleBinomialWithActIsMainSubject() {
    double v = s.score(new Inputs(true, true, true, false, 6, 6, 0.0));
    assertThat(v).isEqualTo(1.0);
    assertThat(s.isMainSubject(v)).isTrue();
  }

  @Test
  void labOrganismInMethodsOnlyIsNot() {
    double v = s.score(new Inputs(false, false, false, false, 2, 10, 0.05));
    assertThat(v).isEqualTo(0.0);
    assertThat(s.isMainSubject(v)).isFalse();
  }

  @Test
  void referenceOnlyNameIsNeverMainSubject() {
    double v = s.score(new Inputs(true, true, true, true, 0, 0, 0.0));
    assertThat(v).isZero();
    assertThat(s.isMainSubject(v)).isFalse();
  }

  @Test
  void weightsComeFromProperties() {
    Inputs in = new Inputs(true, false, false, false, 1, 1, 0.0);
    assertThat(s.score(in)).isEqualTo(0.40 + 0.25);
    Salience custom = new Salience(new SalienceProperties(0.1, 0, 0, 0, 0, 0, 0, 0.02, 0.5));
    assertThat(custom.score(in)).isEqualTo(0.1);
    assertThat(custom.isMainSubject(0.1)).isFalse();
  }
}
