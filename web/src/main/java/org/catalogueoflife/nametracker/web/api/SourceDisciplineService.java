package org.catalogueoflife.nametracker.web.api;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.catalogueoflife.nametracker.store.discipline.Discipline;
import org.catalogueoflife.nametracker.store.discipline.DisciplineMapper;
import org.catalogueoflife.nametracker.store.source.SourceMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * An admin's disciplines of a journal. They are codes of the vocabulary that are not retired; the derivation
 * ({@code CATEGORISE_SOURCES}) leaves an admin-set journal alone until the disciplines are released.
 */
@Service
public class SourceDisciplineService {

  private final SourceMapper sources;
  private final DisciplineMapper disciplines;

  public SourceDisciplineService(SourceMapper sources, DisciplineMapper disciplines) {
    this.sources = sources;
    this.disciplines = disciplines;
  }

  /** Throws a 400 when a code is unknown, retired or repeated; nothing is written then. */
  public void validate(List<String> codes) {
    Set<String> usable = disciplines.all().stream().filter(d -> d.replacedBy() == null).map(Discipline::code)
        .collect(Collectors.toSet());
    Set<String> seen = new HashSet<>();
    for (String c : codes) {
      if (c == null || !usable.contains(c)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "disciplines: unknown or retired code " + c);
      }
      if (!seen.add(c)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "disciplines: " + c + " given twice");
    }
  }

  public void override(long id, List<String> codes) {
    sources.setDisciplinesByAdmin(id, codes);
  }

  public void release(long id) {
    sources.releaseDisciplines(id);
  }
}
