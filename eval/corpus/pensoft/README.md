# Pensoft silver corpus

300 open access articles from the Pensoft journals **ZooKeys**, **PhytoKeys**, **MycoKeys** and **Biodiversity Data
Journal** (BDJ), used to evaluate the Garrulus name finder: 100 in the `dev` split, 200 in `test`.

- `manifest.tsv`: one row per article with its DOI, journal, licence, split and the URLs of its TaxPub XML and PDF.
- `gold.jsonl.gz`: the converted articles with the names, acts and classification contexts the publisher tagged in
  the TaxPub XML.

## Licence and attribution

All articles are published under the Creative Commons Attribution 4.0 licence (CC BY 4.0),
<https://creativecommons.org/licenses/by/4.0/>. The `license` column of the manifest records it per article. Each
article is attributed by its DOI in `manifest.tsv`: `https://doi.org/<doi>` resolves to the article with its authors,
title and journal.

**Modifications:** markup stripped, text normalised, converted to the Garrulus document model. The publisher's taxon
markup is kept as separate gold annotations. Reference lists are included as blocks but excluded from scoring.

## Rebuilding

`eval rebuild-pensoft --corpus eval/corpus/pensoft` re-fetches every article's XML (1 request per second, User-Agent
`Garrulus-eval/0.1`) and re-extracts the text and the gold with the current converter. It keeps the documents, their
ids, splits and licences; see `CLAUDE.md` in the repository root.
