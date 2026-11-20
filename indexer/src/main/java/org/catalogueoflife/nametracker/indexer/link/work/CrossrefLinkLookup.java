package org.catalogueoflife.nametracker.indexer.link.work;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.catalogueoflife.nametracker.indexer.crossref.CrossrefClient;
import org.catalogueoflife.nametracker.indexer.link.DoiBatching;
import org.catalogueoflife.nametracker.indexer.link.IdentifierLookup;
import org.catalogueoflife.nametracker.indexer.link.LinkProperties;
import org.catalogueoflife.nametracker.indexer.link.LinkTarget;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.Identifier;
import org.springframework.stereotype.Component;

/** A work whose DOI Crossref knows has the CROSSREF identifier (the DOI), the record behind the link. */
@Component
public class CrossrefLinkLookup implements IdentifierLookup {

  public static final String NAME = "crossref";

  private final CrossrefClient crossref;
  private final LinkProperties props;

  public CrossrefLinkLookup(CrossrefClient crossref, LinkProperties props) {
    this.crossref = crossref;
    this.props = props;
  }

  @Override public String name() { return NAME; }
  @Override public List<String> schemes() { return List.of(IdScheme.CROSSREF.code()); }
  @Override public IdScheme.Entity appliesTo() { return IdScheme.Entity.WORK; }
  @Override public boolean enabled() { return props.enabled(NAME); }
  @Override public int order() { return 20; }
  @Override public int batchSize() { return 20; }

  @Override
  public boolean applies(LinkTarget t) {
    return t.doi() != null && !t.has(IdScheme.CROSSREF.code());
  }

  @Override
  public Map<Long, List<Identifier>> lookup(List<LinkTarget> batch) {
    Set<String> known = new HashSet<>();
    DoiBatching.each(batch.stream().map(LinkTarget::doi).distinct().toList(), crossref::existingDois)
        .forEach(known::addAll);
    Map<Long, List<Identifier>> out = new HashMap<>();
    for (LinkTarget t : batch) {
      if (known.contains(DoiBatching.key(t.doi()))) out.put(t.id(), List.of(new Identifier(IdScheme.CROSSREF.code(), t.doi())));
    }
    return out;
  }
}
