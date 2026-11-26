package org.catalogueoflife.garrulus.finder.salience;

/** How much a work is about a name. Counts passed in exclude REFERENCE blocks. */
public final class Salience {

  /** {@code ubiquityShare} is the share of works that mention the name, 0 if unknown. */
  public record Inputs(boolean inTitle, boolean inAbstract, boolean hasAct, boolean inHeading, int bodyCount,
      int maxBodyCountInWork, double ubiquityShare) {}

  private final SalienceProperties p;

  public Salience(SalienceProperties p) {
    this.p = p;
  }

  /** The score, clamped to 0..1. A name without a body mention scores 0. */
  public double score(Inputs i) {
    if (i.bodyCount() <= 0) return 0;
    double s = (i.inTitle() ? p.titleWeight() : 0) + (i.inAbstract() ? p.abstractWeight() : 0)
        + (i.hasAct() ? p.actWeight() : 0) + (i.inHeading() ? p.headingWeight() : 0);
    if (i.maxBodyCountInWork() > 0) s += p.shareWeight() * i.bodyCount() / i.maxBodyCountInWork();
    s += p.repeatWeight() * Math.min(1, (i.bodyCount() - 1) / 4.0);
    if (p.ubiquityFullShare() > 0) s -= p.ubiquityWeight() * Math.min(1, i.ubiquityShare() / p.ubiquityFullShare());
    return Math.max(0, Math.min(1, s));
  }

  public boolean isMainSubject(double salience) {
    return salience >= p.threshold();
  }
}
