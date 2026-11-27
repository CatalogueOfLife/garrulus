-- work_name.body_count counted every mention outside the references, title and abstract included, so the mentioned-in
-- filter BODY ("full text": body_count > 0 or in_heading) matched works without any full text. It now counts mentions
-- in body text only, as WorkIndexer does: block types other than TITLE, ABSTRACT, HEADING and REFERENCE, and none at all
-- in a work whose names come from an abstract only (names_text_level, else text_level, ABSTRACT_ONLY: its blocks are
-- all abstract). only_reference and main_subject keep their meaning and are not touched.
UPDATE work_name wn SET body_count = b.n
FROM (
  SELECT wn2.work_id, wn2.name_string_id,
         CASE WHEN COALESCE(w.names_text_level, w.text_level) = 'ABSTRACT_ONLY' THEN 0
              ELSE count(m.id) FILTER (WHERE m.block_type NOT IN ('TITLE', 'ABSTRACT', 'HEADING', 'REFERENCE')) END AS n
  FROM work_name wn2
  JOIN work w ON w.id = wn2.work_id
  LEFT JOIN mention m ON m.work_id = wn2.work_id AND m.name_string_id = wn2.name_string_id
  GROUP BY wn2.work_id, wn2.name_string_id, w.names_text_level, w.text_level
) b
WHERE wn.work_id = b.work_id AND wn.name_string_id = b.name_string_id AND wn.body_count <> b.n;
