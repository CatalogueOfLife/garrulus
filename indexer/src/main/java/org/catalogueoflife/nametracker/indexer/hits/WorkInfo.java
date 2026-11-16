package org.catalogueoflife.nametracker.indexer.hits;

import org.jspecify.annotations.Nullable;

/** The work facts the subscription rules need; textLevel is FULLTEXT, ABSTRACT_ONLY or NO_TEXT. */
public record WorkInfo(long id, @Nullable Long sourceId, String textLevel) {}
