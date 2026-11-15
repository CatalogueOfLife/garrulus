package org.catalogueoflife.nametracker.store.index;

import java.time.Instant;

public record XrRelease(int key, String alias, Instant firstSeen, boolean current) {}
