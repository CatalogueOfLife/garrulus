CREATE TABLE finder_run (id bigserial PRIMARY KEY, name text NOT NULL, version text NOT NULL, config_hash text NOT NULL,
  dictionary_key text, created timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (name, version, config_hash, dictionary_key));
ALTER TABLE work ADD COLUMN current_finder_run bigint REFERENCES finder_run, ADD COLUMN names_doc_sha text,
  ADD COLUMN finder_diagnostics jsonb NOT NULL DEFAULT '{}', ADD COLUMN indexed_at timestamptz,
  ADD COLUMN fts tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(abstract,''))) STORED;
CREATE INDEX work_fts_idx ON work USING gin (fts);
CREATE TABLE name_string (id bigserial PRIMARY KEY, name text NOT NULL UNIQUE, kind text NOT NULL, rank text,
  created timestamptz NOT NULL DEFAULT now());
CREATE INDEX name_string_trgm_idx ON name_string USING gin (name gin_trgm_ops);
CREATE TABLE classification_context (id bigserial PRIMARY KEY, work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  local_id text NOT NULL, verbatim text NOT NULL, ranks jsonb NOT NULL, scope text NOT NULL, block_id text NOT NULL,
  until_block_id text, UNIQUE (work_id, local_id));
CREATE TABLE mention (id bigserial PRIMARY KEY, work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  name_string_id bigint NOT NULL REFERENCES name_string, block_id text NOT NULL, block_type text NOT NULL,
  start_cp integer NOT NULL, end_cp integer NOT NULL, verbatim text NOT NULL, authorship text, qualifier text,
  confidence real NOT NULL, act_type text, context_id bigint REFERENCES classification_context ON DELETE SET NULL,
  snippet text NOT NULL, evidence jsonb NOT NULL DEFAULT '{}');
CREATE INDEX mention_work_idx ON mention (work_id);
CREATE INDEX mention_name_idx ON mention (name_string_id);
CREATE TABLE xr_release (key integer PRIMARY KEY, alias text NOT NULL, first_seen timestamptz NOT NULL DEFAULT now(),
  current boolean NOT NULL DEFAULT false);
CREATE UNIQUE INDEX xr_release_current_idx ON xr_release (current) WHERE current;
CREATE TABLE name_match (name_string_id bigint NOT NULL REFERENCES name_string, hint_key text NOT NULL,
  release_key integer NOT NULL REFERENCES xr_release, match_type text NOT NULL, usage_id text, usage_name text,
  usage_rank text, usage_status text, accepted_id text, code text, taxgroup text, taxon_path text[] NOT NULL DEFAULT '{}',
  classification jsonb NOT NULL DEFAULT '[]', matched_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (name_string_id, hint_key, release_key));
CREATE TABLE work_name (work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  name_string_id bigint NOT NULL REFERENCES name_string, mention_count integer NOT NULL, body_count integer NOT NULL,
  in_title boolean NOT NULL, in_abstract boolean NOT NULL, in_heading boolean NOT NULL, only_reference boolean NOT NULL,
  act_types text[] NOT NULL DEFAULT '{}', best_snippet text NOT NULL,
  placement_method text NOT NULL CHECK (placement_method IN ('MATCH','GENUS','VERBATIM_CONTEXT','NONE')),
  usage_id text, taxon_path text[] NOT NULL DEFAULT '{}', code text, taxgroup text,
  salience real NOT NULL, main_subject boolean NOT NULL, PRIMARY KEY (work_id, name_string_id));
CREATE INDEX work_name_path_idx ON work_name USING gin (taxon_path);
CREATE INDEX work_name_name_idx ON work_name (name_string_id);
CREATE TABLE name_ubiquity (name_string_id bigint PRIMARY KEY REFERENCES name_string, works integer NOT NULL,
  share real NOT NULL, computed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE taxon_stats (taxon_id text NOT NULL, year smallint NOT NULL, works integer NOT NULL,
  main_subject_works integer NOT NULL, PRIMARY KEY (taxon_id, year));
