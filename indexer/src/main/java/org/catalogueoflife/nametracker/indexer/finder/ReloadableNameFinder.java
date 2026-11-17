package org.catalogueoflife.nametracker.indexer.finder;

import java.util.concurrent.atomic.AtomicReference;
import org.catalogueoflife.nametracker.finder.api.Document;
import org.catalogueoflife.nametracker.finder.api.FinderInfo;
import org.catalogueoflife.nametracker.finder.api.FinderOptions;
import org.catalogueoflife.nametracker.finder.api.FinderResult;
import org.catalogueoflife.nametracker.finder.api.NameFinder;

/**
 * A {@link NameFinder} whose delegate can be replaced while it runs, for a new dictionary after an XR switch.
 * {@link #swap} is safe against concurrent {@code find()} calls: a call in flight finishes on the delegate it started
 * with. {@link #info()} is the delegate's, so the {@code configHash} that {@link FinderRollout} reads does not change
 * with the dictionary.
 */
public class ReloadableNameFinder implements NameFinder {

  private final AtomicReference<NameFinder> delegate;

  public ReloadableNameFinder(NameFinder initial) {
    this.delegate = new AtomicReference<>(initial);
  }

  /** From now on new calls go to {@code next}. */
  public void swap(NameFinder next) {
    delegate.set(next);
  }

  @Override
  public FinderInfo info() {
    return delegate.get().info();
  }

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    return delegate.get().find(doc, opts);
  }
}
