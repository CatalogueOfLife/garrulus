package org.catalogueoflife.nametracker.finder.doc;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class DehyphenatorTest {
  private final Predicate<String> known = Set.of("pterostichus", "northeast")::contains;
  private final Predicate<String> none = w -> false;

  @Test void joinsHyphenatedKnownWord() {
    assertThat(Dehyphenator.join("genus Pterosti-\nchus niger", known)).isEqualTo("genus Pterostichus niger");
  }

  @Test void keepsRealHyphen() {
    assertThat(Dehyphenator.join("the north-\neast coast", none)).isEqualTo("the north-east coast");
  }

  @Test void handlesWhitespaceAndNoMatch() {
    assertThat(Dehyphenator.join("Pterosti- \n  chus", known)).isEqualTo("Pterostichus");
    assertThat(Dehyphenator.join("a well-known fact", known)).isEqualTo("a well-known fact");
    assertThat(Dehyphenator.join("Pterosti-\nChus", known)).isEqualTo("Pterosti-\nChus");
    assertThat(Dehyphenator.join("", known)).isEmpty();
  }
}
