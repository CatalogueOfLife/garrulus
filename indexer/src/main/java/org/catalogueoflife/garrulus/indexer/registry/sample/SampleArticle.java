package org.catalogueoflife.garrulus.indexer.registry.sample;

import org.jspecify.annotations.Nullable;

/** One sample article: only what the finder reads. Lives inside the task; never stored. */
public record SampleArticle(String title, @Nullable String abstractText, @Nullable String abstractJats) {

  public boolean hasAbstract() {
    return (abstractText != null && !abstractText.isBlank()) || (abstractJats != null && !abstractJats.isBlank());
  }
}
