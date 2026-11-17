-- M5 finder reprocessing (expand-only). One row per finder configHash the indexer started with: the first one ever
-- seen is the baseline (nothing reprocessed); every later new hash is a rollout, whose REPROCESS_NAMES walk sets
-- enqueued_at when its last batch has been enqueued.
CREATE TABLE finder_rollout (
  config_hash text PRIMARY KEY,
  started timestamptz NOT NULL DEFAULT now(),
  enqueued_at timestamptz,
  baseline boolean NOT NULL DEFAULT false);
