package org.catalogueoflife.garrulus.web.api.public_;

import java.util.ArrayList;
import java.util.List;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.IdScheme.Entity;
import org.catalogueoflife.garrulus.store.ident.Identifier;
import org.catalogueoflife.garrulus.store.ident.IdentifierLink;
import org.catalogueoflife.garrulus.store.ident.IdentifierRegistry;
import org.catalogueoflife.garrulus.store.ident.SourceIdentifierMapper;
import org.catalogueoflife.garrulus.store.ident.WorkIdentifierMapper;
import org.catalogueoflife.garrulus.store.source.Source;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** The identifiers of works and journals as the API shows them, and the {@code identifier=SCHEME:value} parameter. */
@Component
public class IdentifierViews {

  private final IdentifierRegistry registry;
  private final WorkIdentifierMapper workIds;
  private final SourceIdentifierMapper sourceIds;

  public IdentifierViews(IdentifierRegistry registry, WorkIdentifierMapper workIds, SourceIdentifierMapper sourceIds) {
    this.registry = registry;
    this.workIds = workIds;
    this.sourceIds = sourceIds;
  }

  /** The stored identifiers of a work; the DOI column stands in when no DOI row exists yet. */
  public List<IdentifierLink> work(long workId, @Nullable String doi) {
    List<Identifier> ids = new ArrayList<>(workIds.list(workId));
    if (doi != null && ids.stream().noneMatch(i -> i.scheme().equals(IdScheme.DOI.code()))) {
      String n = IdScheme.DOI.normalise(doi);
      if (n != null) {
        ids.add(new Identifier(IdScheme.DOI.code(), n));
      }
    }
    return registry.links(Entity.WORK, ids);
  }

  /** ISSN, ISSN-L and the OpenAlex id come from the source's own columns, the lookup schemes from source_identifier. */
  public List<IdentifierLink> source(Source s) {
    List<Identifier> ids = new ArrayList<>(sourceIds.list(s.id()));
    if (s.issns() != null) {
      s.issns().forEach(i -> ids.add(new Identifier(IdScheme.ISSN.code(), i)));
    }
    if (s.issnL() != null) {
      ids.add(new Identifier(IdScheme.ISSN_L.code(), s.issnL()));
    }
    if (s.openalexId() != null) {
      ids.add(new Identifier(IdScheme.OPENALEX.code(), s.openalexId()));
    }
    return registry.links(Entity.SOURCE, ids);
  }

  /** The filter value {@code SCHEME:value} of a work, normalised through its scheme; 400 if the scheme is not a work scheme or the value is invalid. */
  public @Nullable Identifier parseWorkFilter(@Nullable String param) {
    if (param == null || param.isBlank()) {
      return null;
    }
    Identifier id = registry.parse(param);
    IdScheme s = id == null ? null : registry.find(id.scheme());
    if (s == null || !s.entities().contains(Entity.WORK)) {
      throw WorkController.bad("identifier must be SCHEME:value with a known work scheme and a valid value");
    }
    return id;
  }
}
