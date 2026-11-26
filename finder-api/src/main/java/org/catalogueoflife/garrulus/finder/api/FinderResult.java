package org.catalogueoflife.garrulus.finder.api;

import java.util.List;
import java.util.Map;

public record FinderResult(FinderInfo info, List<Mention> mentions, List<Act> acts,
                           List<ClassificationContext> contexts, List<Mention> rejected,
                           Map<String, String> diagnostics) {
  public FinderResult {
    mentions = Util.list(mentions);
    acts = Util.list(acts);
    contexts = Util.list(contexts);
    rejected = Util.list(rejected);
    diagnostics = Util.map(diagnostics);
  }
}
