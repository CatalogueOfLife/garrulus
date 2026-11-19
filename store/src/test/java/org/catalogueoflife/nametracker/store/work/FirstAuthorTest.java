package org.catalogueoflife.nametracker.store.work;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FirstAuthorTest {

  private static String json(String name) {
    return "[{\"name\":\"" + name + "\",\"orcid\":null},{\"name\":\"Second Author\",\"orcid\":null}]";
  }

  @Test
  void familyNameInEveryNameOrder() {
    assertThat(FirstAuthor.family(json("Jane Doe"))).isEqualTo("doe");
    assertThat(FirstAuthor.family(json("Doe, Jane"))).isEqualTo("doe");
    assertThat(FirstAuthor.family(json("Doe, J."))).isEqualTo("doe");
    assertThat(FirstAuthor.family(json("Doe JM"))).isEqualTo("doe");
    assertThat(FirstAuthor.family(json("J.-M. Doe"))).isEqualTo("doe");
    assertThat(FirstAuthor.family(json("Hans M\\u00fcller"))).isEqualTo("muller");
    assertThat(FirstAuthor.family(json("Müller, Hans"))).isEqualTo("muller");
    assertThat(FirstAuthor.family(json("Wei Li"))).isEqualTo("li");
  }

  @Test
  void noAuthorsNoFamily() {
    assertThat(FirstAuthor.family(null)).isNull();
    assertThat(FirstAuthor.family("[]")).isNull();
    assertThat(FirstAuthor.family(json("J."))).isNull();
  }
}
