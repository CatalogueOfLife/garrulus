package org.catalogueoflife.garrulus.finder.api;

import org.jspecify.annotations.Nullable;

public record JudgeVerdict(String judge, double score, boolean accepted, @Nullable String correctedName) {}
