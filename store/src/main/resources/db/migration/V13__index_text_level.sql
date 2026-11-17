-- M5 final fixes (expand-only). The text level an index was built from: work.text_level changes as soon as a full text
-- arrives, while the live index of a listed work stays the old (abstract) one until its re-index is swapped in. The
-- re-index hit rule judges the old index at the level it was built from. names_text_level belongs to the live index,
-- pending_text_level to the pending one; the swap adopts the latter.
ALTER TABLE work
  ADD COLUMN names_text_level text CHECK (names_text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT')),
  ADD COLUMN pending_text_level text CHECK (pending_text_level IN ('FULLTEXT','ABSTRACT_ONLY','NO_TEXT'));

-- the level a named work's live index was built from is, as far as anything tells, its text level today
UPDATE work SET names_text_level = text_level WHERE names_doc_sha IS NOT NULL;
