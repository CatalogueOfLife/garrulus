-- M6a: when the Crossref history of a journal (route backfill, HARVEST_CROSSREF_HISTORY) last ran to its end, so that
-- later automatic route backfills do not read it again (an admin ROUTE_BACKFILL with "force":true does).
ALTER TABLE source ADD COLUMN crossref_history_at timestamptz;
