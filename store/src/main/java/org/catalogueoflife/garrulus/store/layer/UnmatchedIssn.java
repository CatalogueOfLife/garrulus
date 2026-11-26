package org.catalogueoflife.garrulus.store.layer;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The unmatched DOIs of one ISSN: how many, the newest first sighting, one example and a container title. */
public record UnmatchedIssn(String issn, @Nullable String containerTitle, int dois, Instant lastSeen, String example) {
}
