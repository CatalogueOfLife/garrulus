-- the share the salience of the indexed works was last computed with; RESCORE_SALIENCE targets names whose
-- ubiquity penalty moved away from it (expand-only)
ALTER TABLE name_ubiquity ADD COLUMN scored_share real;
