package org.catalogueoflife.garrulus.web.api.public_;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.catalogueoflife.garrulus.finder.api.ActPatterns;
import org.catalogueoflife.garrulus.finder.api.ActPatterns.ActMatch;
import org.catalogueoflife.garrulus.web.api.public_.dto.ActSpan;
import org.catalogueoflife.garrulus.web.api.public_.dto.MentionSpan;
import org.jspecify.annotations.Nullable;

/**
 * Finds again the act markers ("sp. nov.") of the name spans in a text. The finder's ActTagger attaches a marker to the
 * nearest fitting name before it, at most 40 code points away, and stores only the act type with the name; here each
 * marker of the text goes to the nearest span before it that carries the marker's act type, within the same distance,
 * and each span gets at most one marker.
 */
final class ActSpans {

  /** ActTagger.DEFAULT_MAX_DISTANCE. */
  static final int MAX_DISTANCE = 40;

  private ActSpans() {}

  /** The markers of the spans in text, in text order; spans and markers are code-point offsets into text. */
  static List<ActSpan> locate(@Nullable String text, List<MentionSpan> spans) {
    if (text == null || spans.stream().allMatch(s -> s.actType() == null)) {
      return List.of();
    }
    List<ActSpan> out = new ArrayList<>();
    Set<Long> taken = new HashSet<>();
    for (ActMatch a : ActPatterns.find(text)) {
      String type = a.type().name();
      spans.stream()
          .filter(s -> type.equals(s.actType()) && s.endCp() <= a.start() && a.start() - s.endCp() <= MAX_DISTANCE)
          .max(Comparator.comparingInt(MentionSpan::endCp))
          .filter(s -> taken.add(s.id()))
          .ifPresent(s -> out.add(new ActSpan(s.id(), a.start(), a.end(), type)));
    }
    return out;
  }
}
