package org.catalogueoflife.nametracker.eval.corpus;

import org.catalogueoflife.nametracker.finder.api.ActType;

/** A nomenclatural act (e.g. "sp. nov."); the span covers the status text, nameText is the name it applies to. */
public record GoldAct(String blockId, int start, int end, ActType type, String nameText) {}
