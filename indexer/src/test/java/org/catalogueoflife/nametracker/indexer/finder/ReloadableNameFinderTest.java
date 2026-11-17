package org.catalogueoflife.nametracker.indexer.finder;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.NameFinder;
import org.junit.jupiter.api.Test;

class ReloadableNameFinderTest {

  private static NameFinder named(String key) {
    return new NameFinder() {
      @Override
      public FinderInfo info() {
        return new FinderInfo("stub", "1", "cfg", key);
      }

      @Override
      public FinderResult find(Document doc, FinderOptions opts) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Test
  void swapChangesDelegate() {
    ReloadableNameFinder f = new ReloadableNameFinder(named("100"));
    assertThat(f.info().dictionaryKey()).isEqualTo("100");
    f.swap(named("200"));
    assertThat(f.info().dictionaryKey()).isEqualTo("200");
    assertThat(f.info().configHash()).isEqualTo("cfg");
  }
}
