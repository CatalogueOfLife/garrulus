package org.catalogueoflife.garrulus.indexer.layer;

/**
 * Why a layer reads a route: the daily harvest ({@code LIVE}), the first 120-day pass of a new route
 * ({@code ONBOARDING}) or the route's history back to 2000 ({@code BACKFILL}).
 */
public enum LayerMode {
  LIVE, ONBOARDING, BACKFILL
}
