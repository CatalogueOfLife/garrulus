-- M6c institutional route: the Elsevier TDM answer is its own XML (neither JATS nor TEI). Expand-only: a widened CHECK.
ALTER TABLE content DROP CONSTRAINT content_kind_check;
ALTER TABLE content ADD CONSTRAINT content_kind_check
  CHECK (kind IN ('TAXPUB','JATS','TEI','ABSTRACT','ELSEVIER_XML'));
