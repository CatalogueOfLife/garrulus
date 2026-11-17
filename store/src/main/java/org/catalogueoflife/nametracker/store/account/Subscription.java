package org.catalogueoflife.nametracker.store.account;

import java.time.Instant;

/** Mirrors the {@code subscription} table; {@code filters} is a JSON object as text. Id and created are ignored on insert; target_status, xr_note, xr_note_at and previous_target are read only. */
public record Subscription(
    Long id,
    long userId,
    String type,
    String target,
    String label,
    boolean mainSubjectOnly,
    boolean newNamesOnly,
    boolean includeAbstractOnly,
    boolean includeReferences,
    String filters,
    String feedToken,
    Integer sinceYear,
    boolean paused,
    Instant created,
    String targetStatus,
    String xrNote,
    Instant xrNoteAt,
    String previousTarget) {

  /** A follow without an XR note (target_status OK); the four XR columns are written by the XR switch only. */
  public Subscription(Long id, long userId, String type, String target, String label, boolean mainSubjectOnly,
      boolean newNamesOnly, boolean includeAbstractOnly, boolean includeReferences, String filters, String feedToken,
      Integer sinceYear, boolean paused, Instant created) {
    this(id, userId, type, target, label, mainSubjectOnly, newNamesOnly, includeAbstractOnly, includeReferences,
        filters, feedToken, sinceYear, paused, created, "OK", null, null, null);
  }
}
