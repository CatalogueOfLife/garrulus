package org.catalogueoflife.garrulus.finder.gnfinder;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.jspecify.annotations.Nullable;

/** One name from a gnfinder response. start/end are code-point offsets into the submitted text (no bytesOffset), end exclusive. gnfinder sends a null oddsLog10 for some names; that reads as 0. */
public record GnName(int cardinality, String verbatim, String name, @Nullable String abbrName, @JsonSetter(nulls = Nulls.AS_EMPTY) double oddsLog10, int start, int end,
                     @Nullable String annotationNomen, String annotationNomenType) {}
