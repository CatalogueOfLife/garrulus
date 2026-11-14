package org.catalogueoflife.nametracker.finder.api;

public interface NameFinder {
  FinderInfo info();

  FinderResult find(Document doc, FinderOptions opts);
}
