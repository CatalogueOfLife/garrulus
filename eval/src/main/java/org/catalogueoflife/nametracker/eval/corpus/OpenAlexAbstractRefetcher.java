package org.catalogueoflife.nametracker.eval.corpus;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.doc.FlatText;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Puts the text of a curated abstract back by re-fetching its OpenAlex work (one single-record call) and rebuilding the
 * document exactly as the selection did. {@link CorpusStore} then verifies the SHA-256, so a changed abstract is reported
 * instead of silently shifting the gold offsets.
 */
public class OpenAlexAbstractRefetcher implements TextRefetcher {
  private final OpenAlexClient openAlex;

  public OpenAlexAbstractRefetcher(OpenAlexClient openAlex) {
    this.openAlex = openAlex;
  }

  @Override
  public String refetch(GoldDocument stored) {
    JsonNode work = openAlex.getWork(OpenAlexAbstracts.openAlexId(stored.docId()));
    Document d = OpenAlexAbstracts.document(work, Map.of());
    if (d == null) throw new IllegalStateException("OpenAlex no longer serves an abstract for " + stored.docId());
    return FlatText.of(d).text();
  }
}
