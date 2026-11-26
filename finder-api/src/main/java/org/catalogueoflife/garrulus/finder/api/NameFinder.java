package org.catalogueoflife.garrulus.finder.api;

public interface NameFinder {
  FinderInfo info();

  FinderResult find(Document doc, FinderOptions opts);
}
