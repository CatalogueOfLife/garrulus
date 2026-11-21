-- Where a recurring job that walks a table in id order stopped, by job name (first user: the link sweep's walk over
-- listed works that no identifier lookup has seen yet, 'link-untried:W'). No existing table keeps such state: the
-- task rows that would carry it are purged after 14 days.
CREATE TABLE job_cursor (
  name text PRIMARY KEY,
  position bigint NOT NULL,
  updated timestamptz NOT NULL DEFAULT now()
);
