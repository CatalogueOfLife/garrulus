package org.catalogueoflife.garrulus.indexer.registry;

/** A way to reach a discovered journal: the layer (OAI, MAGTECH, EUROPEPMC) and its config as a JSON object. */
public record RouteHint(String layer, String configJson) {
}
