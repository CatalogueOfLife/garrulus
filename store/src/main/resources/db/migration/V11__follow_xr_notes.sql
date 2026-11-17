-- M5 XR switch, follows on removed taxa (expand-only). After the works are re-placed in a new XR release, XR_FINISH
-- looks up every TAXON follow whose id that release may not have: a follow whose id CLB reports superseded moves to
-- the successor (target_status MOVED, previous_target keeps the old id), any other one is flagged MISSING and keeps
-- its id. xr_note is the line shown on the Following page and in the digest footer, xr_note_at when it was written.
-- The XR switch is the one writer of these four columns and, for a move, of target.
ALTER TABLE subscription
  ADD COLUMN target_status text NOT NULL DEFAULT 'OK' CHECK (target_status IN ('OK', 'MOVED', 'MISSING')),
  ADD COLUMN xr_note text,
  ADD COLUMN xr_note_at timestamptz,
  ADD COLUMN previous_target text;

-- XR_REPLACE takes the listed works placed in another release in id order, per release they are placed in
CREATE INDEX work_placed_release_idx ON work (placed_release, id) WHERE state IN ('INDEXED', 'NOTIFIED');
