package org.catalogueoflife.nametracker.web.api.public_.dto;

import java.util.List;

public record TaxonStats(String taxonId, long total, long mainSubjectTotal, List<Year> years) {

  public record Year(int year, int works, int mainSubjectWorks) {}
}
