-- M6c: language evidence and decision, parallel and machine titles, journal lookup identifiers, link attempts,
-- text fallbacks, the institutional audit, followed-in filters. Expand-only (widened CHECKs and the regenerated
-- work.fts count as expand).

-- language decision of a work (work.language stays the decided language; the evidence rows below say why)
ALTER TABLE work
  ADD COLUMN language_source text CHECK (language_source IN ('FULLTEXT','DECLARED','DETECTED','OPENALEX','JOURNAL','UNDECIDED')),
  ADD COLUMN language_decided_at timestamptz,
  ADD COLUMN title_lang text,
  ADD COLUMN abstract_lang text,
  ADD COLUMN title_en text,
  ADD COLUMN title_en_kind text CHECK (title_en_kind IN ('PARALLEL','MACHINE'));
CREATE INDEX work_language_idx ON work (language) WHERE state IN ('INDEXED','NOTIFIED');

ALTER TABLE work DROP CONSTRAINT work_abstract_source_check;
ALTER TABLE work ADD CONSTRAINT work_abstract_source_check
  CHECK (abstract_source IN ('CROSSREF_JATS','OPENALEX_INVERTED','PUBLISHER','EUROPEPMC','OAI','MAGTECH',
    'SPRINGER','SCIELO','CINII','INSTITUTIONAL'));

-- the new content routes (OAI_JATS, INSTITUTIONAL) write content rows
ALTER TABLE content DROP CONSTRAINT content_route_check;
ALTER TABLE content ADD CONSTRAINT content_route_check
  CHECK (route IN ('PENSOFT','EUROPEPMC','PMC','OPENALEX_TEI','GROBID_LOCAL','ABSTRACT','OAI_JATS','INSTITUTIONAL'));

-- V15's expression plus the English title (parallel or machine), so an English query finds a zh or ru work
ALTER TABLE work ALTER COLUMN fts SET EXPRESSION AS (to_tsvector('simple', coalesce(title, '') || ' '
  || coalesce(title_en, '') || ' ' || coalesce(
  nullif(btrim(regexp_replace(regexp_replace(regexp_replace(abstract_jats,
    '</?(jats:)?(p|title|sec|br|div|list|list-item)([[:space:]/][^<>]*)?>', ' ', 'gi'),
    '<[a-zA-Z/!][^<>]*>', '', 'g'),
    '&#?[a-zA-Z0-9]+;', ' ', 'g')), ''),
  abstract, '')));

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
INSERT INTO work_language_evidence (work_id, kind, source, lang, raw)
SELECT id, 'LEGACY', 'V21', language, language FROM work WHERE language IS NOT NULL;

ALTER TABLE source ADD COLUMN languages text[] NOT NULL DEFAULT '{}';

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

-- one row per entity (W work, S source), subject and lookup. No foreign key (two subject tables): the link sweep
-- deletes rows of deleted subjects.
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

ALTER TABLE subscription ADD COLUMN mentioned_in text[] NOT NULL DEFAULT '{}'
  CHECK (mentioned_in <@ ARRAY['TITLE','ABSTRACT','BODY']);

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

-- like layer_contribution (V18), per work language
CREATE TABLE layer_contribution_language (
  day date NOT NULL,
  layer text NOT NULL,
  language text NOT NULL DEFAULT '',
  first_found bigint NOT NULL DEFAULT 0,
  only_found bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (day, layer, language)
);
