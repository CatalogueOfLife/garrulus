-- Garrulus schema. The indexer applies the migrations; web only validates them.
-- Columns keep the order in which they were introduced during development, so related columns are not always
-- adjacent; the comments group them.

CREATE EXTENSION IF NOT EXISTS pg_trgm;


-- ===================================================================================================================
-- Journals: the registry, its routes, signals, identifiers and classifications
-- ===================================================================================================================

CREATE TABLE source (
  id bigserial PRIMARY KEY,
  -- identity: ISSNs are stored upper case in the hyphenated form (NNNN-NNNC), so ISSN lookups compare them as they
  -- are (issn_l = x OR issns @> ARRAY[x]) and use source_issn_l_idx and source_issns_idx
  openalex_id text UNIQUE, issn_l text, issns text[] NOT NULL DEFAULT '{}',
  title text NOT NULL, publisher text, host text, is_oa boolean, in_doaj boolean,
  tier text NOT NULL DEFAULT 'CANDIDATE' CHECK (tier IN ('CORE','BROAD','CANDIDATE','EXCLUDED')),
  origin text NOT NULL CHECK (origin IN ('SEED_TOPIC','SEED_LIST','DISCOVERED','MANUAL',
    'DOAJ','PKP_BEACON','PLAZI','COL_REFERENCES','BLR','REGISTERED')),
  adapter_config jsonb NOT NULL DEFAULT '{}', active boolean NOT NULL DEFAULT true,
  -- categorisation (CATEGORISE_SOURCES)
  subfields text[] NOT NULL DEFAULT '{}', primary_subfield text,
  journal_type text CHECK (journal_type IN ('TAXONOMIC','GENERAL')),
  taxgroups text[] NOT NULL DEFAULT '{}', taxgroup_shares jsonb NOT NULL DEFAULT '{}', codes text[] NOT NULL DEFAULT '{}',
  oa_model text CHECK (oa_model IN ('DIAMOND','GOLD','HYBRID','CLOSED')), country text,
  name_density real, act_rate real, works_per_year integer, last_harvested timestamptz,
  created timestamptz NOT NULL DEFAULT now(), modified timestamptz NOT NULL DEFAULT now(),
  onboarded_at timestamptz,
  tier_set_by text NOT NULL DEFAULT 'SEED' CHECK (tier_set_by IN ('SEED','ADMIN')),
  -- when the Crossref history of the journal (route backfill, HARVEST_CROSSREF_HISTORY) last ran to its end, so that
  -- later automatic route backfills do not read it again (an admin ROUTE_BACKFILL with "force":true does)
  crossref_history_at timestamptz,
  -- publication status (a CEASED journal is skipped by every harvest), set by an admin
  publication_status text NOT NULL DEFAULT 'ACTIVE' CHECK (publication_status IN ('ACTIVE','CEASED')),
  ceased_reason text CHECK (ceased_reason IN ('STOPPED','TITLE_CHANGE','MERGED','UNKNOWN')),
  last_issue_year smallint,
  continued_by bigint REFERENCES source,
  status_set_by text CHECK (status_set_by IN ('ADMIN')),
  status_changed timestamptz,
  -- the coverage check's dormancy proposal (shown, never applied)
  dormant_since date,
  dormant_evidence jsonb,
  dormant_dismissed_at timestamptz,
  -- candidate score (CandidateScore)
  candidate_score real,
  candidate_scored_at timestamptz,
  -- disciplines: derived from source_classification_raw unless an admin set them
  discipline_source text CHECK (discipline_source IN ('OPENALEX','DOAJ','TAXGROUP','ADMIN')),
  discipline_set_by text NOT NULL DEFAULT 'DERIVED' CHECK (discipline_set_by IN ('DERIVED','ADMIN')),
  -- DOAJ
  doaj_discontinued date,
  successor_issns text[] NOT NULL DEFAULT '{}',
  -- the journal's languages
  languages text[] NOT NULL DEFAULT '{}'
);
CREATE INDEX source_subfields_idx ON source USING gin (subfields);
CREATE INDEX source_taxgroups_idx ON source USING gin (taxgroups);
CREATE INDEX source_codes_idx ON source USING gin (codes);
CREATE INDEX source_issn_l_idx ON source (issn_l);
CREATE INDEX source_issns_idx ON source USING gin (issns);
-- the foreign key check of every fold's DELETE and its continued_by repoint
CREATE INDEX source_continued_by_idx ON source (continued_by) WHERE continued_by IS NOT NULL;

-- a harvest route of a journal in a layer (OAI, MAGTECH, EUROPEPMC)
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
  -- restarts of an ONBOARDING/BACKFILL OAI pass after a lost resumption token
  long_restarts smallint NOT NULL DEFAULT 0,
  UNIQUE (source_id, layer, config)
);

-- why a journal is known: one row per discovery origin and key (signal_key '' or a title key, a registration id)
CREATE TABLE source_signal (
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  origin text NOT NULL,
  signal_key text NOT NULL DEFAULT '',
  first_seen timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz NOT NULL DEFAULT now(),
  detail jsonb NOT NULL DEFAULT '{}',
  PRIMARY KEY (source_id, origin, signal_key)
);

-- lookup identifiers of a journal (WIKIDATA, IPNI_PUB, BPH, BHL_TITLE, DOAJ, CROSSREF_JOURNAL). ISSN, ISSN-L and the
-- OpenAlex id stay the source columns and are not mirrored here.
CREATE TABLE source_identifier (
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  scheme text NOT NULL,
  value text NOT NULL,
  origin text NOT NULL,
  first_seen timestamptz NOT NULL DEFAULT now(),
  UNIQUE (scheme, value)
);
CREATE INDEX source_identifier_source_idx ON source_identifier (source_id);

-- ISSN to ISSN-L (REFRESH_ISSNL)
CREATE TABLE issn_link (
  issn text PRIMARY KEY,
  issn_l text NOT NULL
);
CREATE INDEX issn_link_issn_l_idx ON issn_link (issn_l);

-- the PKP Beacon rows with an ISSN, replaced by every PKP_BEACON discovery run
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

-- journal titles found by discovery (Plazi, COL references), resolved to ISSNs through Crossref
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

-- the discipline vocabulary (OpenAlex subfield codes); a retired code points at its replacement
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

-- crosswalks from external classifications to disciplines
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

-- external classifications of a journal as fetched (OpenAlex subfields and topics, DOAJ LCC), ranked
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

-- one aggregate row per sample of a candidate's newest articles (SAMPLE_SOURCE); the articles are not kept
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


-- ===================================================================================================================
-- Works
-- ===================================================================================================================

-- one row per finder name, version, config hash and dictionary the indexer ran
CREATE TABLE finder_run (id bigserial PRIMARY KEY, name text NOT NULL, version text NOT NULL, config_hash text NOT NULL,
  dictionary_key text, created timestamptz NOT NULL DEFAULT now(),
  UNIQUE NULLS NOT DISTINCT (name, version, config_hash, dictionary_key));

CREATE TABLE work (
  id bigserial PRIMARY KEY,
  -- a work may be known by other identifiers only (work_identifier)
  openalex_id text UNIQUE, doi text UNIQUE,          -- doi lower-case, without https://doi.org/
  source_id bigint REFERENCES source,
  title text, publication_date date, year smallint, volume text, issue text, pages text,
  authors jsonb NOT NULL DEFAULT '[]', type text,
  -- the decided language (LanguageService.decide is its only writer; language_source says how)
  language text,
  oa_status text, license text, best_oa_url text,
  abstract text, abstract_source text CHECK (abstract_source IN ('CROSSREF_JATS','OPENALEX_INVERTED','PUBLISHER',
    'EUROPEPMC','OAI','MAGTECH','SPRINGER','SCIELO','CINII','INSTITUTIONAL')),
  origin text NOT NULL CHECK (origin IN ('LIVE','SWEEP','ONBOARDING','BACKFILL')),
  state text NOT NULL DEFAULT 'DISCOVERED'
    CHECK (state IN ('DISCOVERED','CONTENT_RESOLVED','NAMES_FOUND','MATCHED','INDEXED','NOTIFIED')),
  text_level text CHECK (text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT')),
  codes text[] NOT NULL DEFAULT '{}', taxgroups text[] NOT NULL DEFAULT '{}', has_acts boolean NOT NULL DEFAULT false,
  first_seen_at timestamptz NOT NULL DEFAULT now(), modified timestamptz NOT NULL DEFAULT now(),
  abstract_jats text,
  pmcid text,
  openalex_has_tei boolean NOT NULL DEFAULT false,
  openalex_checked_at timestamptz,
  -- the live index: the finder run and document it was built from
  current_finder_run bigint REFERENCES finder_run, names_doc_sha text,
  finder_diagnostics jsonb NOT NULL DEFAULT '{}', indexed_at timestamptz,
  -- the search covers the title, the English title (parallel or machine, so an English query finds a zh or ru work)
  -- and the abstract the work page shows: the Crossref JATS abstract first (a work harvested from Crossref alone has
  -- no other), else the OpenAlex one. Paragraph-like tags become spaces, other tags go without a trace (CO<sub>2</sub>
  -- stays CO2), entities are dropped.
  fts tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(title, '') || ' '
    || coalesce(title_en, '') || ' ' || coalesce(
    nullif(btrim(regexp_replace(regexp_replace(regexp_replace(abstract_jats,
      '</?(jats:)?(p|title|sec|br|div|list|list-item)([[:space:]/][^<>]*)?>', ' ', 'gi'),
      '<[a-zA-Z/!][^<>]*>', '', 'g'),
      '&#?[a-zA-Z0-9]+;', ' ', 'g')), ''),
    abstract, ''))) STORED,
  -- first time the work became INDEXED, never changed afterwards (indexed_at is the last indexing)
  first_indexed_at timestamptz,
  -- no gaps while re-indexing: a re-index of a listed work writes the finder's result into the pending tables, and
  -- MATCH_NAMES moves it into the live tables in one transaction
  pending_finder_run bigint REFERENCES finder_run, pending_doc_sha text,
  pending_diagnostics jsonb,
  -- the XR release the work's names are placed in
  placed_release integer,
  reindex_prev_subscriptions bigint[],
  -- the text level an index was built from: text_level changes as soon as a full text arrives, while the live index
  -- of a listed work stays the old (abstract) one until its re-index is swapped in. The re-index hit rule judges the
  -- old index at the level it was built from; the swap adopts pending_text_level.
  names_text_level text CHECK (names_text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT')),
  pending_text_level text CHECK (pending_text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT')),
  -- the layer that owns the metadata, the JATS abstract and the access fields
  meta_layer text, abstract_jats_source text, access_layer text,
  -- language decision (the evidence is in work_language_evidence), English title
  language_source text CHECK (language_source IN ('FULLTEXT','DECLARED','DETECTED','OPENALEX','JOURNAL','UNDECIDED')),
  language_decided_at timestamptz,
  title_lang text,
  abstract_lang text,
  title_en text,
  title_en_kind text CHECK (title_en_kind IN ('PARALLEL','MACHINE'))
);
CREATE INDEX work_source_idx ON work (source_id);
CREATE INDEX work_pubdate_idx ON work (publication_date);
CREATE INDEX work_codes_idx ON work USING gin (codes);
CREATE INDEX work_taxgroups_idx ON work USING gin (taxgroups);
CREATE INDEX work_state_idx ON work (state);
CREATE INDEX work_fts_idx ON work USING gin (fts);
-- XR_REPLACE takes the listed works placed in another release in id order, per release they are placed in
CREATE INDEX work_placed_release_idx ON work (placed_release, id) WHERE state IN ('INDEXED', 'NOTIFIED');
-- the candidates of WorkUpsert's title match: all of them for a record without a DOI, only DOI-less works for one with
CREATE INDEX work_source_year_idx ON work (source_id, year);
CREATE INDEX work_source_year_nodoi_idx ON work (source_id, year) WHERE doi IS NULL;
CREATE INDEX work_language_idx ON work (language) WHERE state IN ('INDEXED','NOTIFIED');

-- every identifier a work is known by (DOI, OPENALEX, PMCID, PMID, ...) and the layer that first gave it
CREATE TABLE work_identifier (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  scheme text NOT NULL,
  value text NOT NULL,
  first_layer text,
  first_seen timestamptz NOT NULL DEFAULT now(),
  UNIQUE (scheme, value)
);
CREATE INDEX work_identifier_work_idx ON work_identifier (work_id);

-- the first time each layer saw a work (coverage monitor)
CREATE TABLE work_sighting (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  layer text NOT NULL,
  source_id bigint,
  first_seen timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (work_id, layer)
);
CREATE INDEX work_sighting_layer_idx ON work_sighting (layer, first_seen);

-- the text a content route delivered; retained (open licence) texts are blobs, closed ones only pass the spool
CREATE TABLE content (
  id bigserial PRIMARY KEY,
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  route text NOT NULL CHECK (route IN ('PENSOFT','EUROPEPMC','PMC','OPENALEX_TEI','GROBID_LOCAL','ABSTRACT',
    'OAI_JATS','INSTITUTIONAL')),
  kind text NOT NULL CHECK (kind IN ('TAXPUB','JATS','TEI','ABSTRACT','ELSEVIER_XML')),
  license text, retained boolean NOT NULL, raw_sha256 text, doc_sha256 text, source_url text,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (work_id, route)
);

-- every version of a title or abstract: the original, a parallel one the publisher gave, a machine translation
CREATE TABLE work_text (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  field text NOT NULL CHECK (field IN ('TITLE','ABSTRACT')),
  lang text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('ORIGINAL','PARALLEL','MACHINE')),
  text text NOT NULL,
  source text NOT NULL,
  model text,
  created timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (work_id, field, lang, kind)
);

-- what each signal said about the language of a work (a journal's languages are source.languages, not a row here)
CREATE TABLE work_language_evidence (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('FULLTEXT','DECLARED','DETECTED','OPENALEX','LEGACY')),
  source text NOT NULL,
  lang text,
  confidence real,
  raw text,
  created timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (work_id, kind, source)
);

-- a text fallback service already asked for a work (an outage is not recorded)
CREATE TABLE text_lookup (
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  service text NOT NULL,
  tried_at timestamptz NOT NULL DEFAULT now(),
  found boolean NOT NULL,
  PRIMARY KEY (work_id, service)
);

-- audit of every institutional fetch
CREATE TABLE institutional_fetch (
  id bigserial PRIMARY KEY,
  work_id bigint REFERENCES work ON DELETE SET NULL,
  doi text NOT NULL,
  publisher text NOT NULL,
  api text NOT NULL,
  fetched_at timestamptz NOT NULL DEFAULT now(),
  status int NOT NULL,
  bytes int NOT NULL,
  outcome text NOT NULL
);
CREATE INDEX institutional_fetch_publisher_idx ON institutional_fetch (publisher, fetched_at);
CREATE INDEX institutional_fetch_work_idx ON institutional_fetch (work_id) WHERE work_id IS NOT NULL;

-- one row per entity (W work, S source), subject and identifier lookup. No foreign key (two subject tables): the link
-- sweep deletes rows of deleted subjects.
CREATE TABLE link_attempt (
  entity char(1) NOT NULL CHECK (entity IN ('W','S')),
  subject_id bigint NOT NULL,
  lookup text NOT NULL,
  attempts smallint NOT NULL,
  last_tried timestamptz NOT NULL,
  next_try timestamptz,
  found boolean NOT NULL,
  PRIMARY KEY (entity, subject_id, lookup)
);
CREATE INDEX link_attempt_next_try_idx ON link_attempt (next_try) WHERE next_try IS NOT NULL;


-- ===================================================================================================================
-- Names and the index
-- ===================================================================================================================

CREATE TABLE name_string (id bigserial PRIMARY KEY, name text NOT NULL UNIQUE, kind text NOT NULL, rank text,
  created timestamptz NOT NULL DEFAULT now());
CREATE INDEX name_string_trgm_idx ON name_string USING gin (name gin_trgm_ops);

-- the verbatim classification an article gives for its names
CREATE TABLE classification_context (id bigserial PRIMARY KEY, work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  local_id text NOT NULL, verbatim text NOT NULL, ranks jsonb NOT NULL, scope text NOT NULL, block_id text NOT NULL,
  until_block_id text, UNIQUE (work_id, local_id));

-- every occurrence of a name, at an exact span of the source text (code points)
CREATE TABLE mention (id bigserial PRIMARY KEY, work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  name_string_id bigint NOT NULL REFERENCES name_string, block_id text NOT NULL, block_type text NOT NULL,
  start_cp integer NOT NULL, end_cp integer NOT NULL, verbatim text NOT NULL, authorship text, qualifier text,
  confidence real NOT NULL, act_type text, context_id bigint REFERENCES classification_context ON DELETE SET NULL,
  snippet text NOT NULL, evidence jsonb NOT NULL DEFAULT '{}');
CREATE INDEX mention_work_idx ON mention (work_id);
CREATE INDEX mention_name_idx ON mention (name_string_id);

-- the pending index of a listed work being re-indexed. The ids come from the live tables' sequences, so they survive
-- the move into the live tables.
CREATE TABLE classification_context_pending (
  id bigint PRIMARY KEY DEFAULT nextval('classification_context_id_seq'),
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  local_id text NOT NULL, verbatim text NOT NULL, ranks jsonb NOT NULL, scope text NOT NULL, block_id text NOT NULL,
  until_block_id text, UNIQUE (work_id, local_id));

CREATE TABLE mention_pending (
  id bigint PRIMARY KEY DEFAULT nextval('mention_id_seq'),
  work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  name_string_id bigint NOT NULL REFERENCES name_string, block_id text NOT NULL, block_type text NOT NULL,
  start_cp integer NOT NULL, end_cp integer NOT NULL, verbatim text NOT NULL, authorship text, qualifier text,
  confidence real NOT NULL, act_type text,
  context_id bigint REFERENCES classification_context_pending ON DELETE SET NULL,
  snippet text NOT NULL, evidence jsonb NOT NULL DEFAULT '{}');
CREATE INDEX mention_pending_work_idx ON mention_pending (work_id);

-- the COL XR releases. A release moves SEEN -> PREPARING (pre-match) -> PREPARED (gate pending) -> SWITCHING (current,
-- works being re-placed) -> CURRENT -> RETIRED; the gate or an admin can make it REFUSED. `current` stays the one flag
-- the readers use and is flipped together with `state`. `note` is a JSON object: the switch options (refuse, force)
-- and, for a REFUSED release, who refused it (refusedBy: gate, admin, rollback). `switch_gen` counts the switches
-- started to the release; the pre-match and gate tasks carry it, so that a chain of an earlier (cancelled) switch to
-- the same release stops instead of running alongside the new one.
CREATE TABLE xr_release (key integer PRIMARY KEY, alias text NOT NULL, first_seen timestamptz NOT NULL DEFAULT now(),
  current boolean NOT NULL DEFAULT false,
  state text NOT NULL DEFAULT 'SEEN'
    CHECK (state IN ('SEEN', 'PREPARING', 'PREPARED', 'SWITCHING', 'CURRENT', 'RETIRED', 'REFUSED')),
  switch_started timestamptz,
  switched_at timestamptz,
  keys_total integer,
  keys_lost integer,
  note text,
  switch_gen integer NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX xr_release_current_idx ON xr_release (current) WHERE current;

-- the CLB match of a name (with a classification hint) in a release
CREATE TABLE name_match (name_string_id bigint NOT NULL REFERENCES name_string, hint_key text NOT NULL,
  release_key integer NOT NULL REFERENCES xr_release, match_type text NOT NULL, usage_id text, usage_name text,
  usage_rank text, usage_status text, accepted_id text, code text, taxgroup text, taxon_path text[] NOT NULL DEFAULT '{}',
  classification jsonb NOT NULL DEFAULT '[]', matched_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (name_string_id, hint_key, release_key));
-- the name page looks up the match rows of a usage in the current release
CREATE INDEX name_match_release_usage_idx ON name_match (release_key, usage_id);

-- a name in a work: counts, where it occurs, its placement and salience
CREATE TABLE work_name (work_id bigint NOT NULL REFERENCES work ON DELETE CASCADE,
  name_string_id bigint NOT NULL REFERENCES name_string, mention_count integer NOT NULL, body_count integer NOT NULL,
  in_title boolean NOT NULL, in_abstract boolean NOT NULL, in_heading boolean NOT NULL, only_reference boolean NOT NULL,
  act_types text[] NOT NULL DEFAULT '{}', best_snippet text NOT NULL,
  placement_method text NOT NULL CHECK (placement_method IN ('MATCH','GENUS','VERBATIM_CONTEXT','NONE')),
  usage_id text, taxon_path text[] NOT NULL DEFAULT '{}', code text, taxgroup text,
  salience real NOT NULL, main_subject boolean NOT NULL, PRIMARY KEY (work_id, name_string_id));
CREATE INDEX work_name_path_idx ON work_name USING gin (taxon_path);
CREATE INDEX work_name_name_idx ON work_name (name_string_id);

-- scored_share is the share the salience of the indexed works was last computed with; RESCORE_SALIENCE targets names
-- whose ubiquity penalty moved away from it
CREATE TABLE name_ubiquity (name_string_id bigint PRIMARY KEY REFERENCES name_string, works integer NOT NULL,
  share real NOT NULL, computed_at timestamptz NOT NULL DEFAULT now(),
  scored_share real);

CREATE TABLE taxon_stats (taxon_id text NOT NULL, year smallint NOT NULL, works integer NOT NULL,
  main_subject_works integer NOT NULL, PRIMARY KEY (taxon_id, year));

-- precomputed language breakdowns, filled by REBUILD_STATS (never a live GROUP BY over work on a public request).
-- Listed works per taxon (itself or any ancestor on work_name.taxon_path), work language ('' = undecided) and year.
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


-- ===================================================================================================================
-- Harvesting, layers and coverage
-- ===================================================================================================================

-- calls and spend per metered service and UTC day
CREATE TABLE api_usage (
  service text NOT NULL, day date NOT NULL,
  calls integer NOT NULL DEFAULT 0, usd numeric(10,4) NOT NULL DEFAULT 0,
  remaining_usd numeric(10,4),
  key_fingerprint text,
  PRIMARY KEY (service, day));

CREATE TABLE harvest_run (
  id bigserial PRIMARY KEY, adapter text NOT NULL, window_from date, window_to date,
  started timestamptz NOT NULL DEFAULT now(), finished timestamptz,
  calls integer NOT NULL DEFAULT 0, works_seen integer NOT NULL DEFAULT 0, works_new integer NOT NULL DEFAULT 0,
  state text NOT NULL DEFAULT 'RUNNING' CHECK (state IN ('RUNNING','DONE','DEFERRED','FAILED')), error text);

-- health of a layer per day (COVERAGE_CHECK)
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

-- 28-day sightings and misses per journal and layer
CREATE TABLE source_layer_coverage (
  source_id bigint NOT NULL REFERENCES source ON DELETE CASCADE,
  layer text NOT NULL,
  sightings_28d int NOT NULL DEFAULT 0,
  misses int NOT NULL DEFAULT 0,
  updated timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (source_id, layer)
);

-- DOIs a layer saw in no known journal (BLR discovery reads them)
CREATE TABLE layer_unmatched (
  layer text NOT NULL,
  doi text PRIMARY KEY,
  issn text,
  container_title text,
  first_seen timestamptz NOT NULL DEFAULT now()
);

-- the layers' 28-day contribution as of a coverage check (first-found and only-found per layer and journal country).
-- country '' stands for a journal without a country.
CREATE TABLE layer_contribution (
  day date NOT NULL,
  layer text NOT NULL,
  country text NOT NULL DEFAULT '',
  first_found bigint NOT NULL DEFAULT 0,
  only_found bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (day, layer, country)
);

-- like layer_contribution, per work language
CREATE TABLE layer_contribution_language (
  day date NOT NULL,
  layer text NOT NULL,
  language text NOT NULL DEFAULT '',
  first_found bigint NOT NULL DEFAULT 0,
  only_found bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (day, layer, language)
);


-- ===================================================================================================================
-- Task queue, locks and job state
-- ===================================================================================================================

-- started is the start of the last attempt; finished - started is its run time
CREATE TABLE task (
  id bigserial PRIMARY KEY,
  type text NOT NULL, payload jsonb NOT NULL DEFAULT '{}',
  priority smallint NOT NULL,
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','DONE','DEAD')),
  attempts smallint NOT NULL DEFAULT 0, run_after timestamptz NOT NULL DEFAULT now(),
  locked_by text, locked_at timestamptz, last_error text, dedup_key text,
  created timestamptz NOT NULL DEFAULT now(), finished timestamptz,
  started timestamptz
);
CREATE INDEX task_claim_idx ON task (priority, run_after, id) WHERE state = 'QUEUED';
CREATE UNIQUE INDEX task_dedup_idx ON task (dedup_key) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX task_type_state_idx ON task (type, state);
CREATE INDEX task_done_finished_idx ON task (finished) WHERE state = 'DONE';

CREATE TABLE shedlock (
  name varchar(64) PRIMARY KEY, lock_until timestamptz NOT NULL,
  locked_at timestamptz NOT NULL, locked_by varchar(255) NOT NULL
);

-- where a recurring job that walks a table in id order stopped, by job name (e.g. 'link-untried:W', the link sweep's
-- walk over listed works no identifier lookup has seen yet). Task rows cannot keep it: they are purged after 14 days.
CREATE TABLE job_cursor (
  name text PRIMARY KEY,
  position bigint NOT NULL,
  updated timestamptz NOT NULL DEFAULT now()
);

-- one row per finder configHash the indexer started with: the first one ever seen is the baseline (nothing
-- reprocessed); every later new hash is a rollout, whose REPROCESS_NAMES walk sets enqueued_at when its last batch
-- has been enqueued
CREATE TABLE finder_rollout (
  config_hash text PRIMARY KEY,
  started timestamptz NOT NULL DEFAULT now(),
  enqueued_at timestamptz,
  baseline boolean NOT NULL DEFAULT false);

-- one backfill_run per BACKFILL task: the year range, the snapshot (manifest) date and the number of works files it
-- covers. One backfill_file per file, idx being the file's position among the manifest's works files; BACKFILL_FILE
-- writes its counters and done_at once, at the end of the file, and the last file of a run marks the run DONE.
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


-- ===================================================================================================================
-- Accounts, follows, alerts, registrations and admin notices. web writes app_user, subscription, digest, email_token,
-- source_registration, hit.digest_id and the Spring tables; the indexer writes hit.
-- ===================================================================================================================

CREATE TABLE app_user (
  id bigserial PRIMARY KEY,
  email text,
  email_verified timestamptz,
  orcid text UNIQUE,
  display_name text,
  time_zone text NOT NULL DEFAULT 'UTC',
  digest_frequency text NOT NULL DEFAULT 'DAILY' CHECK (digest_frequency IN ('DAILY', 'WEEKLY', 'OFF')),
  digest_weekday smallint NOT NULL DEFAULT 1 CHECK (digest_weekday BETWEEN 1 AND 7),
  digest_hour smallint NOT NULL DEFAULT 7 CHECK (digest_hour BETWEEN 0 AND 23),
  digest_since timestamptz,
  created timestamptz NOT NULL DEFAULT now(),
  last_login timestamptz
);
-- case-insensitive uniqueness without the citext extension; several rows without an address are fine (NULLs)
CREATE UNIQUE INDEX app_user_email_lower_uq ON app_user (lower(email));

-- a follow. After the works are re-placed in a new XR release, XR_FINISH looks up every TAXON follow whose id that
-- release may not have: a follow whose id CLB reports superseded moves to the successor (target_status MOVED,
-- previous_target keeps the old id), any other one is flagged MISSING and keeps its id. xr_note is the line shown on
-- the Following page and in the digest footer, xr_note_at when it was written. The XR switch is the one writer of
-- these four columns and, for a move, of target.
CREATE TABLE subscription (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  type text NOT NULL CHECK (type IN ('TAXON', 'SOURCE', 'NAME', 'TAXGROUP')),
  target text NOT NULL,
  label text,
  main_subject_only boolean NOT NULL DEFAULT false,
  new_names_only boolean NOT NULL DEFAULT false,
  include_abstract_only boolean NOT NULL DEFAULT true,
  include_references boolean NOT NULL DEFAULT false,
  filters jsonb NOT NULL DEFAULT '{}',
  feed_token text UNIQUE,
  since_year smallint,
  paused boolean NOT NULL DEFAULT false,
  created timestamptz NOT NULL DEFAULT now(),
  target_status text NOT NULL DEFAULT 'OK' CHECK (target_status IN ('OK', 'MOVED', 'MISSING')),
  xr_note text,
  xr_note_at timestamptz,
  previous_target text,
  -- the parts of a work a name must occur in (empty = anywhere)
  mentioned_in text[] NOT NULL DEFAULT '{}' CHECK (mentioned_in <@ ARRAY['TITLE','ABSTRACT','BODY']),
  UNIQUE (user_id, type, target)
);
CREATE INDEX subscription_type_target_idx ON subscription (type, target);

CREATE TABLE digest (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  sent_at timestamptz NOT NULL DEFAULT now(),
  hits integer NOT NULL DEFAULT 0,
  status text NOT NULL CHECK (status IN ('SENT', 'FAILED')),
  error text
);
CREATE INDEX digest_user_idx ON digest (user_id, sent_at);

CREATE TABLE hit (
  id bigserial PRIMARY KEY,
  subscription_id bigint NOT NULL REFERENCES subscription (id) ON DELETE CASCADE,
  work_id bigint NOT NULL REFERENCES work (id) ON DELETE CASCADE,
  kind text NOT NULL CHECK (kind IN ('ARTICLE', 'NEW_NAMES')),
  created timestamptz NOT NULL DEFAULT now(),
  names jsonb NOT NULL DEFAULT '[]',
  digest_id bigint REFERENCES digest (id) ON DELETE SET NULL,
  UNIQUE (subscription_id, work_id, kind)
);
CREATE INDEX hit_subscription_digest_idx ON hit (subscription_id, digest_id);
CREATE INDEX hit_work_idx ON hit (work_id);
CREATE INDEX hit_digest_idx ON hit (digest_id);

CREATE TABLE email_token (
  id bigserial PRIMARY KEY,
  user_id bigint NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
  email text NOT NULL,
  token_hash text NOT NULL UNIQUE,
  expires timestamptz NOT NULL
);

-- a journal a user registered (by ISSN, DOI or URL), identified by the indexer (IDENTIFY_SOURCE)
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
-- every fold moves registrations; ON DELETE SET NULL, the admin list's registered filter and counts
CREATE INDEX source_registration_source_idx ON source_registration (source_id);

-- notices the indexer writes and web mails to the admins
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

-- Spring Session JDBC (schema-postgresql.sql of spring-session-jdbc), created here, never by auto-init
CREATE TABLE spring_session (
  primary_id char(36) NOT NULL,
  session_id char(36) NOT NULL,
  creation_time bigint NOT NULL,
  last_access_time bigint NOT NULL,
  max_inactive_interval int NOT NULL,
  expiry_time bigint NOT NULL,
  principal_name varchar(100),
  CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
  session_primary_id char(36) NOT NULL,
  attribute_name varchar(200) NOT NULL,
  attribute_bytes bytea NOT NULL,
  CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
  CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- Spring Security one-time-token login (JdbcOneTimeTokenService); username is an email address, so wider than 50
CREATE TABLE one_time_tokens (
  token_value varchar(36) PRIMARY KEY,
  username varchar(320) NOT NULL,
  expires_at timestamp NOT NULL
);
