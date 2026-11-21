-- M6c Task 13: precomputed language breakdowns, filled by REBUILD_STATS (never a live GROUP BY over work on a public request).
-- V21 holds the other M6c tables; it is already applied on dev, so these two are a new, expand-only migration.

-- listed works per taxon (itself or any ancestor on work_name.taxon_path), work language ('' = undecided) and year
CREATE TABLE taxon_language_stats (
  taxon_id text NOT NULL,
  language text NOT NULL DEFAULT '',
  year int NOT NULL,
  works int NOT NULL,
  PRIMARY KEY (taxon_id, language, year)
);

-- listed works per language and year over the filters of /api/stats/languages. taxgroup, code and country are the
-- value of the dimension, or '' for "not filtered on it": every combination of the three is a row (a cube), so a
-- request reads one slice and never adds up rows of a work that holds several groups or codes.
CREATE TABLE language_stats (
  taxgroup text NOT NULL DEFAULT '',
  code text NOT NULL DEFAULT '',
  country text NOT NULL DEFAULT '',
  language text NOT NULL DEFAULT '',
  year int NOT NULL,
  works bigint NOT NULL,
  PRIMARY KEY (taxgroup, code, country, language, year)
);
