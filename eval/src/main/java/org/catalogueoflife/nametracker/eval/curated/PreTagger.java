package org.catalogueoflife.nametracker.eval.curated;

import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.Mention;
import org.catalogueoflife.nametracker.finder.api.NameFinder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Machine pre-tagging: the union of all finders' mentions, de-duplicated by span (block, start, end), first finder wins. */
public final class PreTagger {
  private PreTagger() {}

  public static List<Mention> pretag(Document doc, List<NameFinder> finders) {
    List<Mention> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (NameFinder f : finders) {
      for (Mention m : f.find(doc, FinderOptions.defaults()).mentions()) {
        if (seen.add(m.blockId() + ":" + m.start() + ":" + m.end())) out.add(m);
      }
    }
    return out;
  }
}
