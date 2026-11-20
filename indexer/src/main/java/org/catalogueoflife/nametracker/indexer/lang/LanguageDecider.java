package org.catalogueoflife.nametracker.indexer.lang;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.catalogueoflife.nametracker.store.lang.LanguageCodes;
import org.catalogueoflife.nametracker.store.lang.LanguageEvidence;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides the language of a work from its evidence. The first that applies: FULLTEXT (confidence at least the
 * minimum), DECLARED (by layer MAGTECH, SCIELO, OAI, EUROPEPMC, other layers after them), DETECTED (at least the
 * minimum), OPENALEX or else LEGACY (both reported as {@code OPENALEX}: the legacy values came from the first layer,
 * almost always OpenAlex), JOURNAL (the journal has exactly one language), else UNDECIDED with no language.
 */
@Component
public class LanguageDecider {

  public record Decision(@Nullable String lang, String source) {}

  private static final List<String> DECLARED_ORDER = List.of("MAGTECH", "SCIELO", "OAI", "EUROPEPMC");

  private final LanguageProperties props;

  public LanguageDecider(LanguageProperties props) {
    this.props = props;
  }

  public Decision decide(List<LanguageEvidence> ev, List<String> journalLanguages) {
    Optional<LanguageEvidence> fulltext = confident(ev, "FULLTEXT");
    if (fulltext.isPresent()) return decision(fulltext.get(), "FULLTEXT");

    Optional<LanguageEvidence> declared = ev.stream()
        .filter(e -> "DECLARED".equals(e.kind()) && LanguageCodes.normalise(e.lang()) != null)
        .min(Comparator.comparingInt(e -> rank(e.source())));
    if (declared.isPresent()) return decision(declared.get(), "DECLARED");

    Optional<LanguageEvidence> detected = confident(ev, "DETECTED");
    if (detected.isPresent()) return decision(detected.get(), "DETECTED");

    for (String kind : List.of("OPENALEX", "LEGACY")) {
      Optional<LanguageEvidence> e = ev.stream()
          .filter(x -> kind.equals(x.kind()) && LanguageCodes.normalise(x.lang()) != null).findFirst();
      if (e.isPresent()) return decision(e.get(), "OPENALEX");
    }

    List<String> journal = journalLanguages.stream().map(LanguageCodes::normalise).filter(l -> l != null)
        .distinct().toList();
    if (journal.size() == 1) return new Decision(journal.get(0), "JOURNAL");
    return new Decision(null, "UNDECIDED");
  }

  private Optional<LanguageEvidence> confident(List<LanguageEvidence> ev, String kind) {
    return ev.stream()
        .filter(e -> kind.equals(e.kind()) && LanguageCodes.normalise(e.lang()) != null && e.confidence() != null
            && e.confidence() >= props.minConfidence())
        .max(Comparator.comparing(LanguageEvidence::confidence));
  }

  private static Decision decision(LanguageEvidence e, String source) {
    return new Decision(LanguageCodes.normalise(e.lang()), source);
  }

  private static int rank(String source) {
    int i = DECLARED_ORDER.indexOf(source);
    return i < 0 ? DECLARED_ORDER.size() : i;
  }
}
