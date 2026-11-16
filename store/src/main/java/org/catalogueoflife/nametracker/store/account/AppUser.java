package org.catalogueoflife.nametracker.store.account;

import java.time.Instant;

/** Mirrors the {@code app_user} table; id, created and last_login are ignored on insert. */
public record AppUser(
    Long id,
    String email,
    Instant emailVerified,
    String orcid,
    String displayName,
    String timeZone,
    String digestFrequency,
    Integer digestWeekday,
    Integer digestHour,
    Instant digestSince,
    Instant created,
    Instant lastLogin) {
}
