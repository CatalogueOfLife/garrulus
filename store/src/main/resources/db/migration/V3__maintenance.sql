ALTER TABLE api_usage ADD COLUMN key_fingerprint text;
CREATE INDEX task_type_state_idx ON task (type, state);
CREATE INDEX task_done_finished_idx ON task (finished) WHERE state = 'DONE';
