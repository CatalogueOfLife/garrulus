-- The search covers the abstract the work page shows: the Crossref JATS abstract first (a work harvested from Crossref
-- alone has no other), else the OpenAlex one. Paragraph-like tags become spaces, other tags go without a trace
-- (CO<sub>2</sub> stays CO2), entities are dropped. Postgres 17 rewrites the column and rebuilds work_fts_idx.
ALTER TABLE work ALTER COLUMN fts SET EXPRESSION AS (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(
  nullif(btrim(regexp_replace(regexp_replace(regexp_replace(abstract_jats,
    '</?(jats:)?(p|title|sec|br|div|list|list-item)([[:space:]/][^<>]*)?>', ' ', 'gi'),
    '<[a-zA-Z/!][^<>]*>', '', 'g'),
    '&#?[a-zA-Z0-9]+;', ' ', 'g')), ''),
  abstract, '')));
