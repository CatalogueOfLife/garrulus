package org.catalogueoflife.garrulus.store.source;

/**
 * When a harvested journal's name density and act rate (written nightly by {@code CATEGORISE_SOURCES}) propose a tier
 * change: PROMOTE a BROAD journal with an act rate of at least {@code promoteActRate} and {@code promoteNames} names
 * per work; DEMOTE a CORE journal with an act rate below {@code demoteCoreActRate} and fewer than
 * {@code demoteCoreNames} names per work, and a BROAD one with fewer than {@code demoteBroadNames}.
 */
public record ProposalThresholds(float promoteActRate, float promoteNames, float demoteCoreActRate,
    float demoteCoreNames, float demoteBroadNames) {
}
