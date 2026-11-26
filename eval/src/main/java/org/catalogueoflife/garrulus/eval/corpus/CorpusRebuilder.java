package org.catalogueoflife.garrulus.eval.corpus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Rebuilds a corpus from its manifest with the current extraction code, without resampling: the same documents, docIds,
 * splits and licences, in manifest order. A document that cannot be fetched keeps its previous version (and is counted),
 * so a transient failure never shrinks the corpus.
 */
public final class CorpusRebuilder {
  private CorpusRebuilder() {}

  /** What changed between the old and the rebuilt corpus. Name counts compare the name texts of each block as multisets. */
  public record Stats(int documents, int rebuilt, int keptOld, List<String> failed, int documentsChanged, int textChanged,
                      int oldNames, int newNames, int namesChanged, int namesAdded, int namesRemoved, int oldActs, int newActs) {}

  public record Result(List<GoldDocument> documents, Stats stats) {}

  /**
   * @param fetch builds the gold document of a manifest row; a RuntimeException skips the row (its old version is kept)
   */
  public static Result rebuild(CorpusManifest manifest, List<GoldDocument> old, Function<CorpusManifest.Row, GoldDocument> fetch,
                               java.util.function.Consumer<String> log) {
    Map<String, GoldDocument> before = new LinkedHashMap<>();
    old.forEach(d -> before.put(d.docId(), d));
    List<GoldDocument> out = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    int rebuilt = 0, keptOld = 0, changed = 0, textChanged = 0;
    int oldNames = 0, newNames = 0, namesChanged = 0, namesAdded = 0, namesRemoved = 0, oldActs = 0, newActs = 0;
    for (CorpusManifest.Row row : manifest.rows()) {
      GoldDocument prev = before.get(row.docId());
      GoldDocument doc;
      try {
        doc = fetch.apply(row);
        rebuilt++;
      } catch (RuntimeException e) {
        failed.add(row.docId());
        log.accept("skipping " + row.doi() + ": " + e.getMessage() + (prev != null ? " (keeping its previous version)" : ""));
        if (prev == null) continue;
        doc = prev;
        keptOld++;
      }
      out.add(doc);
      if (out.size() % 25 == 0) log.accept(out.size() + "/" + manifest.rows().size() + " rebuilt");
      newNames += doc.names().size();
      newActs += doc.acts().size();
      if (prev == null) {
        namesAdded += doc.names().size();
        changed++;
        continue;
      }
      oldNames += prev.names().size();
      oldActs += prev.acts().size();
      if (!prev.equals(doc)) changed++;
      if (!prev.textSha256().equals(doc.textSha256())) textChanged++;
      // name texts per document: a text that is only in the new version is a changed or added name
      int gone = minus(texts(prev), texts(doc));
      int fresh = minus(texts(doc), texts(prev));
      int paired = Math.min(gone, fresh);
      namesChanged += paired;
      namesAdded += fresh - paired;
      namesRemoved += gone - paired;
    }
    Stats s = new Stats(manifest.rows().size(), rebuilt, keptOld, failed, changed, textChanged, oldNames, newNames,
        namesChanged, namesAdded, namesRemoved, oldActs, newActs);
    return new Result(out, s);
  }

  private static Map<String, Integer> texts(GoldDocument d) {
    Map<String, Integer> m = new HashMap<>();
    d.names().forEach(n -> m.merge(n.blockId() + "\u0000" + n.text(), 1, Integer::sum));
    return m;
  }

  /** Size of the multiset difference a - b. */
  private static int minus(Map<String, Integer> a, Map<String, Integer> b) {
    int n = 0;
    for (var e : a.entrySet()) n += Math.max(0, e.getValue() - b.getOrDefault(e.getKey(), 0));
    return n;
  }
}
