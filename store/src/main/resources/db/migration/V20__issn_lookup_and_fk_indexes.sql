-- M6b fix wave: ISSN lookups use the ISSN indexes, and the two new foreign keys of V19 get their indexes.
-- Expand-only.

-- ISSNs are stored upper case in the hyphenated form (NNNN-NNNC), so findByIssn compares them as they are
-- (issn_l = x OR issns @> ARRAY[x]) and Postgres can use source_issn_l_idx and the GIN source_issns_idx. Seeding used
-- to store OpenAlex's ISSNs as given; this back-fill brings stored values into that form. Idempotent: only rows that
-- change are written. A value that is not eight ISSN characters is only trimmed and upper-cased. pg_temp functions
-- vanish with the session.
CREATE FUNCTION pg_temp.v20_issn(raw text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN c ~ '^[0-9]{7}[0-9X]$' THEN substr(c, 1, 4) || '-' || substr(c, 5) ELSE upper(btrim(raw)) END
  FROM (SELECT upper(regexp_replace(raw, '[-– [:space:]]', '', 'g')) AS c) x
$$;

CREATE FUNCTION pg_temp.v20_issns(raw text[]) RETURNS text[] LANGUAGE sql IMMUTABLE AS $$
  SELECT COALESCE(array_agg(n ORDER BY o), '{}')
  FROM (SELECT DISTINCT ON (pg_temp.v20_issn(i)) pg_temp.v20_issn(i) AS n, o
        FROM unnest(raw) WITH ORDINALITY u(i, o) WHERE i IS NOT NULL
        ORDER BY pg_temp.v20_issn(i), o) d
$$;

UPDATE source SET issn_l = pg_temp.v20_issn(issn_l), issns = pg_temp.v20_issns(issns)
WHERE issn_l IS DISTINCT FROM pg_temp.v20_issn(issn_l) OR issns IS DISTINCT FROM pg_temp.v20_issns(issns);

-- source_registration.source_id: every fold moves registrations, ON DELETE SET NULL, the admin list's registered
-- filter and counts. source.continued_by: the foreign key check of every fold's DELETE and its continued_by repoint.
CREATE INDEX source_registration_source_idx ON source_registration (source_id);
CREATE INDEX source_continued_by_idx ON source (continued_by) WHERE continued_by IS NOT NULL;
