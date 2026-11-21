package org.catalogueoflife.nametracker.indexer.link;

import org.catalogueoflife.nametracker.store.ident.IdScheme.Entity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Whether asking the identifier lookups about an entity can do anything: the master switch
 * {@code nametracker.links.enabled} is on and at least one lookup of the entity is enabled. Producers of single
 * {@code LINK_WORK} tasks ask it first, so no task waits in the queue for a handler that is off.
 */
@Component
public class LinkGate {

  private final LinkProperties props;
  private final ObjectProvider<IdentifierLookup> lookups;

  public LinkGate(LinkProperties props, ObjectProvider<IdentifierLookup> lookups) {
    this.props = props;
    this.lookups = lookups;
  }

  public boolean on(Entity entity) {
    return props.enabled() && lookups.orderedStream().anyMatch(l -> l.appliesTo() == entity && l.enabled());
  }
}
