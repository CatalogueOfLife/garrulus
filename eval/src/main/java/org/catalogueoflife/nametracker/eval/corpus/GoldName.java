package org.catalogueoflife.nametracker.eval.corpus;

import org.gbif.nameparser.api.Rank;
import org.jspecify.annotations.Nullable;

/** A publisher-tagged name; offsets are code points of the block text, end exclusive. fullName is joined from the reg attributes. */
public record GoldName(String blockId, int start, int end, String text, @Nullable String fullName, @Nullable Rank rank) {}
