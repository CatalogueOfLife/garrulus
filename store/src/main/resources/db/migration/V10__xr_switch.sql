-- M5 automatic XR switch (expand-only). A release moves SEEN -> PREPARING (pre-match) -> PREPARED (gate pending) ->
-- SWITCHING (current, works being re-placed) -> CURRENT -> RETIRED; the gate or an admin can make it REFUSED.
-- `current` stays the one flag the readers use and is flipped together with `state`. `note` is a JSON object: the
-- switch options (refuse, force) and, for a REFUSED release, who refused it (refusedBy: gate, admin, rollback).
-- `switch_gen` counts the switches started to the release; the pre-match and gate tasks carry it, so that a chain of
-- an earlier (cancelled) switch to the same release stops instead of running alongside the new one.
ALTER TABLE xr_release
  ADD COLUMN state text NOT NULL DEFAULT 'SEEN'
    CHECK (state IN ('SEEN', 'PREPARING', 'PREPARED', 'SWITCHING', 'CURRENT', 'RETIRED', 'REFUSED')),
  ADD COLUMN switch_started timestamptz,
  ADD COLUMN switched_at timestamptz,
  ADD COLUMN keys_total integer,
  ADD COLUMN keys_lost integer,
  ADD COLUMN note text,
  ADD COLUMN switch_gen integer NOT NULL DEFAULT 0;

UPDATE xr_release SET state = 'CURRENT' WHERE current;
