-- M5 no gaps while re-indexing (expand-only). A re-index of a listed work writes the finder's result into the pending
-- tables; MATCH_NAMES moves it into the live tables in one transaction. The ids come from the live tables' sequences,
-- so they survive the move.

CREATE TABLE classification_context_pending (
  id bigint PRIMARY KEY DEFAULT nextval('classification_context_id_seq'),
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  local_id text NOT NULL, verbatim text NOT NULL, ranks jsonb NOT NULL, scope text NOT NULL, block_id text NOT NULL,
  until_block_id text, UNIQUE (work_id, local_id));

CREATE TABLE mention_pending (
  id bigint PRIMARY KEY DEFAULT nextval('mention_id_seq'),
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  name_string_id bigint NOT NULL REFERENCES name_string, block_id text NOT NULL, block_type text NOT NULL,
  start_cp integer NOT NULL, end_cp integer NOT NULL, verbatim text NOT NULL, authorship text, qualifier text,
  confidence real NOT NULL, act_type text,
  context_id bigint REFERENCES classification_context_pending ON DELETE SET NULL,
  snippet text NOT NULL, evidence jsonb NOT NULL DEFAULT '{}');
CREATE INDEX mention_pending_work_idx ON mention_pending (work_id);

ALTER TABLE work ADD COLUMN pending_finder_run bigint REFERENCES finder_run, ADD COLUMN pending_doc_sha text,
  ADD COLUMN pending_diagnostics jsonb, ADD COLUMN placed_release integer,
  ADD COLUMN reindex_prev_subscriptions bigint[];

-- the release every listed work is placed in today
UPDATE work SET placed_release = (SELECT key FROM xr_release WHERE current) WHERE state IN ('INDEXED', 'NOTIFIED');
