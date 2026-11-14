CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE source (
  id bigserial PRIMARY KEY,
  openalex_id text UNIQUE, issn_l text, issns text[] NOT NULL DEFAULT '{}',
  title text NOT NULL, publisher text, host text, is_oa boolean, in_doaj boolean,
  tier text NOT NULL DEFAULT 'CANDIDATE' CHECK (tier IN ('CORE','BROAD','CANDIDATE','EXCLUDED')),
  origin text NOT NULL CHECK (origin IN ('SEED_TOPIC','SEED_LIST','DISCOVERED','MANUAL')),
  adapter_config jsonb NOT NULL DEFAULT '{}', active boolean NOT NULL DEFAULT true,
  subfields text[] NOT NULL DEFAULT '{}', primary_subfield text,
  journal_type text CHECK (journal_type IN ('TAXONOMIC','GENERAL')),
  taxgroups text[] NOT NULL DEFAULT '{}', taxgroup_shares jsonb NOT NULL DEFAULT '{}', codes text[] NOT NULL DEFAULT '{}',
  oa_model text CHECK (oa_model IN ('DIAMOND','GOLD','HYBRID','CLOSED')), country text,
  name_density real, act_rate real, works_per_year integer, last_harvested timestamptz,
  created timestamptz NOT NULL DEFAULT now(), modified timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX source_subfields_idx ON source USING gin (subfields);
CREATE INDEX source_taxgroups_idx ON source USING gin (taxgroups);
CREATE INDEX source_codes_idx ON source USING gin (codes);

CREATE TABLE work (
  id bigserial PRIMARY KEY,
  openalex_id text UNIQUE, doi text UNIQUE,          -- doi lower-case, without https://doi.org/
  source_id bigint REFERENCES source,
  title text, publication_date date, year smallint, volume text, issue text, pages text,
  authors jsonb NOT NULL DEFAULT '[]', type text, language text,
  oa_status text, license text, best_oa_url text,
  abstract text, abstract_source text CHECK (abstract_source IN ('CROSSREF_JATS','OPENALEX_INVERTED','PUBLISHER')),
  origin text NOT NULL CHECK (origin IN ('LIVE','SWEEP','ONBOARDING','BACKFILL')),
  state text NOT NULL DEFAULT 'DISCOVERED'
    CHECK (state IN ('DISCOVERED','CONTENT_RESOLVED','NAMES_FOUND','MATCHED','INDEXED','NOTIFIED')),
  text_level text CHECK (text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT')),
  codes text[] NOT NULL DEFAULT '{}', taxgroups text[] NOT NULL DEFAULT '{}', has_acts boolean NOT NULL DEFAULT false,
  first_seen_at timestamptz NOT NULL DEFAULT now(), modified timestamptz NOT NULL DEFAULT now(),
  CHECK (openalex_id IS NOT NULL OR doi IS NOT NULL)
);
CREATE INDEX work_source_idx ON work (source_id);
CREATE INDEX work_pubdate_idx ON work (publication_date);
CREATE INDEX work_codes_idx ON work USING gin (codes);
CREATE INDEX work_taxgroups_idx ON work USING gin (taxgroups);

CREATE TABLE content (
  id bigserial PRIMARY KEY,
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  route text NOT NULL CHECK (route IN ('PENSOFT','EUROPEPMC','PMC','OPENALEX_TEI','GROBID_LOCAL','ABSTRACT')),
  kind text NOT NULL CHECK (kind IN ('TAXPUB','JATS','TEI','ABSTRACT')),
  license text, retained boolean NOT NULL, raw_sha256 text, doc_sha256 text, source_url text,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (work_id, route)
);

CREATE TABLE task (
  id bigserial PRIMARY KEY,
  type text NOT NULL, payload jsonb NOT NULL DEFAULT '{}',
  priority smallint NOT NULL,
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','DONE','DEAD')),
  attempts smallint NOT NULL DEFAULT 0, run_after timestamptz NOT NULL DEFAULT now(),
  locked_by text, locked_at timestamptz, last_error text, dedup_key text,
  created timestamptz NOT NULL DEFAULT now(), finished timestamptz
);
CREATE INDEX task_claim_idx ON task (priority, run_after, id) WHERE state = 'QUEUED';
CREATE UNIQUE INDEX task_dedup_idx ON task (dedup_key) WHERE state IN ('QUEUED','RUNNING');

CREATE TABLE shedlock (
  name varchar(64) PRIMARY KEY, lock_until timestamptz NOT NULL,
  locked_at timestamptz NOT NULL, locked_by varchar(255) NOT NULL
);
