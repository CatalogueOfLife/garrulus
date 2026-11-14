package org.catalogueoflife.nametracker.finder.pipeline;

import org.catalogueoflife.nametracker.finder.api.NameKind;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

/** A merged candidate after name parsing: canonical name without authorship, kind, rank and nomenclatural code. */
public record ParsedCandidate(MergedCandidate c, String canonical, NameKind kind, @Nullable Rank rank, @Nullable NomCode code) {}
