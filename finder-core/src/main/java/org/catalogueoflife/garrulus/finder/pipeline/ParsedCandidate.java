package org.catalogueoflife.garrulus.finder.pipeline;

import org.catalogueoflife.garrulus.finder.api.NameKind;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

/**
 * A merged candidate after name parsing: canonical name without authorship, kind, rank and nomenclatural code.
 *
 * <p>{@code canonical} is parser-normalised and lossy: the subgenus is dropped for binomials ("Carabus (Megodontus) nigrus"
 * gives "Carabus nigrus") and infrageneric names come out as "Carabus infragen. Megodontus". Use it for matching and
 * indexing only; {@code c().name()}, the expanded verbatim-shaped name, is the mention name.
 */
public record ParsedCandidate(MergedCandidate c, String canonical, NameKind kind, @Nullable Rank rank, @Nullable NomCode code) {}
