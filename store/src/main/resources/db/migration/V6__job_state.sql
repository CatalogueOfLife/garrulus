-- small key/value state of scheduled jobs, e.g. the name_ubiquity checksum last rescored (expand-only)
CREATE TABLE job_state (key text PRIMARY KEY, value text, updated timestamptz NOT NULL DEFAULT now());
