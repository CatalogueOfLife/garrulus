package org.catalogueoflife.nametracker.indexer.link.source;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.catalogueoflife.nametracker.store.ident.Issn;
import org.springframework.stereotype.Component;

/** A journal Crossref knows by an ISSN has the CROSSREF_JOURNAL identifier (that ISSN): {@code GET /journals/{issn}}. */
@Component
public class CrossrefJournalLookup implements IdentifierLookup {

  public static final String NAME = "crossref-journal";

  private final CrossrefClient crossref;
  private final LinkProperties props;

  public CrossrefJournalLookup(CrossrefClient crossref, LinkProperties props) {
    this.crossref = crossref;
    this.props = props;
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.CROSSREF_JOURNAL.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.SOURCE; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 40; }

  @Override
  public boolean applies(LinkTarget t) {
    return !t.has(IdScheme.CROSSREF_JOURNAL.code()) && t.issns().stream().anyMatch(i -> Issn.normalise(i) != null);
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      for (String issn : t.issns().stream().map(Issn::normalise).filter(Objects::nonNull).distinct().toList()) {
        if (crossref.journal(issn).isPresent()) {
          out.put(t.id(), List.of(new Identifier(IdScheme.CROSSREF_JOURNAL.code(), issn)));
          break;
        }
      }
    }
    return out;
  }
}
