ALTER TABLE source ADD COLUMN onboarded_at timestamptz;
ALTER TABLE source ADD COLUMN tier_set_by text NOT NULL DEFAULT 'SEED' CHECK (tier_set_by IN ('SEED','ADMIN'));
ALTER TABLE work ADD COLUMN abstract_jats text;
ALTER TABLE work ADD COLUMN pmcid text;
ALTER TABLE work ADD COLUMN openalex_has_tei boolean NOT NULL DEFAULT false;
ALTER TABLE work ADD COLUMN openalex_checked_at timestamptz;
CREATE INDEX work_state_idx ON work (state);
CREATE TABLE api_usage (
  service text NOT NULL, day date NOT NULL,
  calls integer NOT NULL DEFAULT 0, usd numeric(10,4) NOT NULL DEFAULT 0,
  remaining_usd numeric(10,4),
  PRIMARY KEY (service, day));
CREATE TABLE harvest_run (
  id bigserial PRIMARY KEY, adapter text NOT NULL, window_from date, window_to date,
  started timestamptz NOT NULL DEFAULT now(), finished timestamptz,
  calls integer NOT NULL DEFAULT 0, works_seen integer NOT NULL DEFAULT 0, works_new integer NOT NULL DEFAULT 0,
  state text NOT NULL DEFAULT 'RUNNING' CHECK (state IN ('RUNNING','DONE','DEFERRED','FAILED')), error text);
