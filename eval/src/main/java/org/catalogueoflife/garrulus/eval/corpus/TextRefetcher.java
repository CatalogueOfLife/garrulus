package org.catalogueoflife.garrulus.eval.corpus;

/**
 * Re-fetches the text of a document whose text is not stored (licence). The result must be the normalised text exactly as
 * {@code FlatText.of(document).text()} produced it when the corpus was built: its SHA-256 has to equal
 * {@link GoldDocument#textSha256()}.
 */
public interface TextRefetcher {
  String refetch(GoldDocument stored);
}
