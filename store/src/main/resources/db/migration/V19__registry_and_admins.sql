-- M6b: journal registry (publication status, signals, ISSN-L links, discovery inputs), disciplines, registrations
-- and admin notices. Expand-only.

ALTER TABLE source DROP CONSTRAINT source_origin_check;
ALTER TABLE source ADD CONSTRAINT source_origin_check CHECK (origin IN ('SEED_TOPIC','SEED_LIST','DISCOVERED','MANUAL',
  'DOAJ','PKP_BEACON','PLAZI','COL_REFERENCES','BLR','REGISTERED'));

ALTER TABLE source
  ADD COLUMN publication_status text NOT NULL DEFAULT 'ACTIVE' CHECK (publication_status IN ('ACTIVE','CEASED')),
  ADD COLUMN ceased_reason text CHECK (ceased_reason IN ('STOPPED','TITLE_CHANGE','MERGED','UNKNOWN')),
  ADD COLUMN last_issue_year smallint,
  ADD COLUMN continued_by bigint REFERENCES source,
  ADD COLUMN status_set_by text CHECK (status_set_by IN ('ADMIN')),
  ADD COLUMN status_changed timestamptz,
  ADD COLUMN dormant_since date,
  ADD COLUMN dormant_evidence jsonb,
  ADD COLUMN dormant_dismissed_at timestamptz,
  ADD COLUMN candidate_score real,
  ADD COLUMN candidate_scored_at timestamptz,
  ADD COLUMN discipline_source text CHECK (discipline_source IN ('OPENALEX','DOAJ','TAXGROUP','ADMIN')),
  ADD COLUMN discipline_set_by text NOT NULL DEFAULT 'DERIVED' CHECK (discipline_set_by IN ('DERIVED','ADMIN')),
  ADD COLUMN doaj_discontinued date,
  ADD COLUMN successor_issns text[] NOT NULL DEFAULT '{}';
CREATE INDEX source_issn_l_idx ON source (issn_l);
CREATE INDEX source_issns_idx ON source USING gin (issns);

CREATE TABLE source_signal (
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  origin text NOT NULL,
  signal_key text NOT NULL DEFAULT '',
  first_seen timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz NOT NULL DEFAULT now(),
  detail jsonb NOT NULL DEFAULT '{}',
  PRIMARY KEY (source_id, origin, signal_key)
);

CREATE TABLE issn_link (
  issn text PRIMARY KEY,
  issn_l text NOT NULL
);
CREATE INDEX issn_link_issn_l_idx ON issn_link (issn_l);

CREATE TABLE pkp_beacon (
  id bigserial PRIMARY KEY,
  context_name text,
  issns text[] NOT NULL,
  oai_url text,
  set_spec text,
  country text,
  total_records int,
  records_2025 int,
  last_oai_response timestamptz,
  unresponsive boolean NOT NULL,
  loaded timestamptz NOT NULL
);
CREATE INDEX pkp_beacon_issns_idx ON pkp_beacon USING gin (issns);

CREATE TABLE discovery_title (
  origin text NOT NULL,
  title_key text NOT NULL,
  title text NOT NULL,
  issn text,
  refs int NOT NULL,
  datasets int,
  detail jsonb NOT NULL DEFAULT '{}',
  resolved_issns text[],
  resolved_at timestamptz,
  PRIMARY KEY (origin, title_key)
);

CREATE TABLE discipline (
  code text PRIMARY KEY,
  label text NOT NULL,
  parent text REFERENCES discipline,
  replaced_by text REFERENCES discipline,
  sort_order int NOT NULL
);
INSERT INTO discipline (code, label, sort_order) VALUES
  ('1103', 'Animal Science and Zoology', 1),
  ('1104', 'Aquatic Science', 2),
  ('2303', 'Ecology', 3),
  ('1105', 'Ecology, Evolution, Behavior and Systematics', 4),
  ('1109', 'Insect Science', 5),
  ('2404', 'Microbiology', 6),
  ('1911', 'Paleontology', 7),
  ('2405', 'Parasitology', 8),
  ('1110', 'Plant Science', 9);

CREATE TABLE discipline_mapping (
  scheme text NOT NULL,
  external_code text NOT NULL,
  discipline text NOT NULL REFERENCES discipline,
  weight real NOT NULL DEFAULT 1,
  PRIMARY KEY (scheme, external_code, discipline)
);
INSERT INTO discipline_mapping (scheme, external_code, discipline, weight)
  SELECT 'OPENALEX_SUBFIELD', code, code, 1 FROM discipline;
INSERT INTO discipline_mapping (scheme, external_code, discipline, weight) VALUES
  ('DOAJ_LCC', 'QL1-991', '1103', 1),
  ('DOAJ_LCC', 'QK1-989', '1110', 1),
  ('DOAJ_LCC', 'QH1-278.5', '1105', 1),
  ('DOAJ_LCC', 'QR1-502', '2404', 1),
  ('DOAJ_LCC', 'QH540-549.5', '2303', 1),
  ('DOAJ_LCC', 'QE701-760', '1911', 1),
  ('DOAJ_LCC', 'SH1-691', '1104', 1),
  ('DOAJ_LCC', 'QH301-705.5', '1105', 0.5),
  ('DOAJ_LCC', 'RC109-216', '2405', 0.5),
  ('TAXGROUP', 'Insects', '1109', 1),
  ('TAXGROUP', 'Plants', '1110', 1),
  ('TAXGROUP', 'Fungi', '1110', 0.5),
  ('TAXGROUP', 'Fungi', '2404', 0.5),
  ('TAXGROUP', 'Prokaryotes', '2404', 1),
  ('TAXGROUP', 'Viruses', '2404', 1),
  ('TAXGROUP', 'Protists', '2404', 1),
  ('TAXGROUP', 'Animals', '1103', 1);

CREATE TABLE source_classification_raw (
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  scheme text NOT NULL,
  code text NOT NULL,
  label text,
  share real,
  rank smallint NOT NULL,
  fetched timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, scheme, code)
);
INSERT INTO source_classification_raw (source_id, scheme, code, label, share, rank, fetched)
  SELECT s.id, 'OPENALEX_SUBFIELD', sf.code, NULL, NULL, (sf.ord - 1)::smallint, s.modified
  FROM source s, unnest(s.subfields) WITH ORDINALITY AS sf(code, ord)
  ON CONFLICT DO NOTHING;
UPDATE source SET discipline_source = 'OPENALEX' WHERE subfields <> '{}';

CREATE TABLE source_sample (
  id bigserial PRIMARY KEY,
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  sampled_at timestamptz NOT NULL DEFAULT now(),
  route text,
  articles int NOT NULL,
  with_abstract int NOT NULL,
  names_per_article real,
  act_share real,
  taxgroups jsonb NOT NULL DEFAULT '{}',
  error text
);
CREATE INDEX source_sample_source_idx ON source_sample (source_id, sampled_at DESC);

CREATE TABLE source_registration (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app_user ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('ISSN','DOI','URL')),
  input text NOT NULL,
  note text,
  state text NOT NULL DEFAULT 'IDENTIFYING' CHECK (state IN ('IDENTIFYING','IDENTIFIED','NOT_IDENTIFIED','WITHDRAWN')),
  outcome text CHECK (outcome IN ('NEW_CANDIDATE','EXISTING','CEASED')),
  reason text,
  source_id bigint REFERENCES source ON DELETE SET NULL,
  tier_at_identification text,
  decision text CHECK (decision IN ('ACCEPTED','DECLINED')),
  decision_at timestamptz,
  created timestamptz NOT NULL DEFAULT now(),
  identified_at timestamptz
);
CREATE INDEX source_registration_user_idx ON source_registration (user_id, created);
CREATE INDEX source_registration_state_idx ON source_registration (state);

CREATE TABLE admin_notice (
  id bigserial PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('LAYER_FAILING','LAYER_RECOVERED','LAYER_STILL_FAILING','ROUTE_RESTART_CAP',
    'REGISTRATIONS_WAITING')),
  dedup_key text NOT NULL UNIQUE,
  subject text NOT NULL,
  body text NOT NULL,
  created timestamptz NOT NULL DEFAULT now(),
  mailed_at timestamptz,
  outcome text
);
CREATE INDEX admin_notice_unmailed_idx ON admin_notice (created) WHERE mailed_at IS NULL;

ALTER TABLE source_route ADD COLUMN long_restarts smallint NOT NULL DEFAULT 0;
