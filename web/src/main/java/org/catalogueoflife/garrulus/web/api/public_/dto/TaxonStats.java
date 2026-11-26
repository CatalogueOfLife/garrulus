package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.util.List;
import org.catalogueoflife.garrulus.store.lang.LanguageYear;

public record TaxonStats(String taxonId, long total, long mainSubjectTotal, List<Year> years,
    List<LanguageYear> languages) {

  public record Year(int year, int works, int mainSubjectWorks) {}
}
