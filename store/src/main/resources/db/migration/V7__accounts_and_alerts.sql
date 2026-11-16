-- M4 accounts and alerts (expand-only). web writes app_user, subscription, digest, email_token, hit.digest_id and the
-- Spring tables; the indexer writes hit and work.first_indexed_at.

-- first time a work became INDEXED, never changed afterwards (indexed_at keeps meaning "last indexing")
ALTER TABLE work ADD COLUMN first_indexed_at timestamptz;
UPDATE work SET first_indexed_at = indexed_at WHERE indexed_at IS NOT NULL;

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
