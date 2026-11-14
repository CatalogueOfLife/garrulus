package org.catalogueoflife.nametracker.finder.api;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public record Evidence(Set<Generator> generators, @Nullable Double gnfinderOddsLog10, @Nullable JudgeVerdict judge) {
  public Evidence {
    // enum order keeps the JSON stable
    EnumSet<Generator> g = EnumSet.noneOf(Generator.class);
    if (generators != null) g.addAll(generators);
    generators = Collections.unmodifiableSet(g);
  }
}
