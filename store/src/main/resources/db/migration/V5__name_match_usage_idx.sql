-- the name page looks up the match rows of a usage in the current release
CREATE INDEX name_match_release_usage_idx ON name_match (release_key, usage_id);
