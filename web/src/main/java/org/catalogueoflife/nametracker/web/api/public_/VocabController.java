package org.catalogueoflife.nametracker.web.api.public_;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import life.catalogue.api.vocab.TaxGroup;
import org.catalogueoflife.nametracker.web.api.public_.dto.TaxGroupDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The TaxGroup vocabulary of ChecklistBank, so that clients can build a taxgroup filter without the vocab jar. */
@RestController
public class VocabController {

  static final String ICON = "https://api.checklistbank.org/vocab/taxgroup/%s/icon.svg";

  @GetMapping("/api/vocab/taxgroup")
  public List<TaxGroupDto> taxgroups() {
    return Arrays.stream(TaxGroup.values()).map(g -> new TaxGroupDto(g.name(), inOrder(g.getParents()),
        g.getCodes() == null ? List.of() : g.getCodes().stream().map(Enum::name).sorted().toList(),
        ICON.formatted(g.name()))).toList();
  }

  /** Keeps the declared order of the enum: the first parent is the primary one. */
  private static List<String> inOrder(Set<String> s) {
    return s == null ? List.of() : List.copyOf(s);
  }
}
