package org.catalogueoflife.nametracker.finder.api;

/** Code-point range [start, end) of a block text. */
public record StyleSpan(int start, int end, Style style) {}
