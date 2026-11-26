package org.catalogueoflife.garrulus.indexer.finder;

import java.util.concurrent.atomic.AtomicReference;
import org.catalogueoflife.garrulus.finder.api.Document;
import org.catalogueoflife.garrulus.finder.api.FinderInfo;
import org.catalogueoflife.garrulus.finder.api.FinderOptions;
import org.catalogueoflife.garrulus.finder.api.FinderResult;
import org.catalogueoflife.garrulus.finder.api.NameFinder;
import org.catalogueoflife.garrulus.finder.remote.FinderUnavailableException;
import org.jspecify.annotations.Nullable;

/**
 * A {@link NameFinder} whose delegate can be replaced while it runs, for a new dictionary after an XR switch.
 * {@link #swap} is safe against concurrent {@code find()} calls: a call in flight finishes on the delegate it started
 * with. {@link #info()} is the delegate's, so the {@code configHash} that {@link FinderRollout} reads does not change
 * with the dictionary. Without a delegate (a fresh deployment before its first dictionary) it is not {@link #ready()} and
 * throws {@link FinderUnavailableException}, so FIND_NAMES tasks wait like for an outage.
 */
public class ReloadableNameFinder implements NameFinder {

  private final AtomicReference<@Nullable NameFinder> delegate;

  /** {@code initial} null: no dictionary yet, the finder is not {@link #ready()} until the first {@link #swap}. */
  public ReloadableNameFinder(@Nullable NameFinder initial) {
    this.delegate = new AtomicReference<>(initial);
  }

  /** False while there is no delegate; {@code info()} and {@code find()} then throw {@link FinderUnavailableException}. */
  public boolean ready() {
    return delegate.get() != null;
  }

  private NameFinder current() {
    NameFinder f = delegate.get();
    if (f == null) throw new FinderUnavailableException("no dictionary has been built yet");
    return f;
  }

  /** From now on new calls go to {@code next}. */
  public void swap(NameFinder next) {
    delegate.set(next);
  }

  @Override
  public FinderInfo info() {
    return current().info();
  }

  @Override
  public FinderResult find(Document doc, FinderOptions opts) {
    return current().find(doc, opts);
  }
}
