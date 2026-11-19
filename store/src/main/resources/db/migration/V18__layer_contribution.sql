-- M6a: the layers' 28-day contribution as of a coverage check (first-found and only-found per layer and journal country).
-- country '' stands for a journal without a country.
CREATE TABLE layer_contribution (
  day date NOT NULL,
  layer text NOT NULL,
  country text NOT NULL DEFAULT '',
  first_found bigint NOT NULL DEFAULT 0,
  only_found bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (day, layer, country)
);
