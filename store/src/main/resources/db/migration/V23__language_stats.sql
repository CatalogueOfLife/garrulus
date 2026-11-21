-- M6c Task 13: precomputed language breakdowns, filled by REBUILD_STATS (never a live GROUP BY over work on a public request).
-- V21 holds the other M6c tables; these two are separate so V21 stayed as written. V21 to V23 apply together at the M6c deploy.

-- listed works per taxon (itself or any ancestor on work_name.taxon_path), work language ('' = undecided) and year
CREATE TABLE taxon_language_stats (
  taxon_id text NOT NULL,
  language text NOT NULL DEFAULT '',
  year int NOT NULL,
  works int NOT NULL,
  PRIMARY KEY (taxon_id, language, year)
);

-- listed works per language and year for one dimension at a time (no endpoint combines them): dim 'none' (value ''
-- = all works), 'taxgroup' (a group the work holds), 'code' (a nomenclatural code it holds) or 'country' (of its
-- journal). A work counts once per group or code it holds, so only a slice of one dimension is a count of works.
CREATE TABLE language_stats (
  dim text NOT NULL CHECK (dim IN ('none','taxgroup','code','country')),
  value text NOT NULL DEFAULT '',
  language text NOT NULL DEFAULT '',
  year int NOT NULL,
  works bigint NOT NULL,
  PRIMARY KEY (dim, value, language, year)
);
