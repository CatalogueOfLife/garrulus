-- M6a: work identity (identifiers, sightings), source routes, layer health.

CREATE TABLE work_identifier (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  scheme text NOT NULL,
  value text NOT NULL,
  first_layer text,
  first_seen timestamptz NOT NULL DEFAULT now(),
  UNIQUE (scheme, value)
);
CREATE INDEX work_identifier_work_idx ON work_identifier (work_id);

CREATE TABLE work_sighting (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  layer text NOT NULL,
  source_id bigint,
  first_seen timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (work_id, layer)
);
CREATE INDEX work_sighting_layer_idx ON work_sighting (layer, first_seen);

CREATE TABLE source_route (
  id bigserial PRIMARY KEY,
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  layer text NOT NULL,
  config jsonb NOT NULL,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','ACTIVE','PAUSED','BROKEN')),
  origin text NOT NULL CHECK (origin IN ('SEED','DISCOVERED','ADMIN','REGISTERED')),
  verified_at timestamptz,
  last_ok timestamptz,
  last_error text,
  onboarded_at timestamptz,
  backfilled_at timestamptz,
  created timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_id, layer, config)
);

CREATE TABLE layer_health (
  layer text NOT NULL,
  day date NOT NULL,
  state text NOT NULL CHECK (state IN ('OK','DEGRADED','FAILING')),
  failed_runs int NOT NULL DEFAULT 0,
  yield int NOT NULL DEFAULT 0,
  median double precision NOT NULL DEFAULT 0,
  miss_share double precision NOT NULL DEFAULT 0,
  PRIMARY KEY (layer, day)
);

CREATE TABLE source_layer_coverage (
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  layer text NOT NULL,
  sightings_28d int NOT NULL DEFAULT 0,
  misses int NOT NULL DEFAULT 0,
  updated timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, layer)
);

CREATE TABLE layer_unmatched (
  layer text NOT NULL,
  doi text PRIMARY KEY,
  issn text,
  container_title text,
  first_seen timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE work ADD COLUMN meta_layer text, ADD COLUMN abstract_jats_source text, ADD COLUMN access_layer text;
UPDATE work SET abstract_jats_source = 'CROSSREF' WHERE abstract_jats IS NOT NULL;
-- owners of the existing rows: OpenAlex where it delivered the record, else Crossref (until V16 the last writer won);
-- access fields only where there is an access value
UPDATE work SET
  meta_layer = CASE WHEN openalex_id IS NOT NULL THEN 'OPENALEX' ELSE 'CROSSREF' END,
  access_layer = CASE
    WHEN openalex_id IS NOT NULL AND (oa_status IS NOT NULL OR license IS NOT NULL OR best_oa_url IS NOT NULL)
      THEN 'OPENALEX'
    WHEN license IS NOT NULL THEN 'CROSSREF'
  END;
-- the candidates of WorkUpsert's title match: all of them for a record without a DOI, only DOI-less works for one with
CREATE INDEX work_source_year_idx ON work (source_id, year);
CREATE INDEX work_source_year_nodoi_idx ON work (source_id, year) WHERE doi IS NULL;

-- a work may now be known by other identifiers only (V1 generated name: work_check)
ALTER TABLE work DROP CONSTRAINT work_check;
ALTER TABLE work DROP CONSTRAINT work_abstract_source_check;
ALTER TABLE work ADD CONSTRAINT work_abstract_source_check
  CHECK (abstract_source IN ('CROSSREF_JATS','OPENALEX_INVERTED','PUBLISHER','EUROPEPMC','OAI','MAGTECH'));

INSERT INTO work_identifier (work_id, scheme, value, first_layer) SELECT id, 'DOI', doi, 'CROSSREF' FROM work WHERE doi IS NOT NULL ORDER BY id ON CONFLICT DO NOTHING;
INSERT INTO work_identifier (work_id, scheme, value, first_layer) SELECT id, 'OPENALEX', openalex_id, 'OPENALEX' FROM work WHERE openalex_id IS NOT NULL ORDER BY id ON CONFLICT DO NOTHING;
INSERT INTO work_identifier (work_id, scheme, value, first_layer) SELECT id, 'PMCID', pmcid, 'EUROPEPMC' FROM work WHERE pmcid IS NOT NULL ORDER BY id ON CONFLICT DO NOTHING;

INSERT INTO work_sighting (work_id, layer, source_id) SELECT id, 'OPENALEX', source_id FROM work WHERE openalex_id IS NOT NULL ORDER BY id ON CONFLICT DO NOTHING;
INSERT INTO work_sighting (work_id, layer, source_id) SELECT id, 'CROSSREF', source_id FROM work WHERE doi IS NOT NULL AND openalex_id IS NULL;
