package org.catalogueoflife.garrulus.web.api.public_;

import java.util.List;
import org.catalogueoflife.garrulus.finder.api.FinderJson;
import org.catalogueoflife.garrulus.store.names.FirstWork;
import org.catalogueoflife.garrulus.store.names.NameHit;
import org.catalogueoflife.garrulus.store.names.NameMapper;
import org.catalogueoflife.garrulus.store.names.NameMatchRow;
import org.catalogueoflife.garrulus.web.api.public_.dto.NameFull;
import org.catalogueoflife.garrulus.web.api.public_.dto.NameHitDto;
import org.catalogueoflife.garrulus.web.api.public_.dto.NameMatchDto;
import org.catalogueoflife.garrulus.web.api.public_.dto.TaxonRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;

/** Public name lookup over the name-literature index. Work counts include every occurrence, references too. */
@RestController
public class NameController {

  private static final Logger LOG = LoggerFactory.getLogger(NameController.class);
  static final int MIN_Q = 2;
  static final int MAX_LIMIT = 50;

  private final NameMapper names;

  public NameController(NameMapper names) {
    this.names = names;
  }

  @GetMapping("/api/names")
  public List<NameHitDto> search(@RequestParam(required = false) String q,
      @RequestParam(defaultValue = "10") int limit) {
    String term = q == null ? "" : q.strip();
    if (term.codePointCount(0, term.length()) < MIN_Q) {
      throw WorkController.bad("q needs at least " + MIN_Q + " characters");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw WorkController.bad("limit must be 1.." + MAX_LIMIT);
    }
    String prefix = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return names.search(prefix, term, limit).stream()
        .map(h -> new NameHitDto(h.id(), h.name(), h.kind(), h.rank(), h.works())).toList();
  }

  @GetMapping("/api/names/{id}")
  public NameFull get(@PathVariable long id) {
    NameHit n = names.get(id);
    if (n == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no name " + id);
    }
    NameMatchRow m = names.currentMatch(id);
    FirstWork first = names.firstAppearance(id);
    return new NameFull(n.id(), n.name(), n.kind(), n.rank(), m == null ? null : match(m),
        names.timeline(id).stream().map(y -> new NameFull.YearWorks(y.year(), y.works())).toList(),
        first == null ? null : new NameFull.FirstAppearance(first.id(), first.title(), first.publicationDate()));
  }

  private static NameMatchDto match(NameMatchRow m) {
    List<TaxonRef> classification = List.of();
    try {
      classification = FinderJson.mapper().readValue(m.classification(), new TypeReference<List<TaxonRef>>() {});
    } catch (RuntimeException e) {
      LOG.warn("Unreadable classification of match {}: {}", m.usageId(), e.toString());
    }
    return new NameMatchDto(m.usageId(), m.usageName(), m.usageRank(), m.usageStatus(), m.acceptedId(), classification);
  }
}
