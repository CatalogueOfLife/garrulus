-- M5 backfill from the OpenAlex snapshot (expand-only). One backfill_run per BACKFILL task: the year range, the
-- snapshot (manifest) date and the number of works files it covers. One backfill_file per file, idx being the file's
-- position among the manifest's works files; BACKFILL_FILE writes its counters and done_at once, at the end of the
-- file, and the last file of a run marks the run DONE.
CREATE TABLE backfill_run (
  id bigserial PRIMARY KEY,
  from_year smallint NOT NULL,
  to_year smallint NOT NULL,
  snapshot_date date NOT NULL,
  files_total integer NOT NULL,
  state text NOT NULL CHECK (state IN ('RUNNING','DONE')),
  created timestamptz NOT NULL DEFAULT now(),
  finished timestamptz);

CREATE TABLE backfill_file (
  run_id bigint REFERENCES backfill_run ON DELETE CASCADE,
  idx integer,
  url text NOT NULL,
  records_read bigint,
  records_kept bigint,
  works_created bigint,
  done_at timestamptz,
  PRIMARY KEY (run_id, idx));
