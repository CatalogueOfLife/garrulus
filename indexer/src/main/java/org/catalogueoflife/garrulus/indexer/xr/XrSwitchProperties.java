package org.catalogueoflife.garrulus.indexer.xr;

/**
 * The XR switch: {@code maxLostShare} ({@code garrulus.xr.max-lost-share}) is the share of matched keys a new
 * release may lose before the gate refuses it; {@code matchParallelism} ({@code garrulus.clb.match-parallelism})
 * is the number of parallel CLB match calls of one {@code XR_PREMATCH} task.
 */
public record XrSwitchProperties(double maxLostShare, int matchParallelism) {

  public XrSwitchProperties {
    if (maxLostShare < 0 || maxLostShare > 1) throw new IllegalArgumentException("max-lost-share not in [0,1]");
    if (matchParallelism < 1) throw new IllegalArgumentException("match-parallelism < 1");
  }
}
